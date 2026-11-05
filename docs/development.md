# Development

[Home](../README.md) | [繁體中文](development.zh-TW.md) | [Question index](README.md) | [Architecture](package-architecture.md)

This guide is the complete owner for Java/Maven test workflow, test responsibilities and evidence boundaries. It does not own execution architecture contracts or deployment procedures. For repository-specific service and environment guidance, consult [AGENTS.md](../AGENTS.md).

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

This page owns the toolchain, test commands and authorization, test responsibilities and evidence boundaries; it does not own product execution contracts or deployment procedures.

<a id="contents"></a>

## Contents

- [Toolchain and ordinary tests](#toolchain-and-ordinary-tests)
- [PostgreSQL integration](#postgresql-integration)
- [Full-suite commands and authorization](#full-suite-commands-and-authorization)
- [Test responsibilities and locations](#test-responsibilities-and-locations)
- [Integration and live evidence boundaries](#integration-and-live-evidence-boundaries)

## Toolchain and ordinary tests

The project targets Java 21 and uses Maven. The ordinary Surefire suite uses fake model and tool boundaries; it does not require PostgreSQL, a live MCP server, or a model key. Tests keep local HTTP exposure loopback-only. `SlackSdkWebApiTest` starts a fake JDK HTTP server on the loopback address and an ephemeral port; it does not contact Slack. Tests that use Mockito static/final mocking need a normal Java 21 process in which Mockito/Byte Buddy can self-attach.

For a focused ordinary test run, use Surefire's `-Dtest` property with existing classes. These names are listed in [Test responsibilities and locations](#test-responsibilities-and-locations):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress \
  -Dtest=MessageJobContextCompactionTest,ApplicationModulesTest \
  test
```

## PostgreSQL integration

For the local shared PostgreSQL environment, use the checked-in launcher:

```bash
bash scripts/test-postgres-local.sh
```

The launcher selects the restricted test role on loopback port `5432` using the private test configuration; it requires explicit `shared` mode and Java 21. It creates a unique database per integration-test class and cleans up only its own test databases. Missing or invalid configuration fails without a Testcontainers fallback; application databases and the shared PostgreSQL service are not test resources. The launcher accepts no positional arguments and always runs `mvn --batch-mode --no-transfer-progress -Ppostgres-it verify`, including the full ordinary suite and all matching PostgreSQL integration tests. It does not provide focused PostgreSQL test selection.

The private configuration contains explicitly trusted shell-safe assignments. Do not display or source that file, or run the launcher with shell tracing. Follow the [verification guide's local PostgreSQL section](../.agents/skills/deploying-with-omp-gateway/references/verification.md#本機-postgresql-測試與可攜-ci) for provisioning, file and role requirements, safety boundaries, and the separate portable mode.

The private test env is `~/.local/share/local-postgres/session-agent-tests.env`, owned by the current user with mode 600. Use this profile for migrations, persistence, transaction boundaries, history ordering or concurrency, subject to the full-suite authorization below. For an authorized clean gate, run Maven `clean` before the launcher above.

## Full-suite commands and authorization

The commands below run a full suite, not a focused check. Coding agents MUST run them only when the current task explicitly authorizes a full-suite run. The ordinary command runs the complete fake-backed ordinary suite:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

The local shared PostgreSQL gate uses the launcher above. It runs the ordinary suite plus the PostgreSQL integration suite; do not replace it with an unconfigured Maven profile command.

Only when explicitly choosing a portable CI environment should Testcontainers be selected. This mode requires Docker and deliberately removes any inherited shared-database credentials:

```bash
env -u SESSION_AGENT_TEST_POSTGRES_ADMIN_URL \
  -u SESSION_AGENT_TEST_POSTGRES_USERNAME -u SESSION_AGENT_TEST_POSTGRES_PASSWORD \
  SESSION_AGENT_TEST_POSTGRES_MODE=container \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

This portable command also runs the full ordinary suite and all matching PostgreSQL integration tests. Testcontainers is not the default local mode; `mvn test` remains fake-backed and does not require a database.

Keep real-model, deployed MCP, and real Slack verification separate from the fake-backed suite and require explicit authorization. `GoogleModelLiveTest` is gated by `GOOGLE_MODEL_LIVE=true`; do not enable that live model call without authorization. Deployed live acceptance remains outside this repository.

<a id="test-responsibilities-and-locations"></a>

## Test responsibilities and locations

Tests are organized around the behavior boundary they prove. A test package is
not a blanket mirror of the production package tree: tests that need a
package-private SDK seam stay with that seam even when their assertions cover a
cross-layer behavior.

| Boundary | Current test organization |
| --- | --- |
| Domain invariants and provider-neutral records | `ConversationDomainTest`, `ContextUsageEstimatorTest`, `GenericToolSnapshotTest`, and `slack/domain/SlackPermalinkParserTest`; these run without Spring, PostgreSQL, an MCP server, or a model key. |
| Conversation application behavior with local fakes | `MessageServiceTest`, `SessionQueryServiceTest`, `MessageJobContextCompactionTest`, `MessageJobToolExecutionTest`, `MessageJobRecoveryTest`, `MessageJobHandoffTest`, `MessageJobSkillsTest`, and shared `MessageJobTestFixtures`; these cover history admission, purpose-aware reservations, bounded handoff, semantic modes, ordered tools, skill receipt propagation, continuation, claim fencing and recovery. |
| Skill filesystem and tool boundary | `skill/FileSystemSkillLibraryTest` and `SkillToolCatalogTest`; startup metadata, current file/reference reads, UTF-8 and size limits, URI/symlink safety, content hashes and read receipts. |
| Model adapter behavior | `model/adapter/springai/HistoryProjectorTest`, `SpringAiConversationModelTest`, `SpringAiToolCallbackFactoryTest`, `SpringAiMessagePartsHandlerTest`, `GoogleGenAiContinuationTest`, and the explicitly gated `GoogleModelLiveTest`. Ordered continuation tests retain reasoning, per-part signatures, unknown provider parts and media; local fake-HTTP provider tests exercise replay without model quota. |
| MCP adapter behavior | `mcp/adapter/sdk/McpConnectionClientFactoryTest`, `McpConnectionManagerTest`, `McpToolCatalogTest`, `McpToolResultMapperTest`, and `McpConnectionsEndpointTest`. The endpoint test stays in this package because it exercises the SDK adapter's private seam. |
| PostgreSQL and HTTP adapters | `storage/adapter/postgres/PostgresConversationCommitPostgresIT`, `PostgresMessageIntakePostgresIT`, and `PostgresMessageJobPostgresIT`; `web/adapter/http/MessageControllerTest`; and Slack persistence tests described below. |
| Durable generated artifacts | `artifact/ArtifactEncoderTest`, `ArtifactToolCatalogTest`, and `storage/adapter/postgres/ArtifactStorePostgresIT`; bounds, CSV safety, trusted invocation identity, route eligibility, exact actual-observation association, PostgreSQL rollback/lease/restart/replay/isolation, and orphan invisibility. |
| Worker behavior | `worker/application/MessageJobWorkerTest`, covering claim, renewal, processing, retry, limit, and recovery coordination. |
| Slack application and adapters | `slack/application/MessageHandlerTest`, `SlackDeliveryLifecycleTest`, `SlackDeliveryWorkerTest`, `SlackProgressWorkerTest`, `SlackProgressLifecycleTest`, and `SlackSocketLifecycleTest`; `slack/adapter/postgres/SlackDeliveryPostgresIT` and `SlackProgressPostgresIT`; and SDK-seam tests in `slack/adapter/sdk`, including `SlackConfigurationTest`, `PostgresMessageSubmissionPostgresIT`, `SlackSocketShutdownTest`, `SlackBoltSocketClientTest`, and `SlackSdkWebApiTest` (real SDK calls against local fake HTTP, including question permalink identity and update errors). |
| Bootstrap configuration and metrics | `bootstrap/configuration/RuntimeConfigurationTest`, `ApplicationStartupTest`, `RuntimePropertiesTest`, and `LoggingConfigurationTest`; `bootstrap/adapter/metrics/RuntimeObservabilityTest`. |
| Architecture and dependency boundaries | `ApplicationModulesTest` verifies the eleven closed modules, the exact allowed dependency map, and the complete named-interface map. `RuntimeBoundaryTest` verifies provider boundaries, MCP and Slack SDK confinement to their SDK adapters, and the domain/port direction rules for conversation and Slack. |

For focused skill-read checks:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress \
  -Dtest=FileSystemSkillLibraryTest,SkillToolCatalogTest,MessageJobSkillsTest \
  test
```

These tests create generic `demo`/`review` skills and references in JUnit `@TempDir`;
they do not depend on an installed business skill. Conversation cases use fake
model/store boundaries to check that loaded content enters model requests and that
new reads, committed recovery and failed tool reads behave correctly. No database,
live provider or model key is required. They do not prove a real model will
autonomously select the skill or establish its business-result quality.

<a id="integration-and-live-evidence-boundaries"></a>

## Integration and live evidence boundaries

Integrated acceptance also needs a separately launched real Runtime with isolated PostgreSQL, actual
configured-model and Slack SDK adapters pointed at local fake HTTP services, and
synthetic intake through the existing Slack adapter. Capture blocked-model refresh,
multipart delivery, independent progress errors, cancellation and restart/history
counts; keep private evidence outside Git and remove temporary launchers. This
proves local transport integration, not genuine Slack inbound or native client UI.

Continuation/skill acceptance additionally exercises cap-discard/one no-tools wrap,
persisted waiting across restart, all four follow-up decisions, restricted tools,
artifact ownership/release, committed definition/reference/failure notices and exact
acknowledgement without progress noise in history. Local deterministic boundary
responses prove integration, not model semantic quality. Authorized real-model
samples must separately capture actual response model identity, real read-only MCP,
stored skill bytes/hash, Slack API updates and downloaded artifact hashes. Keep
production model selection and its allowance unchanged; select the isolated test
profile before even valid skill-only confirmation. Synthetic ingress and authenticated
browser/human Socket evidence are distinct acceptance boundaries. Business-skill
information coverage, result quality and content revisions belong to external
evaluation, not Runtime's regression suite.
