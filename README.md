# Session Agent Runtime

A standalone, provider-neutral conversation service built with Java 21, Spring Boot,
Spring AI, and PostgreSQL. Runtime owns the conversation loop, durable history,
ordered message jobs, and tool execution—not the business rules of tool providers.

## What it does

- **Durable conversations:** one submitted user message creates one ordered job;
  jobs in the same session run FIFO, while different sessions can run concurrently.
- **Model/tool loop:** load committed context, call the selected model, execute
  admitted tool calls sequentially, and commit their observations atomically.
- **Independent MCP connections:** named streamable-HTTP servers reconnect and
  refresh independently. Tool schemas, arguments, and results remain opaque.
- **Bounded work and continuation:** an exhausted investigation asks whether to
  continue, organize retained evidence, or start a new question. Waiting is not a
  running job; cancellation is cooperative.
- **Local skills:** explicitly configured, operator-owned `SKILL.md` libraries;
  explicit `/skill:name` selection or model-requested reads. No shell execution.
- **Optional Slack integration:** Socket Mode intake, per-question progress,
  durable replies, native Markdown, and Markdown/CSV attachments.

Runtime does not add provider-specific tool DTOs, tool-result caching, or business
validation. Ordinary final answers are plain text without a Runtime-owned schema.

## Documentation

| Need | Reference |
| --- | --- |
| HTTP endpoints, history shapes, cancellation, Slack delivery semantics | [HTTP API and Slack delivery](docs/http-api.md) |
| Modules, dependency rules, execution contracts, checkpoint accounting, test ownership | [Package architecture](docs/package-architecture.md) |
| New host: deployment choices, OAuth login, cross-host access, client tokens, credential rotation | [Gateway deployment and credentials](docs/gateway-deployment.md) |
| Host installation with native OMP Gateway | [Installation](.agents/skills/deploying-with-omp-gateway/references/install.md) |
| Models, MCP, Slack, and trusted Runtime skills | [Configuration](.agents/skills/deploying-with-omp-gateway/references/configuration.md) |
| Update, backup, rollback, Docker alternative, and logs | [Operations](.agents/skills/deploying-with-omp-gateway/references/operations.md) |
| Local checks versus explicitly authorized live acceptance | [Verification](.agents/skills/deploying-with-omp-gateway/references/verification.md) |
| MCP setup and model-to-tool compatibility | [MCP runbook](.agents/skills/deploying-with-omp-gateway/references/mcp.md) |

The repository's [deployment skill](.agents/skills/deploying-with-omp-gateway/SKILL.md)
links these operator guides and non-secret templates. They are deployment
instructions, **not** a library to mount as Runtime business skills.

## Build and test

Prerequisites: Java 21 and Maven. Local PostgreSQL integration tests reuse the
shared loopback service with dedicated private test credentials. Run from the repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

The ordinary suite uses fake model/tool boundaries; it requires neither model
quota nor a live MCP server. For persistence, migration, transaction, or history
changes, run:

```bash
bash scripts/test-postgres-local.sh
```

