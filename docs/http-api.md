# HTTP API and Slack delivery reference

[Project overview](../README.md) · [Architecture](package-architecture.md)

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

HTTP and Slack intake share one PostgreSQL admission component, configured once
at bootstrap. It owns the capacity limit, transaction-scoped advisory lock, and
unfinished-job query. Both paths resolve duplicates before checking capacity for
new jobs, so accepted resends still resolve to their original job when full.

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

Ordinary text replies continue a session **only in an already-bound thread**;
they do not require a bot mention. Replies in an unbound thread are ignored even
when they mention the bot: start a new addressed top-level message instead.
The root-thread/session binding is immutable. Ordinary reply and DM text is
trimmed but retains mentions; addressed non-DM roots remove the addressing bot
mention. Explicit skill commands separately remove one leading own-bot mention.
Edits/deletes, hidden messages, attachment-bearing messages, unsupported subtypes,
and bot/system noise are not accepted as ordinary user work.

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
