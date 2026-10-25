# Current package architecture

This guide describes the production packages that exist in the repository today.
The source root is `src/main/java/com/java/system/sessionagent/`. The eleven
top-level packages below are the closed Spring Modulith modules. Subpackages
express the role of the code that is present now; a module does not gain an
empty layer merely to complete a pattern.

## Modules and boundaries

| Module and current packages | Responsibility and boundary |
| --- | --- |
| `conversation` — `domain`, `application`, `port/in`, `port/out` | Owns session, message, job, history, context, and model/tool loop rules. `domain` holds provider-neutral state and invariants; `application` coordinates intake, queries, and message-job processing; `port/in` holds contracts called by adapters and workers; `port/out` holds model, storage, and telemetry contracts implemented outside the module. |
| `tool` — `domain`, `port` | Defines generic tool names, definitions, snapshots, calls, executors, and outputs. Tool names, schemas, arguments, and results remain provider data; this module does not know an MCP or model SDK. |
| `skill` — `domain`, `port`, `configuration`, `adapter/filesystem`, `adapter/tool` | Owns startup-only skill metadata, bounded current-file reads, and the provider-neutral `readSkill` tool. Bootstrap owns the library lifecycle and composes skill and MCP leaves into the sole catalog. |
| `artifact` — `application`, `port` | Owns bounded first-party Markdown/CSV encoding, route-gated creation tools, and durable artifact storage contracts; no provider SDK or conversation dependency. |
| `model` — `adapter/springai` | Maps the provider-neutral conversation model contract to configured Spring AI OpenAI and Google GenAI adapters, including prompts, history projection, tool callbacks, usage, and continuation data. Provider types stay inside this adapter. |
| `mcp` — `domain`, `configuration`, `adapter/sdk`, `adapter/actuator` | Owns named MCP connection state and properties, SDK connection lifecycle, discovery, tool routing/adaptation, refresh/reconnect, and safe actuator diagnostics. Raw MCP metadata, schemas, and results do not become Runtime DTOs. |
| `storage` — `adapter/postgres`, `adapter/postgres/jdbc` | Implements conversation persistence with PostgreSQL, Flyway-backed tables, MyBatis mappers, ordered history, job claims, and atomic history writes. The narrow JDBC export owns shared intake admission and UUID mapping for PostgreSQL adapters. |
| `web` — `adapter/http` | Adapts HTTP requests and responses for message intake, job polling, session history, validation, and error handling. MCP diagnostics are owned by `mcp/adapter/actuator`. |
| `worker` — `application`, `configuration` | Polls queued message jobs, claims and renews ownership, invokes processing, reports completion/retry/ownership-loss/recovery outcomes, and binds worker properties. |
| `slack` — `domain`, `application`, `port/out`, `adapter/sdk`, `adapter/postgres`, `configuration` | Handles Slack intake, filtering, delivery lifecycle, Socket Mode, Slack SDK calls, and its own idempotency, thread binding, delivery persistence, and properties. Provider details stay inside the owning adapter. |
| `bootstrap` — `configuration`, `adapter/metrics` | Composes the application through Spring configuration and supplies the Micrometer telemetry adapter. Bootstrap has no exported named interfaces; composition classes are internal to startup wiring. |

The module `package-info.java` files are the source of truth for the closed
dependency allowlist and the `NamedInterface` annotations are the source of
truth for exports. `module :: named-interface` means that the target module
exposes a named interface such as `conversation :: port.out`; a bare module name
such as `web` means the target module's unnamed/root interface. An allowed
dependency is a ceiling, not evidence that code currently uses it.

## Named interfaces and dependency ceiling

The complete named-interface map is intentionally kept in one assertion in
`ApplicationModulesTest`. These are the only named exports:

| Module | Named interfaces |
| --- | --- |
| `tool` | `domain`; `port` |
| `skill` | `domain`; `port`; `configuration`; `adapter.filesystem`; `adapter.tool` |
| `artifact` | `application`; `port` |
| `conversation` | `domain`; `application`; `port.in`; `port.out` |
| `model` | `adapter.springai` |
| `mcp` | `configuration`; `adapter.sdk`; `adapter.actuator` |
| `storage` | `adapter.postgres`; `adapter.postgres.jdbc` |
| `web` | none |
| `worker` | `application`; `configuration` |
| `slack` | `domain`; `application`; `port.out`; `adapter.sdk`; `adapter.postgres`; `configuration` |
| `bootstrap` | none |

