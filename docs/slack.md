# Slack integration

[Home](../README.md) | [繁體中文](slack.zh-TW.md) | [Question index](README.md) | [HTTP API reference](http-api.md)

Slack is an optional intake and delivery adapter; conversation processing stays provider-neutral. This guide owns Slack setup, usage, and observable behavior. The [HTTP API reference](http-api.md#jobs-cancellation-and-history) owns delivery-status fields and [shared cache semantics](http-api.md#cache-report); [Slack delivery internals](architecture/slack-delivery.md#admission-and-stop-transactions) owns transactions, fencing, recovery, and upload transport.

## Connection and app setup

Slack is disabled when `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`, and
`SLACK_BOT_USER_ID` are all unset. Configure all three together to enable Socket
Mode; a partial configuration prevents startup. Keep tokens in the deployment
environment or secret manager; do not commit them.

Configure Socket Mode and subscribe to `app_mention` plus message events for
public channels, private channels, MPIMs, and direct messages. Grant
`app_mentions:read`, `channels:history`, `groups:history`, `mpim:history`, and
`im:history` as applicable, plus `chat:write` to send replies and update the bot's
own notices. Socket Mode handles inbound event intake and envelope acknowledgement;
a separate worker polls durable database deliveries and sends them through the
Slack Web API. Runtime has no public Slack callback endpoint, signing secret, OAuth
flow, or Slack event polling loop. Slack connection failures leave Runtime,
storage, and MCP startup available; the Socket Mode client reconnects asynchronously.

## Intake and thread behavior

### Start or continue a conversation

- Start a conversation with an addressed top-level channel, private-channel, or
  MPIM message; the addressing bot mention is removed. A top-level DM needs no
  textual mention.
- Continue a session with ordinary text replies only in an already-bound thread;
  no bot mention is required. Replies in an unbound thread are ignored even when
  they mention the bot: start a new addressed top-level message instead.
- The root-thread/session binding is immutable. Ordinary reply and DM text is
  trimmed but retains mentions; addressed non-DM roots remove the addressing bot
  mention. Explicit skill commands separately remove one leading own-bot mention.
- Edits, deletes, hidden messages, attachment-bearing messages, unsupported
  subtypes, and bot/system noise are not accepted as ordinary user work.

### Receipts

An accepted event gets one durable queued confirmation (`已收到，正在排隊處理。`). If
unfinished-job capacity is full, its source is durably recorded as busy with
(`目前工作已滿，這則訊息尚未受理，請稍後重新送出。`) and is not later promoted into
a job by resending it. These notices report delivery status, not job completion or
Socket Mode acknowledgement. HTTP and Slack share database admission, and accepted
resends resolve to their original receipt before capacity is checked; a BUSY resend
remains BUSY after capacity is released. See [queue and intake](http-api.md#queue-and-intake)
for queue limits and HTTP intake contracts.

## Stop a thread's work

### Send the command

Any human in a bound conversation thread can send `@bot stop` or `@bot 停止工作`.
The bot mention is required, including in DMs; after removing that mention and
trimming whitespace, the text must be exactly lowercase `stop` or `停止工作`.
Unmentioned text, uppercase `STOP`, extra words, edits, attachments, hidden
messages, and bot/system noise do not become stop commands.

### Results and limitations

- Runtime handles the command directly, even at full capacity: it creates no user
  message or job, calls no model, and does not wait in the work queue.
- It requests cancellation of every `PENDING`, `RETRY`, or `WORKING` job in the
  thread's immutable session binding and queues one durable `STOP` notice in the
  original thread. Other sessions and submissions accepted afterward are unaffected.
  Replaying the same Slack command does not cancel later work or duplicate the notice.
- The receipt reports `已收到停止請求，共 N 項工作` and warns that an in-flight call
  may need to finish or time out; it does not claim running work has already stopped.
  Cancellation uses the [HTTP cooperative safe points](http-api.md#jobs-cancellation-and-history):
  executed operations are not rolled back, and each job's normal terminal
  cancellation result is delivered separately.
- A bound thread with no nonterminal jobs, or an unbound thread, receives
  `此對話目前沒有可停止的工作`. Top-level commands ask the sender to use the
  target thread. Neither top-level nor unbound commands create a session or job.
- Commands use ordinary messages only: there are no buttons, shortcuts, or slash
  commands. `STOP` notices use durable delivery/retry and are queryable by Slack source.

## Per-question progress notices

### Notice and question link

After Slack confirms the queued notice timestamp, Runtime updates that same
bot-owned message with per-question status and the last-confirmed UTC observation
time. Questions in one thread keep independent notices; a notice is not the status
of the thread's latest job. The original-question link comes from
[`chat.getPermalink`](https://docs.slack.dev/reference/methods/chat.getPermalink/),
is validated against the source channel and message, and is cached durably. Progress
uses that cached permalink and does not need an extra scope for lookup.

### Update timing and limits

Defaults under `session-agent.slack.progress` are:

| Setting | Default | Meaning |
| --- | --- | --- |
| `poll-delay` | `2s` | Progress polling delay. |
| `refresh-interval` | `30s` | Refresh for unchanged nonfinal states. |
| `workspace-interval` | `2s` | Between progress API attempts per workspace across replicas. |

Changed states are eligible sooner; polling coalesces intermediate phases.
Backlog, outages, and Slack `Retry-After` can delay these best-effort intervals.
Slow or failing progress calls do not block answer posting, retry the model, or
add conversation/history events. Permanent lookup/update errors, such as a deleted
notice or permission loss, stop that target; Runtime does not post a replacement.

The timestamp is the last database observation, not proof of current model activity
or an external heartbeat. During a complete Runtime outage, the old timestamp
remains visible until recovery. Source replay and restart reuse the confirmed
notice and cached question link. A delayed accepted update can briefly overwrite
newer remote text: visibility is not guaranteed monotonic or exactly once.
An unknown initial post result does not authorize a replacement post.

### Reading status


Receipt means queued, not running. Progress distinguishes active preparation,
model waiting, tool lookup, retry, stopping, interrupted ownership, preparing
answer delivery, completion, cancellation, processing failure, and delivery failure.
The final answer remains separate from the mutable notice.

| Observation | Meaning |
| --- | --- |
| `DONE` | Processing ended; not proof of success or delivery. |
| Completed notice | Full item set prepared; final text and every required attachment confirmed `SENT`. |
| `DELIVERING` | A text item may be sent while a required attachment remains pending. |
| `DELIVERY_FAILED` | Upload failed or became `UNCONFIRMED`. |
| `WAITING_USER` | Investigation paused for the user's next choice; not a delivered report. |

Skill and reference notices distinguish loading, loaded, failed and interrupted reads;
failed/interrupted outcomes must not be read as loaded.
Loading/`STARTED` is not a committed-read outcome. Only loaded outcomes attest a
committed read, not model compliance or report quality; a reference read is not a
definition reload. Pending outcomes take precedence over ordinary progress and clear
only after acknowledgement of the exact presentation/revision. See the
[skill receipt and notification contract](architecture/skills.md#receipts-and-durable-notifications)
for the mechanics.

Cancellation/processing-failure precedence is unchanged; recovery does not reopen
historical fully delivered questions. Lease and reconciliation rules are in
[progress internals](architecture/slack-delivery.md#progress-ownership-and-reconciliation).


## Answers, cache footer, and files

### Answer format

An ordinary assistant answer is one native Slack `markdown` block with a fixed
accessible top-level notification. Markdown is not parsed, split, repaired, or truncated.

- The inline limit covers the composed answer, its two-newline separator, and
  cache footer: at most 10,000 UTF-16 units (also bounding code points).
- Overflow attaches exactly the original UTF-8 answer as `answer.md`; explanatory
  text carries the footer, not the file. No extra model call occurs.
- Runtime notices, including queue, cancellation, and terminal runtime errors,
  remain plain text.
- Unsupported native Markdown or attachments fail delivery visibly; Runtime does
  not silently fall back to plain text, strip tables/fences, truncate, or generate
  a replacement summary.

### Cache footer

Slack uses the same neutral per-job report as HTTP. See the API's
[cache report](http-api.md#cache-report) for canonical arithmetic, coverage,
zero-input rules, and the non-billing boundary.

- For tracked jobs, a footer appears once in durable `ANSWER` part 0 for ordinary
  answers, waiting-for-input answers, terminal failures, and cancellations.
- Partial data is labelled `資料不完整`, identifies the known subset and known/total
  request coverage, even for partial zero-input data. Unknown usage displays
  `未提供`, no requests displays `未呼叫模型`, and known zero input displays
  `不適用` without a numeric percentage.
- `QUEUED`/`BUSY`/`STOP` receipts and progress updates have no footer.
- The footer is prepared transactionally with delivery parts, not stored in raw
  history or artifact bytes. Retries/reclaim reuse those prepared bytes.
  Untracked old jobs remain undecorated; already-prepared deliveries are not backfilled.
- This decoration does not resolve remote text-post acknowledgement ambiguity.

### Files and delivery

Long answers and eligible artifacts may be shared as files; delivery is separate
from artifact creation. Parent delivery is `SENT` only when every required item is
confirmed `SENT`. Retry resumes unsent items, never regenerates the answer, and
never reruns the model. Prepared-set and upload-stage invariants belong to
[delivery internals](architecture/slack-delivery.md#prepared-deliveries-and-upload-transport).

### Permissions and uncertain outcomes


File sharing requires the installed app's `files:write` permission. Scope changes
and any required app reinstall/reauthorization need explicit operator approval;
upgrading Runtime does not grant permission or authorize changing the app/token.

Runtime does not request `files:read` or automatically reconcile uncertain files.

Permission/type/expired-capability failures terminate delivery. An uncertain
finalization becomes `UNCONFIRMED` (parent `FAILED`): do not reset
`FINALIZING`/`UNCONFIRMED` to retry, finalize again, create a replacement file, or
resubmit the question to repair delivery. Preserve evidence for operator
reconciliation without assuming Slack did or did not share the file.

Upload capabilities are sensitive, and generated files,
accepted long-answer bytes, upload evidence/capabilities, and history inherit
database access, backup security, and retention. There is no automatic artifact
expiration/deletion or public UUID download URL. The existing initial-message-post
acknowledgement ambiguity also remains; these flows do not promise exactly-once
Slack delivery.

Use `GET /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...` to
inspect one Slack source. The endpoint deliberately excludes delivery text,
credentials, URLs, and provider errors. Its complete fields and validation are in
the [HTTP API reference](http-api.md#jobs-cancellation-and-history). Native Markdown
support, including workspace/platform feature availability, must be checked
separately from preservation of the original Markdown value. Rendering and actual
workspace file permissions require an explicitly authorized live Slack check; see
[development](development.md). For deployment setup, see [operations](operations.md).
