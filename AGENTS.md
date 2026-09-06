# Session Agent Runtime Repository Guide

## Purpose

This repository is a provider-neutral conversation runtime. It stores complete
session history, orders submitted message jobs, calls a chat model, runs generic MCP
tools, and persists state in PostgreSQL.

The runtime does not own the meaning, schema, or business rules of any tool provider.

## Hard boundaries

- Keep the core provider-neutral. Provider SDK details belong in model adapters and
  must not leak into conversation, tool, storage, or web contracts.
- Treat MCP tool names, input schemas, arguments, and structured results as opaque
  provider data. Do not add provider-specific DTOs, tool names, repository rules, or
  revision rules to Runtime.
- Keep MCP connections independent. One unavailable server must not prevent startup
  or hide tools from another available server.
- A user message creates one ordered job. Load the complete committed session history
  before each model turn and preserve message-job ordering.
- Execute a model-provided tool batch sequentially in its given order. Atomically
  append one `ASSISTANT_TOOL_CALLS` event followed by all matching `TOOL` events.
- Persist optional assistant text returned with tool calls. Final assistant text is
  plain text and has no Runtime-owned JSON schema or citation format.
- Record an ordinary tool failure as that tool's observation and continue later calls
  in the same batch. Do not invent provider-specific retry or fallback rules.
- Preserve model-call counting before each real provider request. Spring AI retries
  remain disabled; job retries own transient provider recovery.
- Do not add Runtime tool-result caching or deduplication. A future side-effecting
  tool must be idempotent at its adapter or provider boundary.
- The current database contract is a fresh schema. Do not add compatibility code for
  removed pre-release schemas unless a new requirement explicitly asks for it.

## Package map

Production packages are under
`src/main/java/com/java/system/sessionagent/`; the paths below are relative to
that directory.

- `conversation/domain`: session, message, job, context, and ordered history rules.
- `conversation/application`: conversation intake/query services and model/tool loop coordination.
- `conversation/port/in`: contracts called by HTTP, Slack, and worker adapters.
- `conversation/port/out`: model, storage, and telemetry contracts implemented by adapters.
- `model/adapter/springai`: Spring AI model mapping, history projection, prompt, usage, and continuation adapter.
- `mcp/domain`: provider-neutral MCP connection state.
- `mcp/configuration`: named MCP connection properties and validation.
- `mcp/adapter/sdk`: MCP SDK clients, connection lifecycle, discovery, tool routing, and result mapping.
- `mcp/adapter/actuator`: safe MCP connection diagnostics endpoint.
- `tool/domain` and `tool/port`: generic provider-neutral tool names, definitions, calls, snapshots, and observations.
- `storage/adapter/postgres`: PostgreSQL conversation adapters, MyBatis mappers, and UUID mapping; Flyway migrations remain under `src/main/resources/db/migration`.
- `web/adapter/http`: message, job, session-history, and HTTP error endpoints; MCP actuator diagnostics remain in `mcp/adapter/actuator`.
- `worker/application` and `worker/configuration`: queued job claim, renewal, processing, retry, limit, recovery coordination, and worker properties.
- `slack/domain`, `slack/application`, `slack/port/out`, `slack/adapter/sdk`, `slack/adapter/postgres`, and `slack/configuration`: Slack intake, delivery, Socket Mode lifecycle, provider adapters, persistence, and properties.
- `bootstrap/configuration`: Spring composition and runtime configuration.
- `bootstrap/adapter/metrics`: Micrometer conversation telemetry adapter.

## Change guide

- Conversation behavior: start from domain and application contracts; verify stored
  event order and the public history result.
- Model provider change: keep provider continuation data inside the adapter boundary
  and map every provider response into the provider-neutral model result.
- MCP change: preserve raw SDK tool metadata/results, connection isolation, client
  lifetime handling, timeouts, and safe diagnostics.
- Storage change: add a Flyway migration and verify atomic history writes with the
  PostgreSQL integration profile.
- HTTP change: preserve asynchronous message submission, job polling, and complete
  session-history retrieval.
- Prompt change: keep the prompt general. Tool-specific instructions belong to each
  MCP tool description, not the Runtime system prompt.

## Verification

Use Java 21. Run the ordinary fake-backed suite first; it must not need PostgreSQL, a
live MCP server, or a model key:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

Run PostgreSQL integration tests for migrations, repositories, transactions, or
history ordering changes:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

Do not add a default test that consumes model quota. Real-model and deployed MCP
behavior must remain explicitly authorized integration work.

## Security and data

- Never commit model keys, MCP headers, database passwords, conversation data, or
  diagnostic evidence.
- Do not expose MCP URLs, headers, tokens, raw failures, or provider exception details
  through actuator diagnostics.
- Preserve loopback-only defaults for local HTTP exposure and validate all public
  request inputs at the web boundary.
