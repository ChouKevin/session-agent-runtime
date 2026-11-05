# Session Agent Runtime

English | [繁體中文](README.zh-TW.md)

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

Start with the [question index](docs/README.md) to go directly to the section that owns your question.

| Need | Reference |
| --- | --- |
| Use Runtime | [HTTP API and Slack delivery](docs/http-api.md) · [Conversation guide](docs/conversations.md) · [Slack guide](docs/slack.md) |
| Deploy and operate | [Gateway deployment and credentials](docs/gateway-deployment.en.md) · [Configuration guide](docs/configuration.md) · [Operations guide](docs/operations.md) |
| Develop and verify | [Package modules and dependency boundaries](docs/package-architecture.md) · [Development guide: checks, PostgreSQL modes, test ownership, and authorization](docs/development.md) · [Repository guidance](AGENTS.md) |

The repository's [deployment skill](.agents/skills/deploying-with-omp-gateway/SKILL.md)
links these operator guides and non-secret templates. They are deployment
instructions, **not** a library to mount as Runtime business skills.

## Build and test

Requires Java 21 and Maven. The ordinary suite uses fake model/tool boundaries; it
needs no PostgreSQL, model credentials, or quota, and contacts no live MCP or Slack
service. The [development guide](docs/development.md) owns focused/full-suite
commands, private PostgreSQL test setup, and test modes. Full-suite runs and live
model, deployed-MCP, or Slack acceptance require explicit authorization for the
current task and scope; they are not ordinary tests. Spring AI is pinned to a
release candidate; see the [configuration guide](docs/configuration.md) for the
version and adapter constraints.

## Run locally

Choose **one** deployment path. Keep host and container Runtimes off the same ports, and do not change an existing database's Compose project or volume identity.

### Host Runtime with native OMP Gateway

The preferred path is a host Java Runtime, native OMP Auth Gateway, and an
existing compatible shared PostgreSQL database. For installation, existing-service
updates, and deployment-specific roles, volume identity, private settings, and
lifecycle, follow the [Operations guide](docs/operations.md) and its linked
runbooks.

Missing database, application role, or credentials is a blocker: stop or obtain
explicit provisioning approval. Do not start another database, give Runtime
cluster-superuser credentials, or attach the shared volume to Compose; stopping the
shared database is not a Runtime lifecycle step. Gateway forwards model requests;
Runtime owns sessions, tools, and the agent loop; it does not inherit Gateway
skills or MCP settings.

**Gateway compatibility matters:** unpatched OMP 18.12.6 loses tool `strict: false`.
Use a verified fixed build or a fixed upstream release; a matching version string
or healthy endpoint does not prove compatibility. Follow the [deployment
runbook's gateway compatibility and provenance requirements](.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries).

### Explicit opt-in standalone Docker alternative

This explicit opt-in deploys a separate Runtime and its own PostgreSQL, not the
host service. Never use its `start`/`restart` to operate or update the host service,
run it beside a host instance on the same ports, or reuse the shared volume; `stop`
takes down the whole Compose project. Keep credentials out of committed files. See
the [Operations guide](docs/operations.md) for commands, permissions, logs, and
retention.

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

Users supply and maintain their own skills outside this repository. Follow the
[skill definition and installation guide](docs/configuration.md#skill-definition)
to add a `SKILL.md` under an operator-managed root.

Runtime owns discovery, bounded reads, committed history and replay—not business
instructions. Whether a skill gathers sufficient information or produces correct
results must be evaluated externally; its owner updates the content accordingly.

Report-generation tools are available only with a supported attachment route;
ordinary HTTP jobs have no such route. Saved artifacts are not yet delivered.
Waiting/clarification never releases artifacts, and a later job cannot publish a
previous job's files. See [artifact contracts](docs/architecture/artifacts.md#route-and-creation-result).

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

The README and topic guides provide language links. Update both language versions
when changing their contracts; API paths, configuration keys, and example values
must agree. Existing operator runbooks retain their own language and canonical
URLs.
