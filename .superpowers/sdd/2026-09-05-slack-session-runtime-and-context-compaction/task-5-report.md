# Task 5 Report — Committed Slack Terminal Delivery

## Summary

Implemented durable, independent Slack delivery for committed terminal events.
`SlackDeliveryWorker` discovers completed Slack-origin jobs, claims delivery rows
in a short PostgreSQL transaction, posts only after that transaction has ended,
and commits a `SENT`, `RETRY`, or `FAILED` result only while its delivery claim
remains live. Conversation history, message-job state, and model-call counts are
not modified by delivery retries.

## BDD scenarios and evidence

### SLACK-1 / SLACK-2 — terminal response in the bound Slack thread

- Given a completed bound Slack job whose final committed event is a `RUNTIME`
  message after intermediate tool-call and tool events, when discovery runs,
  then exactly one delivery is created and its post contains only the terminal
  committed text, bound `channelId`, and immutable root `threadTs`.
- PostgreSQL integration verifies duplicate discovery leaves one row, the post
  observes a committed `WORKING` claim, and the API receives the terminal text.
  The SDK adapter supplies `replyBroadcast(false)`.

### DELIVERY-1 — durable retry without regenerating output

- Given a claimed committed post whose first Slack API result is rate limited,
  when it is retried, then the exact same `SlackPostRequest` is posted, the
  persisted safe category is `RATE_LIMIT`, and Slack's `Retry-After` is used.
- Given transient failures, exponential delay is capped; a fifth failed attempt
  becomes persisted/queryable `FAILED`. Permanent failures become `FAILED`
  without a retry.
- The PostgreSQL exhausted-attempt scenario proves `FAILED` remains queryable
  and its message job retains its original model-call count.

### RECOVERY-1 — restart/lease recovery

- Given a discovered `WORKING` delivery whose lease has expired, when a new
  delivery store/worker is constructed, then it reclaims and sends the same
  durable row. The test verifies two attempts, one row, unchanged committed
  history, and unchanged model-call count.

## RED / GREEN / REFACTOR

- RED: `SlackDeliveryWorkerTest` initially failed with both expected `poll()`
  results `false`; no retry, failure, or success transition occurred.
- GREEN: add claim/discover/store behavior, safe retry classification, Slack
  SDK port, and the V1 `slack_delivery` state machine. The focused worker suite
  passed with exact request reuse, `Retry-After`, capped transient backoff,
  permanent failure, and fifth-attempt failure.
- REFACTOR: retain provider-neutral conversation packages; keep Slack delivery
  data/SDK details in `slack`; add a read projection for durable failure status.

## Changed contracts/files

- `V1__create_conversation_schema.sql`: unique mutable `slack_delivery` rows,
  claim/attempt/lease timings, safe categories, terminal-event FK, and Slack TS.
- `SlackPostgresDeliveryStore`, `SlackDeliveryWorker`, and Slack delivery domain
  types: discovery, claim fencing, live-claim result persistence, recovery, and
  query projection.
- `SlackSdkWebApi`: `chat.postMessage` with exact stored text, bound thread,
  `replyBroadcast(false)`, and safe transient/rate/permanent classification.
- Runtime configuration and Slack delivery properties: independent polling with
  default five attempts and bounded exponential backoff.
- `SlackDeliveryWorkerTest`, `SlackDeliveryPostgresIT`, and the existing schema
  assertion: fake boundary and PostgreSQL persistence/transaction coverage.

## Commit

- `94b19831eb4faa89921145105add56b6a5d4c129` — `feat(slack): deliver committed terminal responses`

## Verification

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
  -Dtest=SlackDeliveryWorkerTest,MessageJobWorkerTest test
```

Passed: 6 tests, 0 failures/errors. Mockito dynamic-agent warnings are existing
test-environment warnings.

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
  -Dtest=SlackDeliveryWorkerTest,SlackDeliveryPostgresIT test
```

Passed: 5 tests, 0 failures/errors.

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
  -Ppostgres-it verify
