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
| Shared PostgreSQL intake capacity and UUID mapping | `src/main/java/com/java/system/sessionagent/storage/adapter/postgres/jdbc` |
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

One submitted user message starts one ordered job. A job can make one or more model calls. A model call either returns assistant text or requests tools. Within the ordinary allowance, the runtime executes admitted calls sequentially in the model-provided order, then atomically appends one `ASSISTANT_TOOL_CALLS` event followed by all paired `TOOL` observations in one batch; those observations are supplied to the next model call. Tool calls returned on the last allowed ordinary request are neither executed nor appended; the bounded handoff below takes precedence.

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

The general conversation prompt asks for question-focused answers that explain the
outcome and reasoning before supporting evidence. Commit SHAs, full source paths,
line citations, and tool procedures are omitted by default unless requested or
needed for understanding; relevant uncertainty and version limitations remain
explicit. This is presentation guidance only: exact tool identifiers, required
source/version verification, opaque MCP data, and complete audit history are unchanged.

### Durable Markdown reports and CSV exports

On normal jobs with a configured supported attachment route, `createMarkdownReport(title, content)`
and `createCsv(title, columns, rows)` save durable PostgreSQL artifacts. They do not upload files or
claim delivery; their result is `SAVED_PENDING_DELIVERY`. The first route adapter recognizes
accepted Slack jobs with an original persisted thread binding while Slack is configured.
HTTP jobs without that route do not expose the creation tools. Compaction, intent classification, and boundary wrap-up requests have no tools.

Markdown bytes are exact UTF-8, without parsing. CSV accepts string headers and rectangular string
rows, writes a UTF-8 BOM, CRLF, quotes every field, and doubles embedded quotes. Cells whose first
non-whitespace character is `=`, `+`, `-`, or `@`, or which begin with tab/CR/LF, receive an apostrophe
prefix. Results disclose this spreadsheet-safe transformation; it is not a universal guarantee for
all import behaviors. Original input remains in the tool-call audit.
Runtime storage and the bytes passed to the Slack upload adapter are distinct from
Slack-hosted downloads: Slack may omit a CSV's UTF-8 BOM when serving a download.
Record raw hashes and lengths at each boundary; equivalent parsed CSV is not proof
of byte-identical download preservation.

Limits are 1 MiB serialized bytes per generated file, 10 files / 5 MiB per job, 100 CSV columns,
10,000 CSV data rows, and 120 Unicode code points per title. Filenames are immutable UUIDs with
`.md` or `.csv`; titles never select paths. Stable invocation replay returns the same artifact;
changed input for that identity is rejected. Only artifacts tied to their exact successful
tool observation in the atomic assistant/tool batch can be released, and release requires an
explicit delivered-answer completion. Waiting questions and clarifications never release artifacts.
Paused-job artifacts stay private; a later report-only job creates its own artifacts from retained
evidence and can release only those owned by that job. Staged orphans and cancelled/failed jobs remain unpublishable.
Artifacts inherit conversation retention; this feature adds neither public UUID downloads nor
automatic deletion. Attachment upload/delivery is a separate adapter responsibility.


## Durable history and HTTP API

Submit a message, poll its job, then read the complete session history:

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
POST /internal/message-jobs/{messageJobId}/cancel
GET  /internal/sessions/{sessionId}
GET  /internal/sessions/{sessionId}/messages
PUT  /internal/sessions/{sessionId}/model
GET  /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same session. The accepted response contains `sessionId` and `messageJobId`.

Explicit skill selections beginning with `/skill:` are classified durably at intake,
including malformed names. Intake retains the exact USER audit text and never reads
skill files. The storage contract resolves a pending selection atomically to one
`SKILL` audit event (`uri`, `sha256`, and actual `content`) or a safe
`SKILL_UNAVAILABLE` runtime error. Source replay returns the original receipt;
resolution replay cannot replace committed skill bytes. Full and paged history are
complete audit APIs. Context consumers can read history and command dispositions
from one repeatable-read snapshot through `loadContextHistory`.
Workers resolve only their claimed command before any model response or summary,
then reload committed context. Accepted recovery reuses persisted bytes; a new
source message rereads the file. Unknown, malformed, or unreadable selections
finish safely without a model request. Pending/rejected commands and rejection
feedback remain auditable but are excluded from model and summary context.

