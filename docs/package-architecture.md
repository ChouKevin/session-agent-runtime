# Current package architecture

This guide describes the production packages that exist in the repository today.
The source root is `src/main/java/com/java/system/sessionagent/`. The nine
top-level packages below are the closed Spring Modulith modules. Subpackages
express the role of the code that is present now; a module does not gain an
empty layer merely to complete a pattern.

## Modules and boundaries

| Module and current packages | Responsibility and boundary |
| --- | --- |
| `conversation` — `domain`, `application`, `port/in`, `port/out` | Owns session, message, job, history, context, and model/tool loop rules. `domain` holds provider-neutral state and invariants; `application` coordinates intake, queries, and message-job processing; `port/in` holds contracts called by adapters and workers; `port/out` holds model, storage, and telemetry contracts implemented outside the module. |
| `tool` — `domain`, `port` | Defines generic tool names, definitions, snapshots, calls, executors, and outputs. Tool names, schemas, arguments, and results remain provider data; this module does not know an MCP or model SDK. |
| `model` — `adapter/springai` | Maps the provider-neutral conversation model contract to Spring AI and Google GenAI, including prompts, history projection, tool callbacks, usage, and continuation data. Provider types stay inside this adapter. |
| `mcp` — `domain`, `configuration`, `adapter/sdk`, `adapter/actuator` | Owns named MCP connection state and properties, SDK connection lifecycle, discovery, tool routing/adaptation, refresh/reconnect, and safe actuator diagnostics. Raw MCP metadata, schemas, and results do not become Runtime DTOs. |
| `storage` — `adapter/postgres` | Implements conversation persistence with PostgreSQL, Flyway-backed tables, MyBatis mappers, UUID mapping, ordered history, job claims, and atomic history writes. |
| `web` — `adapter/http` | Adapts HTTP requests and responses for message intake, job polling, session history, validation, and error handling. MCP diagnostics are owned by `mcp/adapter/actuator`. |
| `worker` — `application`, `configuration` | Polls queued message jobs, claims and renews ownership, invokes processing, reports completion/retry/ownership-loss/recovery outcomes, and binds worker properties. |
| `slack` — `domain`, `application`, `port/out`, `adapter/sdk`, `adapter/postgres`, `configuration` | Handles Slack intake, filtering, delivery lifecycle, Socket Mode, Slack SDK calls, and its own idempotency, thread binding, delivery persistence, and properties. Provider details stay inside the owning adapter. |
| `bootstrap` — `configuration`, `adapter/metrics` | Composes the application through Spring configuration and supplies the Micrometer telemetry adapter. Bootstrap has no exported named interfaces; composition classes are internal to startup wiring. |

The module `package-info.java` files are the source of truth for the closed
dependency allowlist and the `NamedInterface` annotations are the source of
truth for exports. `module :: named-interface` means that the target module
exposes a named interface such as `conversation :: port.out`; a bare module name
such as `web` means the target module's unnamed/root interface. An allowed
dependency is a ceiling, not evidence that code currently uses it.

## Named interfaces and dependency ceiling

The complete named-interface map is intentionally kept in one assertion in
`ApplicationModulesTest`. These are the only named exports:

| Module | Named interfaces |
| --- | --- |
| `tool` | `domain`; `port` |
| `conversation` | `domain`; `application`; `port.in`; `port.out` |
| `model` | `adapter.springai` |
| `mcp` | `configuration`; `adapter.sdk`; `adapter.actuator` |
| `storage` | `adapter.postgres` |
| `web` | none |
| `worker` | `application`; `configuration` |
| `slack` | `domain`; `application`; `port.out`; `adapter.sdk`; `adapter.postgres`; `configuration` |
| `bootstrap` | none |

The exact allowed dependency map and the currently used direct production
dependencies are shown together below. Same-module imports are omitted. The
unused allowances are deliberate: `web` may use `tool :: domain`, and
`bootstrap` may use the bare `web` module. Every other allowance is currently
used by production code.

