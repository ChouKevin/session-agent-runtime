# Session Agent Runtime

Session Agent Runtime is a standalone, provider-neutral conversation service. It owns durable conversation history, conversation-job ordering, model calls, MCP tool execution, and PostgreSQL storage. Tool providers connect through named MCP streamable-HTTP endpoints; the runtime treats each tool definition and result as opaque provider data.

## Where changes belong

For the current module boundaries, allowed dependencies, and test ownership, see
the [package architecture guide](docs/package-architecture.md).

| Change | Location |
| --- | --- |
| Session, message, job, or history rules | `src/main/java/com/java/system/sessionagent/conversation/domain`, `conversation/application`, `conversation/port/in`, and `conversation/port/out` |
| Chat-model contract | `src/main/java/com/java/system/sessionagent/conversation/port/out` |
| Chat-model provider integration | `src/main/java/com/java/system/sessionagent/model/adapter/springai` |
| MCP discovery, routing, connection lifecycle, or diagnostics | `src/main/java/com/java/system/sessionagent/mcp/domain`, `src/main/java/com/java/system/sessionagent/mcp/configuration`, `src/main/java/com/java/system/sessionagent/mcp/adapter/sdk`, and `src/main/java/com/java/system/sessionagent/mcp/adapter/actuator` |
| Generic tool calls and observations | `src/main/java/com/java/system/sessionagent/tool/domain` and `src/main/java/com/java/system/sessionagent/tool/port` |
| PostgreSQL persistence or migrations | `src/main/java/com/java/system/sessionagent/storage/adapter/postgres` and `src/main/resources/db/migration` |
| Message, job, or session-history HTTP endpoints | `src/main/java/com/java/system/sessionagent/web/adapter/http` |
| MCP actuator diagnostics | `src/main/java/com/java/system/sessionagent/mcp/adapter/actuator` |
| Queued message-job claim, renewal, processing, and recovery | `src/main/java/com/java/system/sessionagent/worker/application` and `src/main/java/com/java/system/sessionagent/worker/configuration` |
| Slack intake, delivery, Socket Mode, and Slack persistence | `src/main/java/com/java/system/sessionagent/slack/domain`, `src/main/java/com/java/system/sessionagent/slack/application`, `src/main/java/com/java/system/sessionagent/slack/port/out`, `src/main/java/com/java/system/sessionagent/slack/adapter/sdk`, `src/main/java/com/java/system/sessionagent/slack/adapter/postgres`, and `src/main/java/com/java/system/sessionagent/slack/configuration` |
| Spring composition and runtime configuration | `src/main/java/com/java/system/sessionagent/bootstrap/configuration` |
| Runtime metrics adapter | `src/main/java/com/java/system/sessionagent/bootstrap/adapter/metrics` |

Keep provider SDK details inside model adapters and keep MCP provider data opaque.
Runtime must not acquire provider-specific tool DTOs or business rules. Coding-agent
guidance is in [AGENTS.md](AGENTS.md).

## Conversation loop

One submitted user message starts one conversation turn. A turn can make one or more model calls. A model call either returns assistant text or requests tools. The runtime executes every requested call sequentially in the model-provided order, then atomically appends one `ASSISTANT_TOOL_CALLS` event followed by all paired `TOOL` observations in one batch; those observations are supplied to the next model call.

```text
load complete ordered session history and available tools
                         |
                         v
                    call model
              +----------+----------+
              |                     |
           direct text          tool requests
              |                     |
     append assistant text   run every request in order
     complete the job                    |
                                          v
                         atomically append ASSISTANT_TOOL_CALLS
                         followed by paired TOOL observations
                                    |
                                    v
                             call model again
```

The runtime exposes registered tools to the model but does not interpret a provider's input schema or tool output. One ordinary tool failure is recorded as that tool's observation and does not prevent later requests in the same batch.

## Durable history and HTTP API

Submit a message, poll its job, then read the complete session history:

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
GET  /internal/sessions/{sessionId}/messages
PUT  /internal/sessions/{sessionId}/model
GET  /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same session. The accepted response contains `sessionId` and `messageJobId`. The job response contains its IDs, `status`, `retryCount`, and `modelCallCount`.

`PUT /internal/sessions/{sessionId}/model` accepts `{"model":"configured-key"}` and returns `200` with `{sessionId, model, effectiveFor:"NEW_MESSAGES"}`. Only messages accepted after the change use the selected model; queued, running, and retrying jobs keep the model captured when they were accepted. Selecting a configured model takes effect without restarting Runtime.

`GET /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...` returns a safe delivery-status array for exactly one Slack source. Each row has `deliveryId`, `kind`, `status`, `attemptCount`, `failureCategory`, `nextAttemptAt`, `jobId`, `totalParts`, and `sentParts`. Absent optional values are `null`; `totalParts` is `null` when no parts exist, while `sentParts` is always a number (`0` when none have been sent). Missing, blank, or duplicate query parameters return `400`. It never returns delivery text, tokens, URLs, or provider errors. Slack delivery status is separate from the model job result; an unknown source returns an empty array.

