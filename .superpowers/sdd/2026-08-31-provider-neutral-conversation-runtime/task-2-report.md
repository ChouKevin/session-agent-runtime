# Task 2 Report — Opaque Tool Observations

## Summary

Implemented Task 2 only. The current tool-use path now persists adapter output as an opaque
`ToolObservationData` message and projects it as one provider-neutral user message. Semantic
source results carry repository identity and revision only inside a Semantic-private serialized
output envelope.

## Contracts and Files

- `DirectToolRegistry.invoke(snapshot, name, input)` returns the adapter `dataJson` unchanged.
  The legacy rich `execute` ABI remains only for the approved Task 5 removal.
- `SemanticToolProvider` serializes source results as private `SourceObservation(repositoryId,
  revision, data)`; no Semantic metadata was added to a common conversation or tool contract.
- `ConversationHistoryProjector` renders each `ToolObservation` once as a marked Spring AI
  `UserMessage` containing exactly the tool name, input, and output. It contains no provider
  call ID, signature, tool-response message, or system role.
- `MessageJobService` uses `invoke`, appends one `ToolObservationData` in a `MessageBatch` with
  `KEEP_WORKING`, and represents ordinary typed/invalid-input failures through generic
  `ToolFailureOutput` so the following work can continue.
- Acceptance test fakes were migrated to persist/consume Task 1 observations, rather than
  inventing legacy `ToolMessage` records.

## TDD Evidence

RED: the initial direct-registry/semantic contract test run failed to compile because
`DirectToolRegistry.invoke` and `ToolFailureOutput` did not yet exist.

GREEN: added the minimal implementation and focused behavior tests for unchanged adapter
output, Semantic source envelope, one-message projection, and success/failure persistence.
The required focused command passed 69 tests.

REFACTOR: extracted `ToolFailureOutput`, kept revision retry guidance model-safe, and removed
the current `MessageJobService` dependency on legacy result-envelope persistence. Updated the
acceptance harness after the broader suite exposed its default `ConversationStore.append` stub.

## Verification

Passed:

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress \
  -Dtest=DirectToolRegistryTest,SemanticToolContractTest,ConversationHistoryProjectorTest,MessageJobServiceTest test
# 69 tests, 0 failures, 0 errors

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress test
# 220 tests, 0 failures, 0 errors, 2 skipped live tests

git diff --check
# clean
```

## Commit

Implementation commit: `b1815d58a962f2fe36212fcc261e19ca02d7d3fe`

## Self-review and Risks

No Critical or Important findings. The legacy `execute`, `ToolMessage` projection, and rich
revision compatibility overload remain deliberately for Task 5. They are isolated from the
current execution path and are the main planned removal risk. The generic failure formatter
intentionally withholds repository/revision metadata; revision retry guidance is the only
Semantic-provided text carried into the safe output.

## Architecture Decision Status

No deviation or new architecture decision was needed. Task 3/4's neutral model adapter and
complete loop were not implemented; Task 5 deletions were not performed.