The exact allowed dependency map and the currently used direct production
dependencies are shown together below. Same-module imports are omitted. The
unused allowances are deliberate: `web` may use `tool :: domain`,
`skill` may use `tool :: domain`, and `bootstrap` may use the bare `web` module.
Every other allowance is currently used by production code.

| Module | Allowed dependencies | Current direct production dependencies |
| --- | --- | --- |
| `tool` | none | none |
| `skill` | `tool :: domain`; `tool :: port` | `tool :: port` |
| `artifact` | `tool :: domain`; `tool :: port` | `tool :: domain`; `tool :: port` |
| `conversation` | `tool :: domain`; `tool :: port`; `skill :: domain`; `skill :: port` | `tool :: domain`; `tool :: port`; `skill :: domain`; `skill :: port` |
| `model` | `conversation :: domain`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain` | `conversation :: domain`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain` |
| `mcp` | `tool :: port` | `tool :: port` |
| `storage` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain`; `artifact :: port` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain`; `artifact :: port` |
| `web` | `conversation :: domain`; `conversation :: port.in`; `tool :: domain`; `slack :: domain`; `slack :: port.out`; `skill :: domain` | `conversation :: domain`; `conversation :: port.in`; `slack :: domain`; `slack :: port.out`; `skill :: domain` |
| `worker` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out` |
| `slack` | `conversation :: domain`; `conversation :: port.in`; `storage :: adapter.postgres.jdbc`; `artifact :: port` | `conversation :: domain`; `conversation :: port.in`; `storage :: adapter.postgres.jdbc`; `artifact :: port` |
| `bootstrap` | `tool :: port`; `artifact :: application`; `artifact :: port`; `skill :: port`; `skill :: configuration`; `skill :: adapter.filesystem`; `skill :: adapter.tool`; `mcp :: configuration`; `mcp :: adapter.sdk`; `mcp :: adapter.actuator`; `conversation :: application`; `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `model :: adapter.springai`; `storage :: adapter.postgres`; `storage :: adapter.postgres.jdbc`; `web`; `worker :: application`; `worker :: configuration`; `slack :: domain`; `slack :: application`; `slack :: port.out`; `slack :: adapter.sdk`; `slack :: adapter.postgres`; `slack :: configuration` | `tool :: port`; `artifact :: application`; `artifact :: port`; `skill :: port`; `skill :: configuration`; `skill :: adapter.filesystem`; `skill :: adapter.tool`; `mcp :: configuration`; `mcp :: adapter.sdk`; `mcp :: adapter.actuator`; `conversation :: application`; `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `model :: adapter.springai`; `storage :: adapter.postgres`; `storage :: adapter.postgres.jdbc`; `worker :: application`; `worker :: configuration`; `slack :: domain`; `slack :: application`; `slack :: port.out`; `slack :: adapter.sdk`; `slack :: adapter.postgres`; `slack :: configuration` |

`JobProcessor` resolves only the claim's PENDING selection before responses or
summaries and reloads its committed context. ACCEPTED selections reuse stored
bytes. Resolution shares the ordinary append uncertainty tracker: an ambiguous
write stops the attempt without resubmission or a replacement failure batch.

Tool executors receive a trusted `ToolInvocation`: the four-field stable
`ToolExecutionContext` plus separate worker/claim-number lease authority. Only Runtime
constructs production invocations, after counting the actual model request. Catalog
snapshots may receive job/session identifiers for route eligibility without depending
on conversation types. MCP ignores invocation metadata; first-party skill reads use
its fenced claim and counted request identity to start durable operational attempts.
Artifact and skill receipts are internal tool-port metadata, not model-visible JSON.
PostgreSQL compares the successful artifact output, original arguments, invocation
ordinal, tool identity and job ownership while associating the actual TOOL event.
Release requires an ANSWER disposition and belongs only to that completed job;
WAITING/clarification never release artifacts or transfer prior-job ownership.
Encoding, file bounds, and release semantics are specified in the
[artifact contract](#durable-markdown-reports-and-csv-exports).
Creation has no Slack side effect and no model-supplied channel/path/capability.

`JobHandoffCoordinator` owns the bounded control protocol, not a second tool loop.
`JobControl` separates persisted execution mode/stage, exact initiating user and
waiting generation from committed conversation history. Semantic decisions persist
under the fenced claim before changing tools. `ToolSnapshot.restrictedTo` transfers
single-owner cleanup; capability metadata is local composition authority, never
inferred from MCP names or schemas. The [handoff contract](#limits-retries-and-recovery)
defines allowance, waiting, and recovery behavior.

`SkillLoadObserver` and `SkillReadReceipt` belong to the neutral tool port.
Filesystem results remain in the skill adapter; storage finalizes attempts in the
same transaction as their SKILL/TOOL history. Slack maps resource kinds into its own
presentation type, avoiding a skill/tool module cycle or Slack receipt dependency.
Pending outcomes are ordered ahead of generic progress and acknowledged by exact
presentation/revision, not merely by job. A reference success is not a definition
reload, failed/interrupted reads never become loaded, and an acknowledged outcome
disappears on subsequent ordinary progress. WAITING_USER is terminal for that job's
status, not a delivered report. None of these operational records become history.
Loaded notices certify committed content only. Runtime neither validates compliance
with prompt skills nor guarantees business-report provenance, completeness or field
accuracy; model-output quality requires separate evidence review.


Slack presents final assistant text as a whole native Markdown block (fixed
accessible notification) or exact UTF-8 `answer.md` above the conservative
10,000-code-point/UTF-16-unit boundary. Notices remain plain text. Typed ordered
delivery items and the released artifact set are prepared atomically before any
parent completion. PostgreSQL owns upload capabilities, file IDs, stages, and the
delivery claim fence. Each allocation/byte-upload/finalization is bounded by a
renewed lease; successful stages release and reschedule rather than holding a
database transaction over HTTP. Finalization intent is persisted first, and
unacknowledged recovered intent is `UNCONFIRMED`, never automatically repeated.
Parent `SENT` requires every text item and upload to be confirmed `SENT`.
The Slack SDK and isolated no-redirect/no-credential byte transport stay inside
`slack/adapter/sdk`; conversation final text remains provider-neutral.
V10's progress view observes the full prepared set in one statement snapshot:
successful jobs remain `DELIVERING` until parent, text/file items and each FILE
upload are `SENT`. Missing preparation or upload evidence is not completion;
`FAILED`/`UNCONFIRMED` upload evidence yields `DELIVERY_FAILED`. Cancellation and
processing-failure precedence remains unchanged. Discovery reuses this view's
delivered predicate, so historical completed questions are not reopened. Progress
updates still use each question's own queued notice and an independent executor.

Unsupported Markdown/file permission errors are delivery failures, not parser,
splitting or plain-text fallback paths. `files:write` and any app
reinstall/reauthorization require separate approval; Runtime adds no `files:read`
reconciliation. Artifact bytes, final-answer upload bytes and sensitive capabilities
inherit PostgreSQL/backup access and conversation retention. This feature adds no
automatic expiry/deletion or public artifact URL. An uncertain finalization is
retained for operator reconciliation, never repaired by another model turn or
automatic finalize/reallocation.



`storage :: adapter.postgres.jdbc` exposes only shared PostgreSQL integration
components: `PostgresIntakeCapacity` and `PostgresUuidTypeHandler`. Slack depends
on this narrow export, not the conversation mapper/store export
`storage :: adapter.postgres`. Both direct-construction MyBatis factories and
bootstrap's MyBatis configuration register the same UUID handler; Slack has no
separate UUID implementation.

Bootstrap supplies one `PostgresIntakeCapacity` instance to
`PostgresConversationStore` and `PostgresMessageSubmission`. It owns the configured
limit, the global transaction-scoped advisory lock, and the query counting
`PENDING`, `WORKING`, and `RETRY` jobs. Intake adapters acquire this lock before
duplicate lookup and check capacity only for new jobs, using the same data source
and transaction as the resulting writes.

Slack still owns its receipts, immutable thread binding, and delivery notices.
Accepted jobs and their receipts/binding/queued notice commit atomically; a full
queue instead commits BUSY receipts and a busy notice without a session or job.
Slack checks capacity without throwing before nested conversation intake, avoiding
a rollback-only transaction that would discard BUSY. Core intake retains its
`QueueFullException` contract. Accepted duplicates resolve to their original job
even at capacity, and BUSY duplicates remain BUSY after capacity releases. The
separate delivery worker and provider-neutral conversation contracts are unchanged.

Per-question status belongs entirely to Slack, not conversation history. V9 adds
`slack_question_progress`, `slack_progress_workspace` and a coherent database
job/answer-part observation view. Confirmation of a new `QUEUED` delivery registers
its progress row in the same transaction; bounded recovery discovery skips fully
delivered historical terminal work. The composite foreign key ties the exact job,
source identity and confirmed notice timestamp together, never the latest thread
job. Only unexpired processing ownership admits an active state; `DONE` is still
delivering until every persisted final-answer part is `SENT`.

`SlackProgressWorker`/`SlackProgressLifecycle` run independently of answer delivery.
Claims use worker identity, claim number, lease and observed revision fencing, with
a durable workspace gate shared by replicas. HTTP runs outside database
transactions. A missing original-question link uses one SDK `chat.getPermalink`
call, validates the provider URL against the source channel/message using the
existing permalink parser, then caches it under the claim and releases ownership.
The next gated claim re-observes the latest job before `chat.update`; there are
never two outbound calls inside one claim window. Thread lookup still resolves a
threaded permalink to its root, while question-link validation checks the specific
path message and preserves the provider's thread query unchanged.

The independent retry/cooldown path cannot create model requests, conversation
events or replacement notices. Progress failures never fail or suppress answer
delivery. Defaults are 2s polling, 30s unchanged-state refresh and 2s workspace
spacing, with coalescing and persisted `Retry-After` delays rather than latency
guarantees. Terminal updates retain a lease-window reconciliation pass after their
first acknowledgment; stale local claims cannot acknowledge success. Slack has no
conditional remote write, so delayed accepted requests can still briefly regress
visible text. An old UTC observation remains static during a complete Runtime
outage and is not a current liveness assertion. Replay/restart recover the same
confirmed notice and cached provider permalink, not a guessed workspace URL.

Keep provider SDKs inside their owning adapters. The conversation domain,
application, ports, generic tool module, storage, web, and worker remain free of
model, MCP, Google, and Slack SDK types. MCP connections remain independent: an
unavailable named server must not hide tools from another available server or
prevent startup. Tool execution remains sequential in the model-provided order,
with opaque observations passed back through the generic tool and conversation
contracts.

## Audit history and model admission

`ConversationStore.loadHistory` and its paged variant remain complete audit APIs.
Model response history, compaction input/suffix, and session usage instead use
`ContextHistoryView` over one `loadContextHistory` snapshot. Pending/rejected skill
command USER events and rejected-command runtime feedback are excluded by durable
disposition, never by matching error text. Automatic tool failures remain visible.
An accepted command's original USER identity moves logically to immediately before
its resolving SKILL event. Range boundaries use that resolution position for both
events, so an old audit USER can cross an earlier summary boundary once, but neither
event is restored after its resolution is summarized. Native tool batches keep
their original contiguous audit sequences and IDs.

Usage projections include a deterministic eligibility fingerprint of sorted command
IDs, dispositions, and resolution positions. It participates in request-shape
matching, not body token estimation, invalidating provider checkpoints after an
admission change. Summary candidates and retained suffixes come from the same
eligible history snapshot; SKILL alone is not a response boundary.

Provider usage checkpoints additionally require coverage proof at response commit.
The worker carries the last **audit** sequence from the exact history snapshot used
for its request, not the last logically admitted message. In the append transaction,
sequence allocation locks the session row; storage writes a checkpoint only when
the assistant response immediately follows that snapshot boundary. A USER arriving
during either the model call or sequential tool execution therefore prevents that
response's checkpoint from hiding the intervening message. The history and complete
tool batch still commit normally. Pending/rejected skill audit events may
conservatively prevent reuse, while admission changes retain their separate
eligibility fingerprint protection.

Request-shape fingerprints are versioned for this coverage contract, so checkpoints
written before the proof existed are ignored without altering deployed migrations.
With no matching checkpoint, usage falls back to a full estimate; an older proven
checkpoint remains reusable because every later admitted event is in its estimated
suffix. Normal adjacent responses still reuse provider totals, with tool observations
and other later events estimated after the assistant boundary. No schema migration,
history rewrite, or retry/skill-admission change is needed.

The library is configured explicitly through `session-agent.skills.roots` (empty
by default), `max-file-bytes` (65536), and `max-skills` (128). Bootstrap scans
direct child `SKILL.md` frontmatter once; normal request tool descriptions contain
that metadata, never preloaded bodies. Each read reopens bounded current UTF-8
bytes using no-follow directory handles; the returned hash records evidence, not
a version lock. `/skill:name` on a newly submitted source message rereads, while
source replay and recovery after a committed selection reuse durable history.
Summarization has no tool definitions, and neither automatic TOOL bodies nor
explicit SKILL bodies are pinned or restored after ordinary compaction.

Enabled roots require a Java filesystem provider supporting
`SecureDirectoryStream`; unsupported providers fail startup closed. The intended
environment is Linux with a supporting local filesystem provider, not a guarantee
for every Linux filesystem or mount. The read limit bounds returned bytes, not
file-open latency: regular-file checks and no-follow opens do not provide a
timeout against a privileged concurrent writer replacing an entry with a FIFO or
another special file. Roots must remain trusted operator-managed content, not a
hostile-writer boundary.

Only operator-managed shared roots belong at this boundary. Service accounts and
container mounts should be read-only, including references; all Runtime users can
request those files. There is no shell execution, automatic home/repository skill
discovery, or implicit import of this repository's maintenance/deployment skills.
The [skill configuration guide](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用)
describes the on-disk definition and command.

## Test responsibilities and locations

Tests are organized around the behavior boundary they prove. A test package is
not a blanket mirror of the production package tree: tests that need a
package-private SDK seam stay with that seam even when their assertions cover a
cross-layer behavior.

| Boundary | Current test organization |
| --- | --- |
| Domain invariants and provider-neutral records | `ConversationDomainTest`, `ContextUsageEstimatorTest`, `GenericToolSnapshotTest`, and `slack/domain/SlackPermalinkParserTest`; these run without Spring, PostgreSQL, an MCP server, or a model key. |
| Conversation application behavior with local fakes | `MessageServiceTest`, `SessionQueryServiceTest`, `MessageJobContextCompactionTest`, `MessageJobToolExecutionTest`, `MessageJobRecoveryTest`, `MessageJobHandoffTest`, `MessageJobSkillsTest`, and shared `MessageJobTestFixtures`; these cover history admission, purpose-aware reservations, bounded handoff, semantic modes, ordered tools, skill receipt propagation, continuation, claim fencing and recovery. |
| Model adapter behavior | `model/adapter/springai/HistoryProjectorTest`, `SpringAiConversationModelTest`, `SpringAiToolCallbackFactoryTest`, `SpringAiMessagePartsHandlerTest`, `GoogleGenAiContinuationTest`, and the explicitly gated `GoogleModelLiveTest`. Ordered continuation tests retain reasoning, per-part signatures, unknown provider parts and media; local fake-HTTP provider tests exercise replay without model quota. |
| MCP adapter behavior | `mcp/adapter/sdk/McpConnectionClientFactoryTest`, `McpConnectionManagerTest`, `McpToolCatalogTest`, `McpToolResultMapperTest`, and `McpConnectionsEndpointTest`. The endpoint test stays in this package because it exercises the SDK adapter's private seam. |
| PostgreSQL and HTTP adapters | `storage/adapter/postgres/PostgresConversationCommitPostgresIT`, `PostgresMessageIntakePostgresIT`, and `PostgresMessageJobPostgresIT`; `web/adapter/http/MessageControllerTest`; and Slack persistence tests described below. |
| Durable generated artifacts | `artifact/ArtifactEncoderTest`, `ArtifactToolCatalogTest`, and `storage/adapter/postgres/ArtifactStorePostgresIT`; bounds, CSV safety, trusted invocation identity, route eligibility, exact actual-observation association, PostgreSQL rollback/lease/restart/replay/isolation, and orphan invisibility. |
| Worker behavior | `worker/application/MessageJobWorkerTest`, covering claim, renewal, processing, retry, limit, and recovery coordination. |
| Slack application and adapters | `slack/application/MessageHandlerTest`, `SlackDeliveryLifecycleTest`, `SlackDeliveryWorkerTest`, `SlackProgressWorkerTest`, `SlackProgressLifecycleTest`, and `SlackSocketLifecycleTest`; `slack/adapter/postgres/SlackDeliveryPostgresIT` and `SlackProgressPostgresIT`; and SDK-seam tests in `slack/adapter/sdk`, including `SlackConfigurationTest`, `PostgresMessageSubmissionPostgresIT`, `SlackSocketShutdownTest`, `SlackBoltSocketClientTest`, and `SlackSdkWebApiTest` (real SDK calls against local fake HTTP, including question permalink identity and update errors). |
| Bootstrap configuration and metrics | `bootstrap/configuration/RuntimeConfigurationTest`, `ApplicationStartupTest`, `RuntimePropertiesTest`, and `LoggingConfigurationTest`; `bootstrap/adapter/metrics/RuntimeObservabilityTest`. |
| Architecture and dependency boundaries | `ApplicationModulesTest` verifies the eleven closed modules, the exact allowed dependency map, and the complete named-interface map. `RuntimeBoundaryTest` verifies provider boundaries, MCP and Slack SDK confinement to their SDK adapters, and the domain/port direction rules for conversation and Slack. |

The ordinary suite uses local fake model/tool boundaries and keeps local HTTP
exposure loopback-only. `SlackSdkWebApiTest` starts a fake JDK HTTP server on
the loopback address and an ephemeral port; it does not contact Slack. Tests
that use Mockito static/final mocking need a normal Java 21 process in which
Mockito/Byte Buddy can self-attach. Run the ordinary suite with Java 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

Run the PostgreSQL profile when checking migrations, persistence, transaction
boundaries, history ordering, or concurrency:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

For the complete clean ordinary-plus-PostgreSQL gate, use
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Ppostgres-it clean verify`.
The ordinary phase is the full suite, not a selected subset. Integrated acceptance
also needs a separately launched real Runtime with isolated PostgreSQL, actual
configured-model and Slack SDK adapters pointed at local fake HTTP services, and
synthetic intake through the existing Slack adapter. Capture blocked-model refresh,
multipart delivery, independent progress errors, cancellation and restart/history
counts; keep private evidence outside Git and remove temporary launchers. This
proves local transport integration, not genuine Slack inbound or native client UI.