`GET /internal/sessions/{sessionId}/messages` accepts optional `afterSequence` and `limit` query parameters. Omitting both returns the complete committed history. When either is present, results are the ascending raw-history page after `afterSequence` (default `0`), limited by `limit` when supplied; an exhausted cursor for an existing session returns an empty list, while an unknown session returns `404`.

History records share `sequence`, `createdAt`, and `messageJobId` fields. The public event shapes are:

| Type | Type-specific fields |
| --- | --- |
| `USER` | `participantId`, `message` |
| `ASSISTANT_TOOL_CALLS` | optional `message`, `calls`: ordered `{toolCallId, toolName, arguments}` values |
| `TOOL` | `toolCallId`, `toolName`, opaque `output`; each observation is paired with a preceding `ASSISTANT_TOOL_CALLS` call ID |
| `ASSISTANT` | `message` |
| `RUNTIME` | `code`, `message` |

Tool inputs and outputs are opaque runtime data. There is no separate tool-result lookup endpoint; use the message-job endpoint for job state and the Slack-delivery endpoint for outbound delivery state.

## Queue and intake

The default worker configuration is `session-agent.worker.concurrency: 4`. The database queue capacity is `session-agent.worker.max-unfinished-jobs: 100`, counting all `PENDING`, `WORKING`, and `RETRY` jobs together. Terminal jobs release capacity. The database is the waiting queue: jobs in the same session run FIFO, while different sessions can run in parallel.

When this unfinished-job capacity is full, a new HTTP message is rejected with `429` and `{"code":"queue_full"}`. A successful resend of an already accepted message returns its original `202` receipt instead of creating another job.

## Generic MCP connections

Configure zero or more named connections under `session-agent.mcp.connections`. A connection name is portable (`[A-Za-z][A-Za-z0-9-]{0,31}`); discovered tools are exposed as `{connectionName}_{rawToolName}`. The URL is the exact absolute streamable-HTTP endpoint, including any non-default path.

```yaml
session-agent:
  mcp:
    connections:
      catalog:
        enabled: true
        url: ${SESSION_AGENT_MCP_CATALOG_URL}
        headers:
          Authorization: ${SESSION_AGENT_MCP_CATALOG_AUTHORIZATION}
```

Headers are optional. Omit the header entry when a provider does not require it; otherwise source the value from the environment as shown, rather than committing a credential. Runtime defaults are a 60-second catalog refresh, 30-second request timeout, reconnect backoff from 1 to 60 seconds, and a 5-second shutdown timeout.

Docker Compose starts with zero MCP connections when `SESSION_AGENT_MCP_CONFIGURATION_JSON` is unset. To configure one through Compose, set that environment variable to the equivalent generic configuration, for example `{"session-agent":{"mcp":{"connections":{"catalog":{"enabled":true,"url":"https://host/custom/mcp"}}}}}`. Supply any optional header values through the deployment environment or secret manager; the exact URL and headers are passed to Runtime without provider-specific behavior.

