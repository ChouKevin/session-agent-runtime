# Task 1 report

Implemented provider-neutral model descriptors, canonical request-shape fingerprints, full/trailing context estimates, result-carried usage, and transactional PostgreSQL usage checkpoints.

## Scenario

`CONTEXT-2`: Given no same-route/model/shape/generation checkpoint, when evaluating a request, then Runtime uses `FULL_ESTIMATE`; given a matching checkpoint, it uses its reliable total plus only events after the response boundary (`PROVIDER_PLUS_TRAILING_ESTIMATE`).

## Evidence

RED: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=ContextUsageEstimatorTest test` failed as expected: expected `PROVIDER_PLUS_TRAILING_ESTIMATE`, got `FULL_ESTIMATE`.

GREEN: the same command passed (1 test, 0 failures/errors). A broader focused command was run outside the sandbox because Mockito self-attachment is blocked inside it; it exposed legacy-mock compatibility regressions, which were fixed. The final focused rerun was not completed before handoff. PostgreSQL profile verification was not run.

## Files and review

Changed model, conversation policy/ports, PostgreSQL V1/store, bootstrap configuration, and `ContextUsageEstimatorTest`; commit `6bd4eb9`.

Self-review: checkpoint insertion is in the existing append transaction and uses the assigned assistant response sequence. Capacity catalog contains the approved `gemini-3.1-flash-lite` value and positive override.

## Concerns

NEEDS_USER_DECISION: none. FOLLOW_UP: complete focused and PostgreSQL verification; add an integration assertion for rollback of the checkpoint with a failed response append.
