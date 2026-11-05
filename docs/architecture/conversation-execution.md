# Conversation execution

[Question index](../README.md) | [Architecture overview](../package-architecture.md) | [繁體中文](conversation-execution.zh-TW.md)

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

Owns the ordered model/tool loop, request allowance, handoff, cancellation, retries and recovery. It does not own HTTP wire fields, usage accounting, artifact encoding, or Slack transport.

<a id="contents"></a>

## Contents

- [Ordered model and tool loop](#ordered-model-and-tool-loop)
- [Control ownership](#control-ownership)
- [Allowance and wrap-up](#allowance-and-wrap-up)
- [Continuation lifecycle](#continuation-lifecycle)
- [Follow-up decisions and report-only mode](#follow-up-decisions-and-report-only-mode)
- [Worker migration boundary](#worker-migration-boundary)
- [Retries and atomic recovery](#retries-and-atomic-recovery)

<a id="ordered-model-and-tool-loop"></a>

## Ordered model and tool loop

One submitted user message starts one ordered job. A job can make one or more model calls. A model call either returns assistant text or requests tools. Within the ordinary allowance, the runtime executes admitted calls sequentially in the model-provided order, then atomically appends one `ASSISTANT_TOOL_CALLS` event followed by all paired `TOOL` observations in one batch; those observations are supplied to the next model call. Tool calls returned on the last allowed ordinary request are neither executed nor appended; the bounded handoff below takes precedence.

The runtime exposes registered tools to the model but does not interpret a provider's input schema or tool output. One ordinary tool failure is recorded as that tool's observation and does not prevent later requests in the same batch.

The general conversation prompt asks for question-focused answers that explain the
outcome and reasoning before supporting evidence. Commit SHAs, full source paths,
line citations, and tool procedures are omitted by default unless requested or
needed for understanding; relevant uncertainty and version limitations remain
explicit. This is presentation guidance only: exact tool identifiers, required
source/version verification, opaque MCP data, and complete audit history are unchanged.

<a id="control-ownership"></a>

## Control ownership

`JobHandoffCoordinator` owns the bounded control protocol, not a second tool loop.
`JobControl` separates persisted execution mode/stage, exact initiating user and
waiting generation from committed conversation history. Semantic decisions persist
under the fenced claim before changing tools. `ToolSnapshot.restrictedTo` transfers
single-owner cleanup; capability metadata is local composition authority, never
inferred from MCP names or schemas. The [handoff contract](#allowance-and-wrap-up)
defines allowance, waiting, and recovery behavior.

<a id="allowance-and-wrap-up"></a>

## Allowance and wrap-up

`session-agent.model.max-model-calls-per-message` bounds ordinary execution and context-compaction requests for one user message. Its environment form is `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`; the default remains 12. Failed ordinary attempts and retries consume this allowance. Every actual provider request is reserved before sending and also increments the existing total `modelCallCount`; intent and wrap-up have separate one-shot reservations, not additional investigation allowance.

A direct text answer on the last allowed ordinary request completes normally. A tool response
on that request executes **zero** tools and appends neither the assistant calls nor fabricated
tool observations. Exhaustion enters durable `WRAP_UP`: at most one no-tools request briefly
describes confirmed progress and gaps and asks whether to continue investigating or organize
the evidence. The transient directive contains no counts and is not committed to history.
The question and durable session waiting generation commit atomically with job completion.
Waiting is not a running job. If the selected request context is too large, Runtime skips
dispatch and uses a safe deterministic waiting notice. A failed, invalid or tool-bearing
wrap response also uses that notice; this is not an oversized returned-text rejection rule.
Recovery after a reserved but lost request never requeries it.
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

<a id="continuation-lifecycle"></a>

## Continuation lifecycle

Continuation belongs to the current job, not the session's future jobs. It commits
with its assistant/tool batch and survives retry, store reconstruction, and a
valid reclaim. Compaction deletes covered entries; completion (including a waiting
response) and cancellation delete the job's remaining continuation. Durable audit
history is retained independently of this operational replay state.

<a id="follow-up-decisions-and-report-only-mode"></a>

## Follow-up decisions and report-only mode

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

<a id="worker-migration-boundary"></a>

## Worker migration boundary

V11 backfills ordinary usage from existing total calls without inferring historical waiting
state. Drain old workers before migration: mixed old/new workers are unsupported.

<a id="retries-and-atomic-recovery"></a>

## Retries and atomic recovery

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history. Runtime reserves each provider request before dispatch, but a crash or ownership loss can leave a reservation undispatched. Reserved counts are therefore not proof of requests sent or billed; gateway-internal attempts are outside Runtime's reservation bound.

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
