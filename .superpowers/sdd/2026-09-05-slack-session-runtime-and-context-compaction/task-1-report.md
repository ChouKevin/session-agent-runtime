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

## Takeover completion

### BDD scenarios and verification boundary

- `CONTEXT-2` (domain/application): matching route/model/shape/generation checkpoints use `PROVIDER_PLUS_TRAILING_ESTIMATE`; unavailable or stale checkpoints use `FULL_ESTIMATE` rather than zero usage.
- `CONTEXT-2` response durability (PostgreSQL integration): the response boundary and available provider usage checkpoint commit in the same transaction. The forced later tool-observation insert failure leaves `context_usage_checkpoint` at zero rows.
- Existing bootstrap contract: the optional capacity override preserves the two-argument programmatic `RuntimeProperties.Model` constructor, while the annotated canonical constructor remains the Spring configuration-binding target.

### RED / GREEN evidence

- Original RED retained: `ContextUsageEstimatorTest` failed with `expected PROVIDER_PLUS_TRAILING_ESTIMATE, got FULL_ESTIMATE` before the estimator/checkpoint implementation.
- Takeover RED: removing the two-argument `RuntimeProperties.Model` constructor produced `NoSuchMethodException`. Restoring it initially made Spring startup fail with `wrong number of arguments: 0 expected: 3`; this identified ambiguous configuration binding rather than a persistence issue.
- GREEN: `@ConstructorBinding` on the canonical record constructor restored both the compatibility constructor and startup binding. `ApplicationStartupTest,RuntimeConfigurationTest` passed 7/7.

### Final verification

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=SpringAiConversationModelTest,MessageJobServiceTest,RuntimePropertiesTest test` — 28/28 passed.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=SpringAiBoundaryTest test` — 6/6 passed; provider dependencies remain confined to model/bootstrap/MCP boundaries.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Ppostgres-it verify` — 143 ordinary tests passed, 1 explicitly skipped live-model test; PostgreSQL integration 16/16 passed. The schema assertion includes `context_usage_checkpoint`, and the rollback scenario supplies a checkpoint before forcing the later tool insert failure.

### Files and self-review

Task 1 changed the provider-neutral conversation usage contracts/policy, PostgreSQL V1/store, model adapter, bootstrap configuration/properties, application YAML, estimator tests, and the PostgreSQL commit integration test. The takeover completion additionally corrected bootstrap constructor binding and retained the schema/rollback integration assertions in these uncommitted files:

- `src/main/java/com/java/system/sessionagent/bootstrap/RuntimeConfiguration.java`
- `src/main/java/com/java/system/sessionagent/bootstrap/RuntimeProperties.java`
- `src/main/java/com/java/system/sessionagent/model/SpringAiConversationModel.java`
- `src/test/java/com/java/system/sessionagent/storage/PostgresConversationCommitPostgresIT.java`

Self-review: Google/Spring AI catalog resolution is contained in bootstrap; the adapter receives only a provider-neutral descriptor. The current catalog entry remains `gemini-3.1-flash-lite` with capacity `1,048,576`; an unknown model needs a positive override. Usage is returned with the model result and stored inside the existing append transaction, with no network work introduced into database transactions. No Task 1 `NEEDS_USER_DECISION` or `FOLLOW_UP` remains.
