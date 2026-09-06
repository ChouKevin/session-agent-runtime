# Task 7 report — LOG-1 safe JSON logs

## BDD scenario and evidence

| Scenario | Given / When / Then | Verification boundary | RED | GREEN |
| --- | --- | --- | --- | --- |
| `LOG-1` dual safe JSON paths | Given a Runtime lifecycle event with safe correlation IDs, when console and rolling-file appenders encode it, then each output is one equivalent parseable JSON line and no throwable/raw sample content is serialized. | Logging configuration contract in `LoggingConfigurationTest` | The focused logging test failed because `/logback-spring.xml` did not exist. | The test captures equivalent `JsonEncoder` output for the console and file schemas; checks the actual configuration contains both appenders, the fixed active file, required rollover values, and disabled throwable output. |
| Supporting rotation/mount contract | Given Compose is rendered with the required database password, when its static contract is evaluated, then it retains loopback HTTP and Docker rotation while supplying the writable host log bind mount and Runtime rollover values. | `src/test/shell/docker-contract-test.sh` | The prior Compose contract forbade host logs and no Runtime logback policy existed. | The static Docker contract passes for the bind target, source, Docker `100m` × `5`, Runtime `100MB`/7-day/`500MB`, and stable UID 10001 image ownership. |

REFACTOR: lifecycle logging stays at the existing component boundaries via SLF4J structured key-values. A proposed common logger in `bootstrap` was removed because it would have made closed modules depend on `bootstrap`; no module or provider boundary changed.

## JSON and operator contract

- Both appenders emit Logback JSON including timestamp, level, logger, message, and `kvpList`; Runtime-owned events use `event` plus only safe structured values.
- Correlation/outcome field set by lifecycle stage: `sessionId`, `messageJobId`, `callOrdinal`, safe Slack IDs, `deliveryId`, model route/ID, duration and token counts, context capacity/ratio, tool name/call ID, attempt/retry delay, component/state, outcome, and safe failure category.
- Runtime omits all encodable throwable, argument, MDC, marker, context, thread, sequence, and nanosecond fields. Lifecycle calls do not attach exception objects or raw exception messages.
- Active file: `/app/logs/session-agent-runtime.log`; archives: `/app/logs/session-agent-runtime.YYYY-MM-DD.N.log.gz`; rolls at `100MB`, retains `7` days, and caps archives at `500MB`.
- Compose mount: `${SESSION_AGENT_LOG_DIR:-../logs}:/app/logs`. On supported Linux POC hosts prepare it before start:

```bash
mkdir -p logs
chown 10001:10001 logs
export SESSION_AGENT_LOG_DIR=/srv/session-agent-runtime/logs  # optional override
docker compose -f docker/compose.yaml logs -f session-agent-runtime
tail -F "${SESSION_AGENT_LOG_DIR:-logs}/session-agent-runtime.log"
```

Docker `json-file` rotation is independently retained at `100m` and `5` files. The ignored stable host filename is ready for a future Filebeat or Elastic Agent tail input; no shipper or ELK component is deployed.

## Lifecycle coverage

- Slack: socket connection state, inbound classification, and permalink/session resolution.
- Message jobs: claim, complete/ownership loss, unexpected failure, lease/storage recovery, and scheduled retry.
- Conversation: model request/response/failure, model usage, tool execution, proactive/overflow compaction threshold/start/success/failure.
- Delivery: recovery scan, attempt, retry, sent, and terminal failure.
- MCP: connection component state and safe diagnostic code.

No lifecycle event includes conversation or summary text, raw Slack payload/error, Slack/model token/header, MCP URL/header, tool argument/result, provider continuation metadata, exception object, stack trace, or raw exception message. Existing actuator diagnostic sanitation is unchanged.

## Files and verification

Changed: `.gitignore`, `Dockerfile`, `docker/compose.yaml`, `docker/.env.example`, `README.md`, `logback-spring.xml`, the existing conversation/worker/Slack/MCP lifecycle owners, `LoggingConfigurationTest`, and Docker contract test.

- RED: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=LoggingConfigurationTest test` failed as expected because the configuration resource was absent.
- GREEN focused: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=RuntimeObservabilityTest,LoggingConfigurationTest test` — passed, 3 tests, 0 failures/errors.
- Module boundary: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Dtest=ApplicationModulesTest test` — passed, 4 tests, 0 failures/errors.
- Docker static contract: `./docker/test-docker-contract.sh` — passed.
- Full fake-backed suite: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress test` — passed after the final code update.
- `git diff --check` — passed.
- Compose runtime smoke was not executed: it may build/pull images and needs external credentials/database startup, so it was outside the no-download/no-external-effects authorization. No live Slack or model call was made.

Security scan over changed logging/configuration lines for token, header, authorization, key, secret, conversation-content, and raw-failure markers found only intentional environment-variable names, operator prose, negative test samples, and the pre-existing fixed `contract-token` Compose test fixture; no real credential or forbidden runtime log field was added.

## Commit, decisions, and follow-up

Commit: `feat(logging): add safe JSON lifecycle logs` (the final commit SHA is reported in handoff).

NEEDS_USER_DECISION: none.

FOLLOW_UP: run the explicitly authorized Compose smoke on a prepared Linux host to prove actual bind-mount ownership and host-readable active-file creation. The current static contract and documentation cannot replace that host-permission proof.

Risk: external libraries can still log independently of Runtime-owned lifecycle events. The Runtime appender disables throwable serialization and this task deliberately does not alter third-party logging behavior.