Continuation/skill acceptance additionally exercises cap-discard/one no-tools wrap,
persisted waiting across restart, all four follow-up decisions, restricted tools,
artifact ownership/release, committed definition/reference/failure notices and exact
acknowledgement without progress noise in history. Local deterministic boundary
responses prove integration, not model semantic quality. Authorized real-model
samples must separately capture actual Luna response identity, real read-only MCP,
stored skill bytes/hash, Slack API updates and downloaded artifact hashes. Keep
production Sol and its allowance unchanged; isolated Luna runs must be selected
before even valid skill-only confirmation. Synthetic ingress and authenticated
browser/human Socket evidence are distinct acceptance boundaries.


Neither command should require a live model or MCP server. Keep live model,
deployed MCP, and real Slack behavior explicitly authorized and outside the
default fake-backed suite.

## Conversation execution contract

One submitted user message starts one ordered job. A job can make one or more model calls. A model call either returns assistant text or requests tools. Within the ordinary allowance, the runtime executes admitted calls sequentially in the model-provided order, then atomically appends one `ASSISTANT_TOOL_CALLS` event followed by all paired `TOOL` observations in one batch; those observations are supplied to the next model call. Tool calls returned on the last allowed ordinary request are neither executed nor appended; the bounded handoff below takes precedence.

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