```

Passed: fake-backed suite plus 33 PostgreSQL integration tests, 0 failures/errors;
one authorized live-model test remained skipped. `git diff --check` passed.

## Failures resolved

- The first full-profile run failed only because the existing fresh-schema test
  enumerated the old table set. Its expected V1 contract was updated to include
  `slack_delivery`; the subsequent full profile passed.

## NEEDS_USER_DECISION

None.

## FOLLOW_UP

None.

## Remaining risk

Slack acceptance followed by a lost client acknowledgement remains intentionally
at-least-once: a retry may create a duplicate visible reply. The delivery ID,
terminal event identity, attempts, safe failure category, and returned Slack TS
provide durable correlation without claiming cross-system exactly-once delivery.

## Fix round 1 — review findings from `94b19831eb4faa89921145105add56b6a5d4c129`

### BDD scenarios and verification boundary

- `DELIVERY-1` supporting `Retry-After` and permanent-failure boundaries:
  fake Slack SDK HTTP responses are classified at the `SlackSdkWebApi` adapter
  boundary. A real SDK `SlackApiException` carrying a 429 response and
  `Retry-After: 7` produces safe `RATE_LIMIT` plus seven seconds; the documented
  deterministic `missing_scope`, `no_permission`, `msg_too_long`, and
  `invalid_arguments` responses produce `PERMANENT`.
- `RECOVERY-1` supporting maximum-attempt boundary: PostgreSQL integration
  creates an expired `WORKING` delivery at attempt five, then proves recovery
  changes it durably and queryably to `FAILED` without a Slack post or sixth
  attempt.
- `DELIVERY-1` independent-delivery lifecycle boundary: a blocking Slack fake
  runs in the delivery lifecycle while an independently scheduled message poll
  still runs; lifecycle shutdown returns within its configured 25ms bound.

### Finding dispositions

1. **BLOCKING resolved — HTTP 429 SDK exception bypassed response classification.**
   `SlackSdkWebApi` now reads only the SDK response status and `Retry-After`
   header, maps a valid 429 delay to `RATE_LIMIT`, and keeps malformed/missing
   delay responses transient. No exception body, request text, token, URL, or
   headers are persisted or logged.
2. **BLOCKING resolved — deterministic `chat.postMessage` errors retried.**
   The adapter conservatively classifies exact documented scope/permission,
   message-length, and invalid-request errors as `PERMANENT`; ambiguous channel,
   membership, and service failures remain transient.
3. **BLOCKING resolved — expired fifth `WORKING` attempt could claim attempt six.**
   The maximum-attempt policy is passed to the durable claim boundary. In the
   same short transaction, expired exhausted rows are failed with a safe
   `TRANSIENT` category before any candidate can be claimed; live-claim result
   fencing remains unchanged.
4. **BLOCKING resolved — blocking delivery used the shared scheduler.**
   Delivery polling now has a dedicated daemon scheduler managed by
   `SlackDeliveryLifecycle`; it cancels, interrupts, and waits only the bounded
   Slack shutdown interval. The Slack SDK has a 25-second whole-call timeout,
   validated to remain shorter than the 30-second delivery lease. Slack calls
   remain outside database transactions.

### RED / GREEN evidence

- RED: `SlackSdkWebApiTest` failed against the reviewed code with expected
  `RATE_LIMIT` but observed `TRANSIENT` for an SDK 429, and expected
  `PERMANENT` but observed `TRANSIENT` for `missing_scope`.
- RED: `SlackDeliveryPostgresIT` failed against the reviewed code because an
  expired fifth `WORKING` delivery returned `poll() == true`, proving a sixth
  Slack call was attempted.
- GREEN (focused):

  ```text
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
    -Dtest=SlackSdkWebApiTest,SlackConfigurationTest,SlackDeliveryLifecycleTest,SlackDeliveryWorkerTest test
  ```

  Passed: 12 tests, 0 failures/errors.
- GREEN (persistence):

  ```text
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
    -Dtest=SlackDeliveryWorkerTest,SlackDeliveryPostgresIT test
  ```

  Passed: 6 tests, 0 failures/errors.
- Broader verification:

  ```text
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
  ```

  Passed: 168 fake-backed tests (one authorized live-model test skipped) and 34
  PostgreSQL integration tests, with 0 failures/errors. `git diff --check`
  passed.

### Commit

- `5832de94bfec4fff7df0a9de2a166b73aab3ee71` — `fix(slack): harden delivery recovery and isolation`

### Residual risk / FOLLOW_UP

- The approved at-least-once outcome remains: loss after Slack accepts a post
  can still yield a duplicate visible reply on retry. This fix does not claim
  cross-system exactly-once delivery.
- No additional FOLLOW_UP items identified.
