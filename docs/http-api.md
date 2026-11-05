# HTTP API and Slack delivery reference

[Home](../README.md) | [繁體中文](http-api.zh-TW.md) | [Question index](README.md) | [Architecture](package-architecture.md)

Run examples against your configured loopback address. The API does not add an authentication layer; do not expose it publicly without a separately secured boundary.

## Message submission

Submit a message, poll its job, then read the complete session history:

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
POST /internal/message-jobs/{messageJobId}/cancel
POST /internal/session-lookups
GET  /internal/sessions/{sessionId}
GET  /internal/sessions/{sessionId}/messages
PUT  /internal/sessions/{sessionId}/model
GET  /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same session. The accepted response contains `sessionId` and `messageJobId`.

All four fields are required and nonblank. `sessionKey`, `participantId`, and
`sourceMessageId` are each limited to 256 characters. The successful response is
`202 {"sessionId":"...","messageJobId":"..."}`.

Idempotency is scoped by **source type plus `sourceMessageId`**, not by session
key. For HTTP submissions, use source IDs unique across all your sessions.
A resend with the same participant and message returns the original receipt,
even if `sessionKey` changes. Reusing that identity with changed participant or
message returns `409 {"code":"message_conflict"}`. Duplicate resolution precedes
capacity admission.

## Session lookup and details

`POST /internal/session-lookups` accepts `{"permalink":"https://...slack.com/..."}`.
The permalink must be nonblank and identify a supported Slack message/thread.
A successful lookup returns `200 {"sessionId":"...","sessionDetailPath":"/internal/sessions/..."}`.
Malformed references return `400`; valid references without a persisted binding
return `404`. Lookup does not create a session.

`GET /internal/sessions/{sessionId}` returns `200` with:

| Member | Shape |
| --- | --- |
| `sessionId`, `createdAt` | Session UUID and creation timestamp |
| `slack` | Optional `{teamId, channelId, rootThreadTs, createdAt}` |
| `currentJob` | Optional job summary described below |
| `latestCompaction` | Optional `{generation, coveredThrough, reason, createdAt}` |
| `context` | `{modelId, capacityTokens, estimatedUsedTokens, ratio, basis}` |

Absent `slack`, `currentJob`, and `latestCompaction` members are **omitted**, not
explicit JSON `null`. Optional fields inside job snapshots use explicit `null`.
Context usage is an estimate, not a provider quota or billing balance.
An unknown session returns `404`; malformed UUID paths return `400`.

## Validation and errors

Unknown JSON request properties are rejected. A model-selection key is required,
nonblank, and at most 64 characters. Each supplied history pagination parameter
must occur exactly once and be nonblank: `afterSequence` is an integer from
0 through 9223372036854775807, and `limit` from 1 through 2147483647.

Handled API errors expose a safe `{"code":"..."}` body, not exception details:

| HTTP status | Code | Meaning |
| --- | --- | --- |
| `400` | `bad_request` | Invalid request body, identifier, reference, or supported query parameter |
| `404` | `not_found` | Requested resource or session binding not found |
| `409` | `message_conflict` | Source identity reused with different participant/message |
| `429` | `queue_full` | New work cannot be admitted at the unfinished-job limit |
| `503` | `service_unavailable` | Unhandled runtime failure |

## Jobs, cancellation and history

The job response contains `messageJobId`, `sessionId`, `status`, `retryCount`, and
`modelCallCount`, plus `phase`, `phaseStartedAt`, `currentToolName`, `failureCode`,
`failureMessage`, and `cancellationRequestedAt`. `GET /internal/sessions/{sessionId}`
exposes a narrower `currentJob` summary: `messageJobId`, `status`, `modelCallCount`,
`phase`, `phaseStartedAt`, `currentToolName`, `failureCode`, `failureMessage`, and
`cancellationRequestedAt`; it does not include `sessionId` or `retryCount`.
Absent optional fields within these job responses are explicit JSON `null`.
`modelCallCount` is the total provider-request ordinal, including ordinary execution,
compaction, intent classification, and wrap-up. The separately persisted ordinary allowance
is operational state, not model history or prompt metadata; total ordinals remain artifact identities.

### Cache report

`GET /internal/message-jobs/{messageJobId}`, the cancellation response, and
`currentJob` in `GET /internal/sessions/{sessionId}` expose the same nullable
`cacheReport` projection. When present, it contains exactly these fields:

| Field | Meaning |
| --- | --- |
| `phase` | `PROVISIONAL` until the job is terminal; `FINAL` only for `DONE` or `CANCELLED`. |
| `status` | Usage-coverage state: `NOT_CALLED`, `UNAVAILABLE`, `PARTIAL`, `COMPLETE`, or `NOT_APPLICABLE`. |
| `requestCount` | Total reserved provider-request count for this message job; matches `modelCallCount`. |
| `knownUsageRequestCount` | Count of requests with a present, valid input/cache token pair. |
| `knownInputTokens` | Sum of input tokens only across requests with known usage. |
| `knownCachedInputTokens` | Sum of cached input tokens across the same known requests. |
| `hitRatio` | `knownCachedInputTokens / knownInputTokens`, rounded HALF_UP to six decimal places; JSON `null` when the denominator is zero. |

The token sums are known-subset totals, not estimates for missing requests.
Missing, unsupported, or invalid input/cache usage is unknown, not zero; an
explicit zero pair is known usage. `NOT_CALLED` means no requests were reserved.
`UNAVAILABLE` means requests were reserved but none had known usage. `PARTIAL`
means at least one but not all reserved requests had known usage, even if the
known token sums are zero. `NOT_APPLICABLE` means every request had known usage
but the known input-token sum is zero. `COMPLETE` means every request had known
usage and the known input-token sum is positive. Availability of completion or
total-token usage does not affect cache-usage coverage.

`hitRatio` is a fraction, not a percentage and not an average of per-request
percentages. For example, `0.555556` corresponds to a `55.6%` display when a
presentation separately computes its one-decimal percentage from the integer
known-subset sums. This neutral usage report is not a billing estimate or
promise.

Slack displays a percentage directly from the same integer sums, rounded HALF_UP
to one decimal, not from the rounded HTTP fraction: 25000 / 45000 displays `55.6%`.
Partial data retains its known-subset and known/total request coverage labels
(`資料不完整`), even when known input is zero. Unknown usage displays `未提供`,
no requests displays `未呼叫模型`, and known zero input displays `不適用` without
a numeric percentage. [Slack footer scope and byte ownership](slack.md#cache-footer)
are defined in the Slack guide.

Finality is independent of coverage and transport state. A `WORKING` job remains
`PROVISIONAL` after cancellation is requested; it becomes `FINAL` only when
cancellation commits as `CANCELLED`. `DONE` is also final. Slack delivery
status does not change report finality.

`requestCount` counts reservations made before provider dispatch. A process may
crash after reserving a call but before dispatching it, so a reserved unknown
attempt is not evidence that a provider request was sent or billed. Historical
jobs without cache-usage tracking return `"cacheReport": null`; newly tracked
jobs with no requests instead report `NOT_CALLED`. The report exposes no raw
provider metadata, per-request or per-model breakdown, or billing data.



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

If a storage failure stops an admitted tool batch before its history append begins,
recovery preserves already-obtained observations and marks unstarted calls
`TOOL_NOT_EXECUTED`; those calls were not invoked. The complete assistant/tool
batch and the `RETRY` transition commit in one transaction. A failed retry
transition does not leave a separately committed batch behind. After reclaim,
the next model request can use those durable observations without rerunning the
completed tool batch. This does not guarantee exactly-once tool execution after
an arbitrary process crash. An uncertain append response is not permission to
blindly resend a possibly committed batch.

Provider continuation is internal replay state, not an HTTP history field. It
survives retry/reclaim for the same job and can accompany visible tool history in
the final no-tools wrap-up request. Completion or cancellation removes that state
without deleting the committed assistant/tool observations.

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
| `SKILL` | `uri`, `sha256`, `content` |
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

HTTP and Slack share admission capacity. Both resolve duplicates before checking
capacity for new jobs, so accepted resends still resolve to their original job
when full. [Admission transactions](architecture/slack-delivery.md#admission-and-stop-transactions)
define the shared PostgreSQL implementation.

## Slack Socket Mode (optional)

See the [Slack guide](slack.md) for setup, message/thread intake, stopping work,
progress notices, answers, cache footers, and files. [Slack internal contracts](architecture/slack-delivery.md#admission-and-stop-transactions)
define admission transactions, lock order, fencing, recovery, and upload transport.
HTTP delivery query fields remain in [jobs, cancellation and history](#jobs-cancellation-and-history);
shared cache calculations remain in [cache report](#cache-report).

Core safety limits:

- Keep Slack tokens in the deployment environment or secret manager, never version control.
- `DONE` means processing ended, not necessarily success or delivery; every required
  text/file item must be confirmed `SENT` for completed delivery.
- Cancellation does not roll back executed operations; an in-flight call may finish or time out.
- Never reset `FINALIZING`/`UNCONFIRMED`, blindly finalize again, or resubmit a
  question to repair uncertain delivery. Preserve evidence for operator reconciliation.
- Scope changes/reinstallation and live Slack checks require explicit authorization.
