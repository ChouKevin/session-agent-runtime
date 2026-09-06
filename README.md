# Session Agent Runtime

Session Agent Runtime is a standalone, provider-neutral conversation service. It owns durable conversation history, conversation-job ordering, model calls, MCP tool execution, and PostgreSQL storage. Tool providers connect through named MCP streamable-HTTP endpoints; the runtime treats each tool definition and result as opaque provider data.

## Where changes belong

| Change | Location |
| --- | --- |
| Session, message, job, or history rules | `src/main/java/com/java/system/sessionagent/conversation` |
| Chat-model contract or provider integration | `src/main/java/com/java/system/sessionagent/model` |
| MCP discovery, routing, connection lifecycle, or diagnostics | `src/main/java/com/java/system/sessionagent/mcp` |
| Generic tool calls and observations | `src/main/java/com/java/system/sessionagent/tool` |
| PostgreSQL persistence or migrations | `src/main/java/com/java/system/sessionagent/storage` and `src/main/resources/db/migration` |
| Message, job, history, or actuator HTTP endpoints | `src/main/java/com/java/system/sessionagent/web` |

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
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same session. The accepted response contains `sessionId` and `messageJobId`. The job response contains its IDs, `status`, `retryCount`, and `modelCallCount`.

History records share `sequence`, `createdAt`, and `messageJobId` fields. The public event shapes are:

| Type | Type-specific fields |
| --- | --- |
| `USER` | `participantId`, `message` |
| `ASSISTANT_TOOL_CALLS` | optional `message`, `calls`: ordered `{toolCallId, toolName, arguments}` values |
| `TOOL` | `toolCallId`, `toolName`, opaque `output`; each observation is paired with a preceding `ASSISTANT_TOOL_CALLS` call ID |
| `ASSISTANT` | `message` |
| `RUNTIME` | `code`, `message` |

Tool inputs and outputs are opaque runtime data. There is no separate result-lookup endpoint.

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

Startup is safe with no configured connections or when every configured server is unavailable. Each connection reconnects and refreshes independently, so a failed provider does not hide tools from an available provider. `GET /actuator/mcpConnections` reports safe per-connection diagnostics: state (`DISABLED`, `CONNECTING`, `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, or `STOPPED`), discovered tool count, and when applicable a safe failure code/message. It never exposes endpoint URLs, headers, tokens, raw provider responses, or exception details.

## Limits, retries, and recovery

`session-agent.model.max-model-calls-per-message` controls the maximum provider requests for one user message. Its environment form is `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`; the default is 12. The count is reserved immediately before each real provider request, including failed attempts. On the final allowed call, a direct answer completes normally; tool requests are not run and the runtime appends `MODEL_CALL_LIMIT_REACHED`.

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history.

Tool execution has no runtime deduplication layer. A read-only tool can run again if the process crashes after execution and before its observation batch commits. Any future side-effecting tool must provide idempotency at its adapter or external-service boundary.

## Database and local runtime

The shipped schema is a fresh V1 schema. Reset a disposable PostgreSQL database before starting this version; do not reuse a database from an earlier schema. The Compose service binds the runtime HTTP port to loopback only. Required local credentials are intentionally not committed.

## Docker JSON logs

Runtime emits one JSON object per line to both `docker compose logs` and a rolling host file. The active file is always `session-agent-runtime.log`; archives are named `session-agent-runtime.YYYY-MM-DD.N.log.gz`. Runtime rolls at 100 MB, keeps seven days, and caps retained archives at 500 MB. Docker's independent `json-file` driver remains at 100 MB with five files.

Before `docker compose up`, create the bind directory and grant the non-root runtime UID/GID (10001) write access. The default is the repository `logs/` directory because `SESSION_AGENT_LOG_DIR` is resolved relative to `docker/compose.yaml`:

```bash
mkdir -p logs
chown 10001:10001 logs
# Or choose another prepared directory:
export SESSION_AGENT_LOG_DIR=/srv/session-agent-runtime/logs
```

Follow container output and the active host file independently:

```bash
docker compose -f docker/compose.yaml logs -f session-agent-runtime
tail -F "${SESSION_AGENT_LOG_DIR:-logs}/session-agent-runtime.log"
```

The host directory is intentionally Git-ignored and keeps a stable active filename so a future Filebeat or Elastic Agent input can tail it. This POC does not deploy an ELK stack or a shipper. JSON lifecycle events contain only stable event names and safe correlation/outcome fields; Runtime never emits conversation text, Slack/model credentials, tool arguments/results, provider continuation data, or raw exception details in its lifecycle events.

## Slack Socket Mode (optional)

Slack is disabled when `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`, and
`SLACK_BOT_USER_ID` are all unset. Configure all three together to enable Socket
Mode; a partial configuration prevents startup. Tokens belong in the deployment
environment or secret manager and must not be committed.

Configure the Slack app for Socket Mode and subscribe to `app_mention` plus
message events for public channels, private channels, MPIMs, and direct
messages. Grant the matching read scopes (`app_mentions:read`,
`channels:history`, `groups:history`, `mpim:history`, and `im:history`) and the
bot write scope needed for the later outbound-response integration. Runtime has
no public Slack callback endpoint, signing secret, OAuth flow, or polling loop.

An addressed top-level channel, private-channel, or MPIM message starts a
conversation after its bot mention is removed. A top-level DM needs no textual
mention. Slack connection failures leave Runtime, storage, and MCP startup
available; the Socket Mode client reconnects asynchronously.

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