Wrap-up uses the same compaction snapshot for visible history, summary, and continuation
selection. It replays opaque continuation only for visible `ASSISTANT_TOOL_CALLS`
sequences on the selected model route. A visible continuation for another route
uses the safe waiting fallback without a provider request, rather than dropping
provider state and sending a damaged replay. Compacted and non-tool sequences do
not enter the wrap continuation map. Provider payload interpretation remains inside
the model adapter. The adapter combines the base prompt and transient purpose
directive into one system instruction; ordinary prompts without a directive are
unchanged, and directives never become stored conversation events.

Continuation belongs to the current job, not the session's future jobs. It commits
with its assistant/tool batch and survives retry, store reconstruction, and a
valid reclaim. Compaction deletes covered entries; completion (including a waiting
response) and cancellation delete the job's remaining continuation. Durable audit
history is retained independently of this operational replay state.

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

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history. With a direct provider connection, each reservation corresponds to a provider request; when a gateway retries internally, it does not bound the gateway's underlying provider attempts.

When a known storage failure stops an admitted tool batch before append begins,
attempt-local observations already obtained are retained unchanged and unstarted
calls receive `TOOL_NOT_EXECUTED` observations. Recovery's
`ConversationStore.appendAndScheduleRetry` commits the complete assistant/tool
batch, optional continuation, applicable usage checkpoint and receipt associations,
and the fenced `RETRY` transition in one PostgreSQL transaction. A failed retry
transition rolls back the batch as well; no separately committed working batch
is left behind. A cancellation that wins this transaction commits cancellation
instead, without retaining continuation or scheduling a retry.

Once append or atomic recovery has begun, a failed response can mean the
transaction committed. The processor reports state unconfirmed and does not
resend that batch or invent a replacement result; durable state and claim/lease
recovery decide what happens next. This is not tool-result caching or general
exactly-once execution.

Tool execution has no runtime deduplication layer. A read-only tool can run again if the process crashes after execution and before its observation batch commits. Any future side-effecting tool must provide idempotency at its adapter or external-service boundary.