Startup is safe with no configured connections or when every configured server is unavailable. Each connection reconnects and refreshes independently, so a failed provider does not hide tools from an available provider. The `mcp` module exposes `GET /actuator/mcpConnections` with safe per-connection diagnostics: state (`DISABLED`, `CONNECTING`, `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, or `STOPPED`), discovered tool count, and when applicable a safe failure code/message. It never exposes endpoint URLs, headers, tokens, raw provider responses, or exception details.

## Limits, retries, and recovery

`session-agent.model.max-model-calls-per-message` controls the maximum provider requests for one user message. Its environment form is `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`; the default is 12. The count is reserved immediately before each real provider request, including failed attempts. On the final allowed call, a direct answer completes normally; tool requests are not run and the runtime appends `MODEL_CALL_LIMIT_REACHED`.

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history.

Tool execution has no runtime deduplication layer. A read-only tool can run again if the process crashes after execution and before its observation batch commits. Any future side-effecting tool must provide idempotency at its adapter or external-service boundary.

## Model configuration

`src/main/resources/models.yml` is the single model configuration source imported by `application.yml` and bundled into the built image. It contains the default Google entry and an inactive OpenAI fragment. Model credentials are environment placeholders only; do not put a key in configuration committed to a repository.

To deploy OpenAI alongside the existing Google entry, edit `src/main/resources/models.yml` to enable the OpenAI fragment, provide both credentials through the deployment environment, and rebuild the image. The entry must have complete, explicit capacities. For example, the non-secret shape is:

```yaml
session-agent:
  model:
    default-model: openai-primary
    models:
      openai-primary:
        provider: openai
        model-id: gpt-5-mini
        api-key: ${OPENAI_API_KEY:}
        context-window-tokens: 400000
        max-output-tokens: 16384
        timeout: 30s
        base-url: https://api.openai.com/v1
```

For Docker Compose, set `OPENAI_API_KEY` in the untracked `docker/.env` (and retain `GOOGLE_API_KEY` while the Google entry is configured). The OpenAI adapter uses Chat Completions with `store=false`; it does not use the Responses API. A configured entry with a missing key, capacity, or default-key mapping fails at startup rather than selecting a different model. Enabling an entry in bundled `models.yml` requires an image rebuild; selecting among already configured entries through the session API does not require a restart and affects only subsequently accepted messages.

## Database and local runtime

The shipped database contract is a fresh schema applied through ordered Flyway migrations V1 through V4. Compatibility with removed pre-release schemas is not supported. Do not reset or delete the current database or its data as part of starting this version. The Compose service binds the runtime HTTP port to loopback only. Required local credentials are intentionally not committed.

Conversations and tool results are retained indefinitely. This POC adds no TTL, automatic cleanup, or backup/recovery workflow. Existing temporary continuation cleanup and Docker/host log rotation remain unchanged.

## Docker JSON logs

Runtime emits one JSON object per line to both `docker compose logs` and a rolling host file. The active file is always `session-agent-runtime.log`; archives are named `session-agent-runtime.YYYY-MM-DD.N.log.gz`. Runtime rolls at 100 MB, keeps seven days, and caps retained archives at 500 MB. Docker's independent `json-file` driver remains at 100 MB with five files.

Initialize the local deployment configuration and prepare the bind directory for the non-root runtime UID/GID (10001). The default is the repository `logs/` directory:

```bash
./docker/runtime.sh init
# To persist a dedicated absolute host path into a newly created docker/.env:
SESSION_AGENT_LOG_DIR=/srv/session-agent-runtime/logs ./docker/runtime.sh init
```

`init` never overwrites an existing `docker/.env`; edit that file to provide `SESSION_AGENT_POSTGRES_PASSWORD` and any optional model, Slack, MCP, port, or log settings. The helper may ask for `sudo` only while preparing the host log directory. It can be invoked from any working directory.

Manage the complete Compose project through the same helper:

```bash
./docker/runtime.sh start    # validate, build, and run detached
./docker/runtime.sh status
./docker/runtime.sh restart  # down, rebuild, and start
./docker/runtime.sh stop     # preserves the PostgreSQL named volume
```

Follow container output and the active host file independently:

```bash
./docker/runtime.sh logs
# Default bind path, from the repository root:
tail -F logs/session-agent-runtime.log
# When SESSION_AGENT_LOG_DIR is set in docker/.env:
tail -F /srv/session-agent-runtime/logs/session-agent-runtime.log
```

The helper does not contain a data-reset command, and `stop` never passes `--volumes`; PostgreSQL data remains in the project-scoped named volume. The host directory is intentionally Git-ignored and keeps a stable active filename so a future Filebeat or Elastic Agent input can tail it. This POC does not deploy an ELK stack or a shipper. JSON lifecycle events contain only stable event names and safe correlation/outcome fields; Runtime never emits conversation text, Slack/model credentials, tool arguments/results, provider continuation data, or raw exception details in its lifecycle events.

## Slack Socket Mode (optional)

Slack is disabled when `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`, and
`SLACK_BOT_USER_ID` are all unset. Configure all three together to enable Socket
Mode; a partial configuration prevents startup. Tokens belong in the deployment
environment or secret manager and must not be committed.

Configure the Slack app for Socket Mode and subscribe to `app_mention` plus
message events for public channels, private channels, MPIMs, and direct
messages. Grant the matching read scopes (`app_mentions:read`,
`channels:history`, `groups:history`, `mpim:history`, and `im:history`) and the
bot write scope needed for outbound replies. Socket Mode supplies inbound event
intake and envelope acknowledgement; accepted messages and outbound notices or
answers are persisted as database deliveries, which a separate worker polls and
sends through the Slack Web API. Runtime has no public Slack callback endpoint,
signing secret, OAuth flow, or Slack event polling loop.

An addressed top-level channel, private-channel, or MPIM message starts a
conversation after its bot mention is removed. A top-level DM needs no textual
mention. Slack connection failures leave Runtime, storage, and MCP startup
available; the Socket Mode client reconnects asynchronously.

Accepted events receive one durable queued confirmation (`已收到，正在排隊處理。`).
When the unfinished-job capacity is full, the source is durably recorded as busy
with (`目前工作已滿，這則訊息尚未受理，請稍後重新送出。`) and is not later
promoted into a job by a resend. These notices are delivery status, not proof of
job completion or a replacement for Socket Mode acknowledgement.

Terminal answers are persisted and delivered in parts of at most 4,000 UTF-16
code units; retries continue with unsent parts and do not rerun the model.
Splitting is presentation only: user text, model text, and raw database history
remain unchanged. The splitter prefers line breaks and safe character boundaries,
preserves whitespace inside code fences, may omit whitespace-only parts outside
fences, and adds synthetic closing or reopening fences as needed for presentation,
including closing an unclosed final fence. This is fence-aware presentation, not a
full Markdown or JSON parser, so parts are not guaranteed to be independently valid
Markdown or JSON.

## Verification

Default Runtime tests use fakes and require neither model quota nor a live MCP server:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

PostgreSQL integration tests verify the fresh schema, atomic ordered history batches, complete-history loading, and persistence contracts:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

Keep deployed live acceptance outside this repository. Runtime owns offline
fake-backed tests and focused model-adapter coverage.