Skills are disabled by default (`session-agent.skills.roots: []`), with no
`readSkill` tool exposed. Configure trusted local root directories explicitly:

```yaml
session-agent:
  skills:
    roots: [/opt/session-agent/skills]
    max-file-bytes: 65536
    max-skills: 128
```

Each root contains direct child directories such as `review/SKILL.md`, with YAML
frontmatter `name: review` and a nonempty `description`. Registry metadata is
startup-only; new names/descriptions need restart. Bodies and relative references
are read from current bounded UTF-8 files without following symlinks. Use
`/skill:review [question]` via HTTP or Slack; omitting the question asks only for
load acknowledgment. Slack removes one leading own-bot mention for explicit
selections in addressed roots, bound replies, and DMs; exact stop commands retain
priority. This is message syntax, not a Slack slash-command registration.
The application owns the library lifecycle and exposes one composite catalog
containing local skills and independently managed MCP tools.

Example `/opt/session-agent/skills/review/SKILL.md` (the directory and name must
match; use lowercase letters/digits separated by hyphens, at most 64 characters):

```markdown
---
name: review
description: Review a proposed change for correctness and maintainability.
---
Check the requested behavior and identify concrete risks.
Read skill://review/references/checklist.md when the task needs the checklist.
```

Descriptions are limited to 1024 characters. On normal model requests, startup
metadata appears only in the `readSkill` description; bodies enter history only
when read. Summarization has no tools. Automatic reads retain the model's actual
call IDs and ordinary TOOL success/failure observations; explicit selections
produce USER + SKILL, not fabricated tool calls. Both kinds of loaded content
follow ordinary compaction: original instructions may leave model context, and
are not pinned or automatically restored. Use a **new source message**
`/skill:review question` to force a current-body reread after editing a file.
The SHA-256 proves the bytes read, not an immutable version.

Slack skill-load notices are transient operational state, not conversation events.
Both explicit selections and automatic `readSkill` calls become “loaded” only when
their actual SKILL/TOOL bytes commit. Definition and relative-reference reads are
identified separately; failed or interrupted reads never claim successful loading.
A fast read may skip the loading presentation, but its committed result remains
pending until that exact Slack presentation is acknowledged. Subsequent ordinary
status replaces it, without a permanent badge. A paused investigation displays a
waiting-for-choice status, not a claim that a report was delivered.
“Loaded” proves committed skill bytes, not model compliance or report correctness.
Skills are prompt guidance, not validators: independently check provenance, coverage
and field mapping before accepting a generated report.

Roots are shared trusted operator configuration: every Runtime user can read their
contents. Grant the Runtime service account read/traverse access only; keep roots
operator-owned, and use read-only bind mounts (`:ro`) for container deployments.
Symlinks (including root ancestors) are rejected. Do not put secrets in these roots,
mount a home directory, or point roots at `.agents/skills` maintenance/deployment
guides. No shell/scripts are executed and no OMP, home, or repository skills are
discovered automatically.

**Java MQ inventory skill.**

The shipped business skill is
[`runtime-skills/inventory-java-mq/SKILL.md`](runtime-skills/inventory-java-mq/SKILL.md).
It is not enabled by the checkout or by OMP Gateway. After approval, copy the
`inventory-java-mq` directory (not a symlink) into an operator-owned Runtime root,
for example `/opt/session-agent/skills/inventory-java-mq/SKILL.md`, and configure
that root as above. Keep the Runtime account read/traverse-only; never mount
`.agents/skills` as the business library. Restart after adding its discovery metadata.

