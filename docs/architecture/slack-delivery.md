# Slack delivery internals

[Question index](../README.md) | [Architecture overview](../package-architecture.md) | [繁體中文](slack-delivery.zh-TW.md)

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

Owns Slack intake/stop transactions, progress fencing, prepared delivery and upload transport. The Slack guide owns setup and observable presentation; HTTP owns delivery query fields. This page is not authorization to change app scopes, deploy, or call Slack.

<a id="contents"></a>

## Contents

- [Admission and stop transactions](#admission-and-stop-transactions)
- [Progress ownership and reconciliation](#progress-ownership-and-reconciliation)
- [Presentation and completion](#presentation-and-completion)
- [Prepared deliveries and upload transport](#prepared-deliveries-and-upload-transport)
- [Security, retention and uncertainty](#security-retention-and-uncertainty)

This section owns Slack admission, fencing, recovery, and transport invariants.
Use the [Slack guide](../slack.md) for configuration and observable behavior and the
[HTTP API](../http-api.md#jobs-cancellation-and-history) for delivery query fields.

<a id="admission-and-stop-transactions"></a>

## Admission and stop transactions

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

- HTTP and Slack share one PostgreSQL admission component configured at bootstrap:
  it owns capacity, the transaction-scoped advisory lock, and the unfinished-job
  query. Both resolve duplicates before checking capacity for new jobs.
- Slack intake atomically commits an accepted job with event/logical-message
  receipts, immutable thread binding, and queued notice, or commits BUSY receipts
  and a busy notice without a session or job. The nonthrowing capacity check
  precedes nested conversation intake, so a full queue does not mark the BUSY
  transaction rollback-only. BUSY resends stay BUSY after capacity releases;
  delivery retries are independent of admission and model processing.
- Stop acceptance atomically snapshots and requests cancellation of every
  `PENDING`, `RETRY`, or `WORKING` job in the immutable session binding, saves
  event/logical-message receipts, and queues one durable `STOP` notice. Other
  sessions and submissions accepted afterward are unaffected.
- Event replays and overlapping `app_mention`/`message` events for the same Slack
  message neither repeat cancellation nor duplicate the notice, even after later
  work is submitted. The transaction takes the shared intake lock, then locks all
  snapshot jobs in user-message order before allocating cancellation history
  sequences. Workers and HTTP cancellation retain job-before-session lock order.

<a id="progress-ownership-and-reconciliation"></a>

## Progress ownership and reconciliation

Per-question status belongs entirely to Slack, not conversation history. V9 adds
`slack_question_progress`, `slack_progress_workspace` and a coherent database
job/answer-part observation view. Confirmation of a new `QUEUED` delivery registers
its progress row in the same transaction; bounded recovery discovery skips fully
delivered historical terminal work. The composite foreign key ties the exact job,
source identity and confirmed notice timestamp together, never the latest thread
job. Only unexpired processing ownership admits an active state. For an ordinary
successful `ANSWER` completion, the base observation remains `DELIVERING` until
the full prepared delivery set is confirmed `SENT`. V11 overlays pending skill
presentations first, then reports `DONE`/`WAITING` as terminal `WAITING_USER`
unless the base observation is processing or delivery failure; waiting is not report delivery.

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

- The original-question permalink is validated against the original channel/message
  and cached durably, including thread query parameters. Canonical root links may
  include `thread_ts` equal to the root message timestamp; reply links resolve to
  the queried thread root. Both retain strict Slack-host, path, timestamp, and
  matching-channel validation; question links must identify the exact source message.
- A separate claim performs the lookup before a later claim updates the notice:
  one claim never spends two Slack call windows.
- Active preparation, model waiting, and tool lookup require a current processing
  lease. Cancellation/processing-failure precedence is unchanged; recovery does
  not reopen historical fully delivered questions.
- Progress uses the existing delivery lease, retry/backoff, and attempt limits,
  with its own scheduler. Source replay/restart reuse the confirmed notice
  timestamp and cached question link; unknown initial post outcomes never authorize
  another progress post.
- [`chat.update`](https://docs.slack.dev/reference/methods/chat.update/) can update
  only the bot's own messages and has no remote compare-and-swap. Claim/version
  fencing rejects stale local acknowledgments, but a delayed accepted request can
  briefly overwrite newer remote text. Terminal reconciliation waits at least
  the delivery lease window (default 30s, longer than the 25s call timeout) before
  retirement. This guarantees neither exactly-once posting nor monotonic visibility.

<a id="presentation-and-completion"></a>

## Presentation and completion

Slack presents final assistant text as a whole native Markdown block (fixed
accessible notification) or exact UTF-8 `answer.md` when answer + two-newline
separator + cache footer exceeds 10,000 UTF-16 units (also a conservative
code-point bound). Runtime notices remain plain text. Typed ordered delivery
items and the released artifact set are prepared atomically before any parent
completion. PostgreSQL owns upload capabilities, file IDs, stages, and the
delivery claim fence. Each allocation/byte-upload/finalization is bounded by a
renewed lease; successful stages release and reschedule rather than holding a
database transaction over HTTP. Finalization intent is persisted first, and
unacknowledged recovered intent is `UNCONFIRMED`, never automatically repeated.
Parent `SENT` requires every text item and upload to be confirmed `SENT`.
The Slack SDK and isolated no-redirect/no-credential byte transport stay inside
`slack/adapter/sdk`; conversation final text remains provider-neutral.
V10's progress view observes the full prepared set in one statement snapshot:
for ordinary successful `ANSWER` completion, the base observation remains `DELIVERING`
until parent, text/file items and each FILE upload are `SENT`. Missing preparation or
upload evidence is not completion; `FAILED`/`UNCONFIRMED` upload evidence yields
`DELIVERY_FAILED`. Cancellation and processing-failure precedence remains unchanged.
V11 presents pending skill outcomes first and overlays terminal `WAITING_USER` for
`DONE`/`WAITING` unless the base observation is processing or delivery failure.
Discovery reuses the delivered predicate, so historical completed questions are not
reopened for generic progress; pending skill presentations may still need acknowledgement.
Progress updates use each question's own queued notice and an independent executor.

<a id="prepared-deliveries-and-upload-transport"></a>

## Prepared deliveries and upload transport

- Delivery snapshots every required text/file item and all released tool artifacts
  in PostgreSQL before acknowledging any item. The parent cannot become `SENT`
  until every required item is confirmed. Auto-created `answer.md` has a
  deterministic job identity separate from tool artifacts. Attachment failure
  never regenerates the answer or reruns the model.
- V10 retains persisted V9 delivery parts as a complete prepared set of plain items,
  preserving identities and sent acknowledgements. In-flight deliveries resume
  only unsent items; they are not regenerated or re-split into the whole-answer
  presentation.
- `SlackPostgresDeliveryStore` consumes `ConversationQueryPort.findJob` inside the
  prepared-item transaction; its query service delegates to `ConversationStore.readJob`
  on the same data source. `SlackCacheReportPresentation` formats that neutral
  report, not a separate provider query or aggregation. [Cache semantics](../http-api.md#cache-report)
  and [footer scope/presentation](../slack.md#cache-footer) have their own owners.
  Prepared footer bytes and the released artifact set survive retries/reclaim/restart
  without new accounting writes. Once-per-job means durable composed delivery,
  not resolution of remote text-post acknowledgement ambiguity.
- Upload persists `PREPARED` → `ALLOCATED` → `UPLOADED` → `FINALIZING` → `SENT`,
  releasing ownership between bounded operations. Known safe rejections honor
  `Retry-After` only with positive documented rate-limit rejection; a timing header
  on an ambiguous completion error does not authorize replay.
- Permission/type/expired-capability failures terminate delivery. Finalization
  intent commits before the one-time Slack sharing call. A lost response, failed
  acknowledgement commit, or expired owner around finalization becomes
  `UNCONFIRMED` (parent `FAILED`), never a blind second finalize or new file.
  Operator reconciliation may be needed; this is not an exactly-once Slack guarantee.
  Initial-message-post ambiguity is unchanged.
- Sensitive upload capabilities are persisted only for staged transport, accepted
  only at HTTPS `files.slack.com/upload/`, and excluded from diagnostic rendering.
  The byte-upload client has no bot credentials and never follows redirects.
  [Permissions, retention, and reconciliation boundaries](../slack.md#permissions-and-uncertain-outcomes)
  remain in the operator-facing guide.

<a id="security-retention-and-uncertainty"></a>

## Security, retention and uncertainty

Unsupported Markdown/file permission errors are delivery failures, not parser,
splitting or plain-text fallback paths. `files:write` and any app
reinstall/reauthorization require separate approval; Runtime adds no `files:read`
reconciliation. Artifact bytes, final-answer upload bytes and sensitive capabilities
inherit PostgreSQL/backup access and conversation retention. This feature adds no
automatic expiry/deletion or public artifact URL. An uncertain finalization is
retained for operator reconciliation, never repaired by another model turn or
automatic finalize/reallocation.