| Module | Allowed dependencies | Current direct production dependencies |
| --- | --- | --- |
| `tool` | none | none |
| `conversation` | `tool :: domain`; `tool :: port` | `tool :: domain`; `tool :: port` |
| `model` | `conversation :: domain`; `conversation :: port.out`; `tool :: domain`; `tool :: port` | `conversation :: domain`; `conversation :: port.out`; `tool :: domain`; `tool :: port` |
| `mcp` | `tool :: port` | `tool :: port` |
| `storage` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `tool :: domain` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `tool :: domain` |
| `web` | `conversation :: domain`; `conversation :: port.in`; `tool :: domain` | `conversation :: domain`; `conversation :: port.in` |
| `worker` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out` |
| `slack` | `conversation :: domain`; `conversation :: port.in` | `conversation :: domain`; `conversation :: port.in` |
| `bootstrap` | `tool :: port`; `mcp :: configuration`; `mcp :: adapter.sdk`; `mcp :: adapter.actuator`; `conversation :: application`; `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `model :: adapter.springai`; `storage :: adapter.postgres`; `web`; `worker :: application`; `worker :: configuration`; `slack :: domain`; `slack :: application`; `slack :: port.out`; `slack :: adapter.sdk`; `slack :: adapter.postgres`; `slack :: configuration` | `tool :: port`; `mcp :: configuration`; `mcp :: adapter.sdk`; `mcp :: adapter.actuator`; `conversation :: application`; `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `model :: adapter.springai`; `storage :: adapter.postgres`; `worker :: application`; `worker :: configuration`; `slack :: domain`; `slack :: application`; `slack :: port.out`; `slack :: adapter.sdk`; `slack :: adapter.postgres`; `slack :: configuration` |

Keep provider SDKs inside their owning adapters. The conversation domain,
application, ports, generic tool module, storage, web, and worker remain free of
model, MCP, Google, and Slack SDK types. MCP connections remain independent: an
unavailable named server must not hide tools from another available server or
prevent startup. Tool execution remains sequential in the model-provided order,
with opaque observations passed back through the generic tool and conversation
contracts.

## Test responsibilities and locations

Tests are organized around the behavior boundary they prove. A test package is
not a blanket mirror of the production package tree: tests that need a
package-private SDK seam stay with that seam even when their assertions cover a
cross-layer behavior.

| Boundary | Current test organization |
| --- | --- |
| Domain invariants and provider-neutral records | `ConversationDomainTest`, `ContextUsageEstimatorTest`, `GenericToolSnapshotTest`, and `slack/domain/SlackPermalinkParserTest`; these run without Spring, PostgreSQL, an MCP server, or a model key. |
| Conversation application behavior with local fakes | `MessageServiceTest`, `SessionQueryServiceTest`, `MessageJobContextCompactionTest`, `MessageJobToolExecutionTest`, `MessageJobRecoveryTest`, and shared `MessageJobTestFixtures`; these cover history loading, model-call reservation, compaction, ordered tool execution and ordinary tool failures, continuation, claim fencing, retry, and recovery. |
| Model adapter behavior | `model/adapter/springai/HistoryProjectorTest`, `SystemPromptTest`, `SpringAiConversationModelTest`, `SpringAiToolCallbackFactoryTest`, `GoogleGenAiThoughtSignatureHandlerTest`, and the explicitly gated `GoogleModelLiveTest`. |
| MCP adapter behavior | `mcp/adapter/sdk/McpConnectionClientFactoryTest`, `McpConnectionManagerTest`, `McpToolCatalogTest`, `McpToolResultMapperTest`, and `McpConnectionsEndpointTest`. The endpoint test stays in this package because it exercises the SDK adapter's private seam. |
| PostgreSQL and HTTP adapters | `storage/adapter/postgres/PostgresConversationCommitPostgresIT`, `PostgresMessageIntakePostgresIT`, and `PostgresMessageJobPostgresIT`; `web/adapter/http/MessageControllerTest`; and Slack persistence tests described below. |
| Worker behavior | `worker/application/MessageJobWorkerTest`, covering claim, renewal, processing, retry, limit, and recovery coordination. |
| Slack application and adapters | `slack/application/MessageHandlerTest`, `SlackDeliveryLifecycleTest`, `SlackDeliveryWorkerTest`, and `SlackSocketLifecycleTest`; `slack/adapter/postgres/SlackDeliveryPostgresIT`; and SDK-seam tests in `slack/adapter/sdk`, including `SlackConfigurationTest`, `PostgresMessageSubmissionPostgresIT`, `SlackSocketShutdownTest`, `SlackBoltSocketClientTest`, and `SlackSdkWebApiTest`. |
| Bootstrap configuration and metrics | `bootstrap/configuration/RuntimeConfigurationTest`, `ApplicationStartupTest`, `RuntimePropertiesTest`, and `LoggingConfigurationTest`; `bootstrap/adapter/metrics/RuntimeObservabilityTest`. |
| Architecture and dependency boundaries | `ApplicationModulesTest` verifies the nine closed modules, the exact allowed dependency map, and the complete named-interface map. `RuntimeBoundaryTest` verifies provider boundaries, MCP and Slack SDK confinement to their SDK adapters, and the domain/port direction rules for conversation and Slack. |

The ordinary suite uses local fake model/tool boundaries and keeps local HTTP
exposure loopback-only. `SlackSdkWebApiTest` starts a fake JDK HTTP server on
the loopback address and an ephemeral port; it does not contact Slack. Tests
that use Mockito static/final mocking need a normal Java 21 process in which
Mockito/Byte Buddy can self-attach. Run the ordinary suite with Java 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

Run the PostgreSQL profile when checking migrations, persistence, transaction
boundaries, history ordering, or concurrency:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

Neither command should require a live model or MCP server. Keep live model,
deployed MCP, and real Slack behavior explicitly authorized and outside the
default fake-backed suite.