Use `/skill:inventory-java-mq List Java MQ consumers and settings as CSV` over HTTP,
or `@bot /skill:inventory-java-mq ...` in Slack. A skill-only selection
`/skill:inventory-java-mq` loads and asks the model for acknowledgment; automatic
selection instead requires an actual model `readSkill` call for
`skill://inventory-java-mq`. Verify those paths separately.

The inventory classifies Java/MQ candidates before deep reads, defaults to
common/static settings, and records per-repository source pins and precise evidence
ranges. Environment comparisons require an explicit request. Unknown external
deployment values do not imply that static coverage is incomplete, and static
configuration does not prove deployment. Incomplete requested coverage results in
a progress/gap choice, not an unsolicited partial CSV; delivery of existing evidence
requires the user's approval. See the deployment
[configuration](.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用)
and [verification](.agents/skills/deploying-with-omp-gateway/references/verification.md#java-mq-業務-skill-驗收)
references for installation and acceptance boundaries.

The job response contains its IDs, `status`, `retryCount`, and `modelCallCount`, plus
`phase`, `phaseStartedAt`, `currentToolName`, `failureCode`, `failureMessage`, and
`cancellationRequestedAt`. `GET /internal/sessions/{sessionId}` exposes the same
fields in `currentJob`. Absent optional values are explicit JSON `null`.
`modelCallCount` is the total provider-request ordinal, including ordinary execution,
compaction, intent classification, and wrap-up. The separately persisted ordinary allowance
is operational state, not model history or prompt metadata; total ordinals remain artifact identities.


| Phase | Meaning |
| --- | --- |
| `PREPARING` | Claimed work is preparing the next turn or continuing after a committed batch. |
| `MODEL_CALL` | A counted model request is in progress, including compaction, intent, and wrap-up. |
| `TOOL_EXECUTION` | The tool identified by `currentToolName` is running. |
| `HISTORY_COMMIT` | Runtime is committing history or compaction; this does not expose uncommitted events. |

`phaseStartedAt` is the start time of the current phase, not a heartbeat or progress
percentage. `currentToolName` is populated only during `TOOL_EXECUTION`. Phases are
durable snapshots, not an event stream; polling can miss short transitions.
Assistant tool calls and all matching tool observations still become visible as
one atomic history batch.

`DONE` means processing has ended, not necessarily that it succeeded. Inspect
`failureCode` and the final history event to distinguish outcomes. Failure messages
are fixed safe text, never raw provider exceptions. Codes are
`MODEL_CALL_LIMIT_REACHED`, `MODEL_OUTPUT_INVALID`, `MODEL_UNAVAILABLE`,
`CONTEXT_TOO_LARGE`, `COMPACTION_FAILED`, `INVALID_CONVERSATION_HISTORY`,
`STORAGE_UNAVAILABLE`, `SKILL_UNAVAILABLE`, and `PROCESSING_INTERRUPTED`.
An ordinary tool error remains that tool's observation and does not, by itself,
mark the entire job as failed.

Retry and completion clear in-flight phase/tool fields. A fresh claim or model
reservation clears the previous failure; successful completion does too. When a
worker lease expires, polling hides stale progress and reports
`PROCESSING_INTERRUPTED` while retaining `WORKING` until recovery claims the job.
Reclaim initializes `PREPARING`; the old claim cannot overwrite its progress or
history. Process restarts do not require an in-memory progress cache.

`POST /internal/message-jobs/{messageJobId}/cancel` requires no request body.
It returns `200` with the current job snapshot for a known job, `404` for an
unknown job, or `400` for a malformed UUID. Repeating the request is idempotent:

- `PENDING` and `RETRY` jobs become `CANCELLED` without starting another model request.
- A `WORKING` job retains its current phase and records `cancellationRequestedAt`.
  It stops at a safe point after its already-admitted model/tool request finishes
  or times out. A cancellation request is not yet a stopped-job acknowledgement.
- In a tool batch, obtained observations remain unchanged. Unstarted calls receive
  explicit Runtime observations with `isError: true` and
  `result: {code: "TOOL_CANCELLED", message: ...}`; they were **not invoked**.
  The original assistant tool calls, all matching observations, and terminal
  `RUNTIME` event `JOB_CANCELLED` commit atomically. No next model turn is started.
  If a storage failure had already stopped the batch before cancellation became
  observable, terminal recovery instead marks those unstarted calls
  `TOOL_NOT_EXECUTED` with the storage-failure reason. A cancellation that wins
  during that append does not rewrite the earlier stop reason or any tool result.
- `DONE` and `CANCELLED` jobs are immutable under this endpoint. If normal completion
  commits before cancellation, the response remains `DONE`; if cancellation wins,
  the terminal state is `CANCELLED`, with no failure code or in-flight progress.

The request timestamp survives process restart and lease reclamation. Recovery of
a requested job does not replay model/tool work. Cancellation does not forcibly
abort a remote OMP/MCP operation, refund reserved model calls, or roll back tool
side effects. Results not committed before a process crash or an uncertain
database commit cannot be promised as recovered observations; Runtime does not
invent results or blindly replay a possibly committed batch.

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

For MCP observations, `output` has the generic `{isError, result}` wrapper;
`result` retains native MCP `content` blocks and, when present, `structuredContent`.
Both channels reach stored history and subsequent model turns: human-readable
content may contain evidence or presentation details absent from structured data.
Content type discriminators are preserved; the MCP result-envelope `_meta` is not
forwarded to the model. Runtime does not parse tool-specific source text or impose
a citation format. Previously committed observations remain unchanged; operational
Runtime failures retain their safe `{code, message}` result.

## Queue and intake

The default worker configuration is `session-agent.worker.concurrency: 4`. The database queue capacity is `session-agent.worker.max-unfinished-jobs: 100`, counting all `PENDING`, `WORKING`, and `RETRY` jobs together. Terminal jobs release capacity. The database is the waiting queue: jobs in the same session run FIFO, while different sessions can run in parallel.

When this unfinished-job capacity is full, a new HTTP message is rejected with `429` and `{"code":"queue_full"}`. A successful resend of an already accepted message returns its original `202` receipt instead of creating another job.

HTTP and Slack intake share one PostgreSQL admission component, configured once
at bootstrap. It owns the capacity limit, transaction-scoped advisory lock, and
unfinished-job query. Both paths resolve duplicates before checking capacity for
new jobs, so accepted resends still resolve to their original job when full.

## Generic MCP connections

Configure zero or more named connections under `session-agent.mcp.connections`. A connection name is portable (`[A-Za-z][A-Za-z0-9-]{0,31}`); discovered tools are exposed as `{connectionName}_{rawToolName}`. The URL is the exact absolute streamable-HTTP endpoint, including any non-default path.

For the host deployment, follow the [MCP setup and acceptance runbook](.agents/skills/deploying-with-omp-gateway/references/mcp.md). It includes two independent read-only `java-code-intelligence` readers, private systemd credentials, disabling/rotation, and model-to-tool history verification. Gateway routing does not import OMP MCP configuration or skills. Discovery and direct MCP success do not by themselves prove model-generated tool arguments are compatible.

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

`session-agent.model.max-model-calls-per-message` bounds ordinary execution and context-compaction requests for one user message. Its environment form is `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`; the default remains 12. Failed ordinary attempts and retries consume this allowance. Every actual provider request is reserved before sending and also increments the existing total `modelCallCount`; intent and wrap-up have separate one-shot reservations, not additional investigation allowance.

A direct text answer on the last allowed ordinary request completes normally. A tool response
on that request executes **zero** tools and appends neither the assistant calls nor fabricated
tool observations. Exhaustion enters durable `WRAP_UP`: at most one no-tools request briefly
describes confirmed progress and gaps and asks whether to continue investigating or organize
the evidence. The transient directive contains no counts and is not committed to history.
The question and durable session waiting generation commit atomically with job completion.
Waiting is not a running job. A failed, invalid, tool-bearing, or oversized wrap response uses
a safe deterministic waiting notice; recovery after a reserved but lost request never requeries it.
Cancellation takes precedence and cannot publish a waiting prompt or release artifacts.

When the next ordered job executes against a pending choice, one no-tools semantic intent
request classifies that job's exact initiating user message, even if later users are already
queued in history. `CONTINUE` consumes the observed waiting generation and opens a fresh
ordinary allowance. `DELIVER` consumes it and persists report-only mode: only trusted local
report-generation bindings are advertised and invokable, never MCP or skill reads, and
unknown/uncovered areas must be disclosed. `NEW_QUESTION` consumes it and starts a normal
task without automatically resuming the old investigation. `CLARIFY` publishes its question
without tools and preserves the source generation. Invalid/failed/lost intent results fail
closed with a clarification notice, not permission. Decisions persist before execution.
An accepted explicit `/skill:name` starts a new task and clears waiting atomically with its
committed skill resolution; a rejected command preserves waiting.

Report-only work uses the same bounded ordinary loop and may create a report, observe its
receipt, then answer; exhaustion still hands off without releasing a partial report.
Control JSON (`intent` and optional CLARIFY-only `clarification`) is internal and strictly
validated. Ordinary final answers, including JSON-looking text, remain unparsed plain text.

V11 backfills ordinary usage from existing total calls without inferring historical waiting
state. Drain old workers before migration: mixed old/new workers are unsupported.

For authorized Luna acceptance, use an isolated Runtime preconfigured with
`openai-codex/gpt-6-luna`, medium reasoning, context window 272000 and output limit
8192 before any model-producing message, including a valid skill-only selection.
Do not change the production Sol default. A dedicated smaller ordinary allowance
can exercise handoff without changing production's 12. Never start a second Socket
Mode consumer for the same Slack app; a harness may disable only its Socket
runtime factory while retaining real intake, workers, storage, MCP and Web API.
Label synthetic ingress explicitly: real API acknowledgements are not evidence
of human Socket intake or browser-visible status. See the operator
[verification guide](.agents/skills/deploying-with-omp-gateway/references/verification.md).

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history. With a direct provider connection, each reservation corresponds to a provider request; when a gateway retries internally, it does not bound the gateway's underlying provider attempts.

Tool execution has no runtime deduplication layer. A read-only tool can run again if the process crashes after execution and before its observation batch commits. Any future side-effecting tool must provide idempotency at its adapter or external-service boundary.

## Model configuration

The preferred deployment uses **native OMP Auth Gateway** (`omp auth-gateway`),
a host Java 21 Runtime, and Docker PostgreSQL. It does not use `omp-priority` or
a custom forwarding service. This is **model-request forwarding**, not delegation
to the OMP agent: Java Runtime still owns the agent loop, sessions, history, and
MCP tool execution. Start with the repository skill
[`deploying-with-omp-gateway`](.agents/skills/deploying-with-omp-gateway/SKILL.md).
It separates installation, model/Slack/MCP configuration, updates/rollback, and
verification, and includes portable, non-secret templates. The example model is
`openai-codex/gpt-6.1-sol` with `reasoning-effort: medium`; verify availability and
capacity against your gateway catalog before deployment.

The build pins Spring AI **2.1.0-RC1**, a release candidate/pre-release rather than
a stable release. The upgrade retains the existing Google and OpenAI adapters;
availability of Responses API support in the dependency does not enable it in this
runtime or add native Codex OAuth login/refresh.

Spring AI's ordered message parts are captured as opaque, route-bound continuation
state during a tool turn. This preserves reasoning, per-part Google thought
signatures, and OpenAI-compatible extra tool-call properties on replay, including
assistant text alongside tool calls. Provider response metadata is not the replay
contract. The continuation remains private to the model adapter and is removed
when the job finishes; permanent provider-neutral history is unchanged. Runtime
still owns sequential tool execution and atomic history commits, not Spring AI
memory or an advisor-managed tool loop.

Before replacing an M1 runtime with RC1, drain in-flight/retry jobs through
completion. RC1 does not replay the old Google metadata-only continuation format;
already completed conversation history needs no migration. The local fake-HTTP
smoke verifies Runtime sends explicit `strict: false`, keeps optional tool-schema
fields optional, commits/replays the tool observation, and stores the final
response. It does not verify a gateway's downstream translation; the gateway
compatibility requirement below still applies.

`src/main/resources/models.yml` is the bundled model configuration imported by the default `application.yml`. It contains a Google entry and an inactive OpenAI fragment for direct-provider/container deployments. The OMP deployment skill instead selects a complete external `application.yml` with `SPRING_CONFIG_LOCATION`, importing its own `models.yml` without retaining the unused Google entry. Model credentials are environment placeholders only; do not put a key in configuration committed to a repository.

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

Codex OAuth is not a native Runtime authentication mode. The `openai` adapter can
use an external OpenAI-compatible gateway through `base-url`; in that deployment,
`api-key` is the gateway's bearer credential, while the gateway owns Codex OAuth
login, refresh, and provider-protocol conversion. An OAuth access token is not a
drop-in replacement for an OpenAI API key. Keep these provider details outside
the conversation and MCP contracts.

The currently pinned host-local OMP gateway does not run the OMP agent loop or add
its agent prompt, skills, or conversation history. It translates the caller's
messages/tools into the Codex protocol, manages OAuth and transport, and can apply
protocol/history normalization and internal retries. It is not a byte-for-byte
relay. Runtime's `modelCallCount` counts requests to that gateway, not all
underlying Codex attempts.

**Gateway compatibility requirement:** unpatched OMP 18.12.6 drops tool
`strict: false` while translating Chat Completions into the provider protocol.
The deployed workaround is a native OMP source build with that conversion fixed,
not a different agent or proxy implementation. Explicit `false`/`true` must survive,
and omitted Chat Completions strictness must retain its non-strict default.
Use a verified fixed build or an upstream release containing the fix; do not replace
it with an unpatched binary merely because both report the same OMP version.
Record the source revision/patch and executable checksum, and rerun the
[MCP model-to-tool acceptance](.agents/skills/deploying-with-omp-gateway/references/mcp.md)
after changing the Gateway. A local patched build is not an official upstream release.

For this pinned Codex route, the gateway does not forward the OpenAI
`max_tokens`/`max_completion_tokens`, temperature, or top-p settings. Configuring
Runtime `max-output-tokens` is therefore not a guaranteed provider-side output
cap on this route. Recheck these boundaries when upgrading or replacing OMP;
this feature does not change gateway behavior.

The OMP deployment skill installs host-local configuration and versioned artifacts
outside the repository, under `$HOME/.local/share/session-agent-runtime` by
default, with `session-agent-runtime`, `session-agent-omp-broker`, and
`session-agent-omp-gateway` systemd user services. Existing installations must
retain their actual service paths, environment files, Compose project, and
database volume; use the update instructions rather than overwrite them with
fresh-install templates. Do not start this host deployment and the Docker Runtime
below on the same ports. OMP remains an external dependency and is not upgraded
as part of a Runtime update.

## Database and local runtime

The shipped database contract is a fresh schema applied through ordered Flyway migrations V1 through V9. V5 adds durable job progress and safe failure codes; historical terminal failures are backfilled only from an allowlisted final runtime event, not an earlier recoverable failure. Existing working rows may initially have no phase until reclaimed. V6 adds durable cancellation requests and the `CANCELLED` terminal state without rewriting existing history or progress. V7 adds durable Slack stop-command receipts and `STOP` delivery notices. V8 adds skill commands; V9 adds Slack-owned per-question progress and workspace cooldown persistence. Compatibility with removed pre-release schemas is not supported. Do not reset or delete the current database or its data as part of starting this version. The Compose service binds the runtime HTTP port to loopback only. Required local credentials are intentionally not committed.

Conversations and tool results are retained indefinitely. This POC adds no TTL or automatic cleanup. The OMP deployment skill documents operator-run database backup and schema-aware rollback; it does not add automatic backup scheduling or guarantee lossless restoration. Existing temporary continuation cleanup and Docker/host log retention remain unchanged.

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
bot `chat:write` scope needed for outbound replies and updates to its own notices. Socket Mode supplies inbound event
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

After Slack confirms the queued notice timestamp, Runtime updates that same bot-owned
message with per-question status and a last-confirmed UTC observation time. Two
questions in one thread retain independent notices; the status is not the thread's
latest job. The original-question link comes from
[`chat.getPermalink`](https://docs.slack.dev/reference/methods/chat.getPermalink/),
is validated against the original channel/message, and is cached durably, including
thread query parameters. No additional scope is required for that lookup. A
separate claim performs the lookup before a later claim updates the notice, so one
claim never spends two Slack call windows.
Canonical root links may include `thread_ts` equal to that root's message timestamp;
reply links resolve to the queried thread root. Both forms retain strict Slack-host,
path, timestamp and matching-channel validation; question links must still identify
the exact source message.

Receipt means queued, not running. Active preparation, model waiting and tool
lookup require a current processing lease. Status also distinguishes retry,
stopping, interrupted ownership, preparing answer delivery, completed, cancelled,
job failure and answer-delivery failure. `DONE` alone is not delivery success:
completed appears only after the full item set is prepared and the final text and
every required attachment are confirmed `SENT`. A sent text item while a file is
pending remains `DELIVERING`; a failed or `UNCONFIRMED` upload yields
`DELIVERY_FAILED`. Existing cancellation/processing-failure precedence is unchanged,
and recovery does not reopen historical fully delivered questions. The final
answer remains separate from the mutable notice.

`session-agent.slack.progress` defaults to `poll-delay: 2s`,
`refresh-interval: 30s` for unchanged nonfinal states, and
`workspace-interval: 2s` between progress API attempts per workspace across replicas.
Changed states are eligible sooner; polling coalesces intermediate phases.
Backlog, outages and Slack `Retry-After` can delay updates beyond these best-effort
intervals. Progress uses the existing delivery lease, retry/backoff and attempt
limits, but has its own scheduler: slow or failing progress calls do not block
answer posting, retry the model, or add conversation/history events. Permanent
lookup/update errors (for example a deleted notice or permission loss) stop that
target; they do not post a replacement notice.

The timestamp is the last database observation, not proof of current model activity
or an external heartbeat. An entire Runtime outage leaves the old static timestamp
visible until recovery. Source replay and restart reuse the confirmed notice
timestamp and cached question link. An unknown initial post outcome does not
authorize another progress post. Slack
[`chat.update`](https://docs.slack.dev/reference/methods/chat.update/) can update only
the bot's own messages and has no remote compare-and-swap: claim/version fencing
rejects stale local acknowledgments, but a delayed accepted request can briefly
overwrite newer remote text. A terminal reconciliation pass waits at least the
delivery lease window (default 30s, longer than the 25s call timeout) before
retirement; this is not a guarantee of exactly-once posting or monotonic remote
visibility.

The Slack intake transaction commits an accepted job together with its receipts,
immutable thread binding, and queued notice, or commits BUSY receipts and a busy
notice without creating a session or job. Its nonthrowing capacity check happens
before nested conversation intake, so a full queue does not mark the BUSY
transaction rollback-only. A BUSY resend stays BUSY even after capacity releases;
delivery retries remain independent of admission and model processing.

Any human in a bound conversation thread can send `@bot stop` or `@bot 停止工作`.
The bot mention is required, including in DMs; removing the addressing mention
and trimming whitespace must leave exactly lowercase `stop` or `停止工作`.
Unmentioned text, uppercase `STOP`, extra words, edits, attachments, hidden
messages, and bot/system noise do not become stop commands.

Runtime handles this command directly, without creating a user message or job,
calling the model, or waiting in the work queue, even at full capacity. Acceptance
atomically snapshots and requests cancellation of every `PENDING`, `RETRY`, or
`WORKING` job in the thread's immutable session binding, saves event/logical-message
receipts, and queues one durable `STOP` notice in the original thread. Other
sessions and submissions accepted afterward are unaffected. Event replays and
overlapping `app_mention`/`message` events for the same Slack message neither repeat
cancellation nor duplicate the notice, even after later work has been submitted.
The transaction takes the shared intake lock, then locks all snapshot jobs in
user-message order before allocating cancellation history sequences; workers and
the HTTP cancellation endpoint retain their job-before-session lock order.

The receipt reports `已收到停止請求，共 N 項工作` and cautions that an in-flight call
may need to finish or time out; it does not claim that running work has already
stopped. Cancellation uses the same cooperative safe points as HTTP cancellation:
already-executed operations are not rolled back, and each job's normal terminal
cancellation result is still delivered separately. A bound thread with no
nonterminal jobs, or an unbound thread, receives `此對話目前沒有可停止的工作`.
A top-level command instead asks the sender to use the target thread; neither
top-level nor unbound commands create a session or job. Commands use ordinary
messages only, with no buttons, shortcuts, or slash commands. `STOP` notices use
the existing durable delivery/retry mechanism and are queryable by Slack source.

Ordinary assistant answers are one native Slack `markdown` block with a fixed
accessible top-level notification. Markdown is never parsed, split, repaired, or
truncated. Answers at or below both 10,000 Unicode code points and 10,000 UTF-16
units stay inline; exceeding either bound attaches the exact UTF-8 answer as
`answer.md` with a fixed explanatory notice and no extra model call. Runtime
notices (including queue, cancellation, and terminal runtime errors) stay plain text.
Unsupported native Markdown or attachments fail delivery visibly; Runtime does not
silently fall back to plain text, strip tables/fences, truncate, or generate a new
summary. Native Slack support (including workspace/platform feature availability)
must be checked separately from preserving the original Markdown value.


Delivery snapshots every required text/file item and all released tool artifacts
in PostgreSQL before acknowledging any item. The parent cannot become `SENT`
until every required item is confirmed. Auto-created `answer.md` has a deterministic
job identity separate from tool artifacts. Attachment failure never regenerates
the answer or reruns the model.
V10 retains already-persisted V9 delivery parts as a complete prepared set of
plain items, preserving their identities and sent acknowledgements. Such in-flight
deliveries resume only their unsent items; they are not regenerated or re-split
into the new whole-answer presentation.

The upload protocol persists `PREPARED` → `ALLOCATED` → `UPLOADED` → `FINALIZING`
→ `SENT`, releasing ownership between bounded operations. Known safe rejections
honor `Retry-After` only with positive documented rate-limit rejection; a timing
header on an ambiguous completion error does not authorize replay.
Permission/type/expired-capability failures terminate delivery.
Finalization intent is committed before the one-time Slack sharing call. A lost
response, failed acknowledgement commit, or expired owner around finalization
becomes `UNCONFIRMED` (parent `FAILED`), never a blind second finalize or new file.
This is not an exactly-once Slack guarantee; operator reconciliation may be needed.
The existing initial-message-post ambiguity is unchanged.

File sharing requires the installed app's `files:write` permission. Scope changes
and any required app reinstall/reauthorization need explicit operator approval;
upgrading Runtime does not grant permission or authorize changing the app/token.
Runtime does not request `files:read` or automatically reconcile uncertain files.
Upload capabilities are sensitive, persisted only for staged transport, accepted
only at HTTPS `files.slack.com/upload/`, and excluded from diagnostic rendering.
The byte-upload client has no bot credentials and never follows redirects.
Generated files, accepted long-answer bytes, upload evidence/capabilities, and
conversation history inherit database access, backup security, and retention;
there is no automatic artifact expiration/deletion or public UUID download URL.
Do not reset `FINALIZING`/`UNCONFIRMED` to retry or resubmit the question to repair
delivery: preserve evidence for operator reconciliation, without assuming Slack did
or did not share the file. Native Markdown rendering and actual workspace file
permissions still require an explicitly authorized live Slack check.

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