The launcher requires `~/.local/share/local-postgres/session-agent-tests.env`
(current-user owned, mode600), explicit `shared` mode, and the restricted test
role—not application or cluster-superuser credentials. Each test class owns a
unique disposable database; application databases are not reset. Missing/invalid
configuration fails without container fallback. See [test setup and explicit
portable CI mode](.agents/skills/deploying-with-omp-gateway/references/verification.md#本機-postgresql-測試與可攜-ci).

The build uses Spring AI 2.1.0-RC1, a pre-release. Real model, deployed MCP, and
Slack acceptance are separate, explicitly authorized operations—not ordinary tests.

## Run locally

Choose **one** deployment path; do not start host and container Runtimes on the
same ports or change an existing database's Compose project/volume identity.

### Host Runtime with native OMP Gateway

The preferred deployment is a host Java Runtime, native OMP Auth Gateway, and the
existing standalone Docker PostgreSQL 17 `local-postgres` on `127.0.0.1:5432`.
Its physical legacy volume remains `gamecenter-local-postgres-data`.
Runtime uses `session_agent` owned by the non-superuser `session_agent` role;
GameCenter uses separate `gamecenter_local`/`gamecenter`. Runtime datasource values
come from private `~/.local/share/session-agent-runtime/runtime.env` through
external YAML placeholders. Keep directories700 and secret files600. Missing
credentials are a blocker or require explicitly authorized provisioning, not a
reason to launch another database or use cluster-superuser runtime credentials.
Start/stop the host Runtime with systemd; never stop the shared DB as a repo
lifecycle step or adopt its volume with Compose.

Follow the [installation guide](.agents/skills/deploying-with-omp-gateway/references/install.md)
for a new installation, or the [update procedure](.agents/skills/deploying-with-omp-gateway/references/operations.md#更新-runtime不更新-omp)
for an existing service. Gateway forwards model requests; Java Runtime still owns
sessions, tools, and the agent loop. Gateway does not import its skills or MCP setup.

**Gateway compatibility matters:** unpatched OMP 18.12.6 loses tool `strict: false`.
Use a verified fixed build or a fixed upstream release, recording source revision
and executable checksum. A matching version string or healthy endpoint is not
sufficient; follow the guide's model-to-tool acceptance checks.

### Explicit opt-in standalone Docker alternative

This builds a separate Runtime **and its own PostgreSQL**, not a shared-DB host
operation. `start/restart` must not be used to update the host service; `stop`
takes down the whole Compose project. Do not attach the shared legacy volume.

```bash
./docker/runtime.sh init
# Edit the untracked docker/.env before starting; never commit credentials.
./docker/runtime.sh start
./docker/runtime.sh status
```

Supply the database password and credentials for every enabled model entry.
Bundled `models.yml` selects Google by default; the host Gateway configuration is
separate and typically selects a configured OpenAI-compatible route. Do not assume
that checkout defaults match an installed service.

See [configuration](.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries)
for model entries and [Docker operations](.agents/skills/deploying-with-omp-gateway/references/operations.md#database-contract-and-docker-alternative)
for logging, permissions, lifecycle commands, and retention.

## Minimal HTTP example

With Runtime running, set the loopback URL to its configured port (the deployment
examples use `8090`). Submitting this example consumes model quota:

```bash
BASE_URL=http://127.0.0.1:8090
curl --fail-with-body "$BASE_URL/internal/messages" \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"example","participantId":"local-user","sourceMessageId":"example-1","message":"Hello"}'
```

The `202` receipt returns `sessionId` and `messageJobId`. Substitute them below:

```text
GET /internal/message-jobs/{messageJobId}
GET /internal/sessions/{sessionId}/messages
```

Reuse the session key to continue the conversation. Use source message IDs unique
across HTTP sessions; changing the session key does not reset source identity.
An identical resend returns the original receipt; changed participant/text yields
`409`. `DONE` means processing ended, not necessarily success or Slack delivery;
inspect job failure fields, history, and delivery status as appropriate.

Complete wire shapes, pagination, model selection, cancellation, and delivery
semantics are in the [API reference](docs/http-api.md).

## Skills and generated reports

Skills are disabled until trusted roots are configured. Metadata is scanned at
startup; new reads use current bounded UTF-8 content without following symlinks.
Use `/skill:name question` in HTTP message text, or `@bot /skill:name question` in
Slack. This is message syntax, not a registered Slack slash command.

The shipped [Java MQ inventory skill](runtime-skills/inventory-java-mq/SKILL.md)
is installed separately by copying it into an operator-owned root. It defaults
to common/static consumer settings; environment comparisons require an explicit
request. See [skill configuration](.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用).

**Loaded skill bytes do not prove model compliance.** Generated MQ reports require
manual checks of provenance, coverage, and field mapping.

Report-generation tools are available only with a supported attachment route;
ordinary HTTP jobs have no such route. Saved artifacts are not yet delivered.
Waiting/clarification never releases artifacts, and a later job cannot publish a
previous job's files. See [artifact contracts](docs/package-architecture.md#durable-markdown-reports-and-csv-exports).

## Safety and operational limits

- Keep HTTP loopback-only unless a separate secured access boundary is installed.
  Do not commit credentials, conversation data, or diagnostic evidence.
- Never reset the database as an update step. Back up and assess schema/binary
  compatibility before migration or rollback; switching jars does not undo SQL.
- Cancellation does not forcibly abort remote calls or roll back tool side effects.
  Side-effecting tools must provide idempotency at their adapter/provider boundary.
- A confirmed Slack upload is distinct from native-client rendering. Slack may
  strip a CSV's UTF-8 BOM on download; parsed equality is not byte equality.
- Uncertain Slack finalization is not automatically retried. Preserve evidence
  for reconciliation rather than resubmit the question or reset delivery state.
- Conversation history and generated artifacts have no automatic TTL or deletion.

## Contributing

Start with the [architecture and module boundaries](docs/package-architecture.md)
and [repository guidance](AGENTS.md). Keep provider SDKs inside their adapters,
MCP data opaque, and tool-specific instructions in tool descriptions or business
skills—not the general Runtime prompt. Update the owning reference document when
changing a contract; keep this README an entrypoint rather than a second manual.
