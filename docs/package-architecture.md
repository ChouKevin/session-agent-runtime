# Current package architecture

[Home](../README.md) | [繁體中文](package-architecture.zh-TW.md) | [Question index](README.md)

This guide describes the production packages that exist in the repository today.
The source root is `src/main/java/com/java/system/sessionagent/`. The eleven
top-level packages below are the closed Spring Modulith modules. Subpackages
express the role of the code that is present now; a module does not gain an
empty layer merely to complete a pattern.


<a id="purpose-and-scope"></a>

## Purpose and non-purpose

This page owns modules, named interfaces and dependency ceilings. It is not the detailed owner for execution, skills, artifacts, Slack delivery or test commands; use the topic links below.

<a id="contents"></a>

## Contents

- [Modules and boundaries](#modules-and-boundaries)
- [Named interfaces and dependency ceiling](#named-interfaces-and-dependency-ceiling)
- [Conversation execution](architecture/conversation-execution.md)
- [History and usage](architecture/history-and-usage.md)
- [Shared PostgreSQL interface](#shared-postgresql-interface)
- [Provider and module isolation](#provider-and-module-isolation)
- [Skills](architecture/skills.md)
- [Artifacts](architecture/artifacts.md)
- [Slack](architecture/slack-delivery.md)
- [Test responsibilities and locations](development.md#test-responsibilities-and-locations)

## Modules and boundaries

| Module and current packages | Responsibility and boundary |
| --- | --- |
| `conversation` — `domain`, `application`, `port/in`, `port/out` | Owns session, message, job, history, context, and model/tool loop rules. `domain` holds provider-neutral state and invariants; `application` coordinates intake, queries, and message-job processing; `port/in` holds contracts called by adapters and workers; `port/out` holds model, storage, and telemetry contracts implemented outside the module. |
| `tool` — `domain`, `port` | Defines generic tool names, definitions, snapshots, calls, executors, and outputs. Tool names, schemas, arguments, and results remain provider data; this module does not know an MCP or model SDK. |
| `skill` — `domain`, `port`, `configuration`, `adapter/filesystem`, `adapter/tool` | Owns startup-only skill metadata, bounded current-file reads, and the provider-neutral `readSkill` tool. Bootstrap owns the library lifecycle and composes skill and MCP leaves into the sole catalog. |
| `artifact` — `application`, `port` | Owns bounded first-party Markdown/CSV encoding, route-gated creation tools, and durable artifact storage contracts; no provider SDK or conversation dependency. |
| `model` — `adapter/springai` | Maps the provider-neutral conversation model contract to configured Spring AI OpenAI and Google GenAI adapters, including prompts, history projection, tool callbacks, usage, and continuation data. Provider types stay inside this adapter. |
| `mcp` — `domain`, `configuration`, `adapter/sdk`, `adapter/actuator` | Owns named MCP connection state and properties, SDK connection lifecycle, discovery, tool routing/adaptation, refresh/reconnect, and safe actuator diagnostics. Raw MCP metadata, schemas, and results do not become Runtime DTOs. |
| `storage` — `adapter/postgres`, `adapter/postgres/jdbc` | Implements conversation persistence with PostgreSQL, Flyway-backed tables, MyBatis mappers, ordered history, job claims, and atomic history writes. The narrow JDBC export owns shared intake admission and UUID mapping for PostgreSQL adapters. |
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
| `skill` | `domain`; `port`; `configuration`; `adapter.filesystem`; `adapter.tool` |
| `artifact` | `application`; `port` |
| `conversation` | `domain`; `application`; `port.in`; `port.out` |
| `model` | `adapter.springai` |
| `mcp` | `configuration`; `adapter.sdk`; `adapter.actuator` |
| `storage` | `adapter.postgres`; `adapter.postgres.jdbc` |
| `web` | none |
| `worker` | `application`; `configuration` |
| `slack` | `domain`; `application`; `port.out`; `adapter.sdk`; `adapter.postgres`; `configuration` |
| `bootstrap` | none |

The complete allowed dependency map is below; the delta column lists unused allowances instead of duplicating the actual map. Same-module imports are omitted. The
unused allowances are deliberate: `artifact` may use `tool :: domain`,
`skill` may use `tool :: domain`, and `bootstrap` may use the bare `web` module.
Every other allowance is currently used by production code.

| Module | Allowed dependencies | Unused allowance (actual = allowed minus this column) |
| --- | --- | --- |
| `tool` | none | none |
| `skill` | `tool :: domain`; `tool :: port` | `tool :: domain` |
| `artifact` | `tool :: domain`; `tool :: port` | `tool :: domain` |
| `conversation` | `tool :: domain`; `tool :: port`; `skill :: domain`; `skill :: port` | none |
| `model` | `conversation :: domain`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain` | none |
| `mcp` | `tool :: port` | none |
| `storage` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `tool :: domain`; `tool :: port`; `skill :: domain`; `artifact :: port` | none |
| `web` | `conversation :: domain`; `conversation :: port.in`; `tool :: domain`; `slack :: domain`; `slack :: port.out`; `skill :: domain` | none |
| `worker` | `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out` | none |
| `slack` | `conversation :: domain`; `conversation :: port.in`; `storage :: adapter.postgres.jdbc`; `artifact :: port` | none |
| `bootstrap` | `tool :: port`; `artifact :: application`; `artifact :: port`; `skill :: port`; `skill :: configuration`; `skill :: adapter.filesystem`; `skill :: adapter.tool`; `mcp :: configuration`; `mcp :: adapter.sdk`; `mcp :: adapter.actuator`; `conversation :: application`; `conversation :: domain`; `conversation :: port.in`; `conversation :: port.out`; `model :: adapter.springai`; `storage :: adapter.postgres`; `storage :: adapter.postgres.jdbc`; `web`; `worker :: application`; `worker :: configuration`; `slack :: domain`; `slack :: application`; `slack :: port.out`; `slack :: adapter.sdk`; `slack :: adapter.postgres`; `slack :: configuration` | `web` |

<a id="shared-postgresql-interface"></a>

## Shared PostgreSQL interface

`storage :: adapter.postgres.jdbc` exposes only shared PostgreSQL integration
components: `PostgresIntakeCapacity` and `PostgresUuidTypeHandler`. Slack depends
on this narrow export, not the conversation mapper/store export
`storage :: adapter.postgres`. Both direct-construction MyBatis factories and
bootstrap's MyBatis configuration register the same UUID handler; Slack has no
separate UUID implementation.

<a id="provider-and-module-isolation"></a>

## Provider and module isolation

Keep provider SDKs inside their owning adapters and bootstrap composition.
Google and OpenAI SDK/provider-specific Spring AI types are permitted only in
`model.adapter.springai` and `bootstrap`, not elsewhere in the model package.
`RuntimeBoundaryTest` enforces this narrow boundary for both providers. The
conversation domain, application, ports, generic tool module, storage, web, and
worker remain free of model, MCP, Google, OpenAI, and Slack SDK types.
MCP connections remain independent: an
unavailable named server must not hide tools from another available server or
prevent startup. Tool execution remains sequential in the model-provided order,
with opaque observations passed back through the generic tool and conversation
contracts.

## Audit history and model admission

Detailed owner: [Audit history and model admission](architecture/history-and-usage.md#audit-and-context-admission).

## Test responsibilities and locations

Detailed owner: [Test responsibilities and locations](development.md#test-responsibilities-and-locations).

## Conversation execution contract

Detailed owner: [Conversation execution contract](architecture/conversation-execution.md#ordered-model-and-tool-loop).

### Durable Markdown reports and CSV exports

Detailed owner: [Durable Markdown reports and CSV exports](architecture/artifacts.md#route-and-creation-result).

## Limits, retries, and recovery

Detailed owner: [Limits, retries, and recovery](architecture/conversation-execution.md#allowance-and-wrap-up).

## Slack internal contracts

Detailed owner: [Slack internal contracts](architecture/slack-delivery.md).

### Admission and stop transactions

Detailed owner: [Admission and stop transactions](architecture/slack-delivery.md#admission-and-stop-transactions).

### Progress ownership and reconciliation

Detailed owner: [Progress ownership and reconciliation](architecture/slack-delivery.md#progress-ownership-and-reconciliation).

### Prepared deliveries and upload transport

Detailed owner: [Prepared deliveries and upload transport](architecture/slack-delivery.md#prepared-deliveries-and-upload-transport).

For skill discovery, reads, receipts and recovery, see [Skills](architecture/skills.md).
