# History and usage

[Question index](../README.md) | [Architecture overview](../package-architecture.md) | [繁體中文](history-and-usage.zh-TW.md)

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

Owns audit/context admission, compaction checkpoint coverage and durable cache accounting. HTTP owns report arithmetic and public fields; this page is not a test command guide or a live-provider proof.

<a id="contents"></a>

## Contents

- [Audit and context admission](#audit-and-context-admission)
- [Checkpoint coverage and compaction](#checkpoint-coverage-and-compaction)
- [Durable cache accounting](#durable-cache-accounting)
- [Response observations](#response-observations)
- [Raw provider usage](#raw-provider-usage)
- [Optional accounting write](#optional-accounting-write)
- [Diagnostic evidence](#diagnostic-evidence)
- [Cache report ownership](#cache-report-ownership)

<a id="audit-and-context-admission"></a>

## Audit and context admission

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

<a id="checkpoint-coverage-and-compaction"></a>

## Checkpoint coverage and compaction

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

<a id="durable-cache-accounting"></a>

## Durable cache accounting

V12 adds durable, provider-neutral per-job cache accounting without backfilling old
jobs: existing rows retain NULL accounting, while new jobs start with zero counters.
`ConversationStore.recordModelUsage` records a sequential reserved-call observation
under the live worker/claim/lease fence, checks ownership again before commit, and
allows an in-flight response to be counted after cancellation is requested. Its
ordinal watermark makes duplicate or older observations no-ops; unreserved future
ordinals cannot mutate accounting. Missing/invalid usage advances the watermark
without known-token sums, so unknown reservations remain in the request denominator.
SQL/arithmetic failures roll back and propagate to the caller; this store does not
isolate optional-write failures from application processing.

<a id="response-observations"></a>

## Response observations

Each model port method requires a synchronous `ModelCallObservation` consumer.
The Spring AI adapter retains the total ordinal returned by the pre-request
reservation and emits exactly one observation immediately after a provider response
returns, before ordinary reply normalization or intent/summary validation. This
includes tool-loop responses, discarded last-budget tool responses, compaction,
intent and wrap-up. Invalid output can still carry known cache usage. Reservation
denial, invalid pre-dispatch history and provider exceptions emit no observation:
the durable reserved attempt remains unknown across retries, reclaim and restart.
A crash between reservation and dispatch is not proof of a billed provider request.

<a id="raw-provider-usage"></a>

## Raw provider usage

Cache coverage requires explicit, nonnegative raw input/cache fields with cached
input no greater than input; missing completion or total tokens do not affect it.
Unsupported usage and omitted fields are unknown, never inferred zero. Provider
types remain in the model adapter. Google accounting reads native optional fields
rather than Spring's default-zero prompt getter. Configured OpenAI uses a typed SDK
client/service response decorator because the pinned Spring mapper reads mandatory
ordinary token fields before returning a response. Only usage shapes that mapper
cannot safely consume have standard usage removed and original raw usage preserved
in adapter-owned response metadata. Valid ordinary responses pass through unchanged;
the adapter never fabricates ordinary `ModelUsage` or checkpoint availability to
recover a cache pair. SDK transport headers, status, body, ownership and other
operations delegate unchanged; no transport rewriting, added request or provider
retry is involved.

<a id="optional-accounting-write"></a>

## Optional accounting write

`JobCacheUsageRecorder` routes observations from ordinary execution, compaction and
handoff into one extra fenced store transaction per returned response. It catches
only `RuntimeException` from this optional usage write, leaving that reservation
unknown if the write fails. It does not fail a valid answer, initiate recovery or
request another model response. Required storage operations and fatal errors keep
their existing failure behavior. Cancellation requested after a response does not
erase its usage. Durable cache accounting is independent of the existing
request-shape and response-boundary proof used for context usage checkpoints:
neither history/checkpoint atomicity nor tool-batch recovery semantics change.

<a id="diagnostic-evidence"></a>

## Diagnostic evidence

Existing ordinary-turn `model_response` lifecycle events include `cacheUsageKnown`
and, only when known, the exact `cachedInputTokens` value (including zero).
These fields use the same raw-normalized `ModelCallObservation.cacheUsage` as
aggregate accounting, not the ordinary `ModelUsage` cache getter, whose provider
defaults can lose field presence. Missing observations or missing/invalid cache
usage emit `cacheUsageKnown=false` and omit `cachedInputTokens`; no prior turn's
value carries forward. Cache availability is independent of the existing
prompt/completion/total token fields. Session/job IDs and the reserved total
`callOrdinal` retain request correlation, without logging conversation or tool
contents. The diagnostic describes the observed response even if its optional
accounting write fails; it is not proof of durable accounting. This does not add
response events for intent, compaction or wrap-up calls.

<a id="cache-report-ownership"></a>

## Cache report ownership

`JobCacheReport` supplies the provider-neutral projection whose arithmetic,
coverage, and finality are defined in the [HTTP cache report](../http-api.md#cache-report).
See [Slack delivery](slack-delivery.md#prepared-deliveries-and-upload-transport) for transaction and prepared-byte ownership.
