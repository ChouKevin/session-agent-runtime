# Operations

[Home](../README.md) | [繁體中文](operations.zh-TW.md) | [Question index](README.md)

Use the systemd host deployment with an existing compatible shared PostgreSQL database as the preferred deployment. The repository's Docker Compose stack is a separate, explicit opt-in full-stack deployment. Do not use Compose helper commands to update, restart, or replace a host-mode service.

## Recommended: systemd host deployment

The host deployment runs Runtime as a systemd user service and uses an already managed, compatible PostgreSQL database with a restricted application owner. The database is shared infrastructure and remains independent of Runtime or gateway service lifecycle. OMP Gateway/broker selection is separate: choose a local or shared gateway using the [gateway deployment guide](gateway-deployment.en.md).

For a new host, follow the [host installation runbook](../.agents/skills/deploying-with-omp-gateway/references/install.md); for an existing deployment, use the [operations runbook](../.agents/skills/deploying-with-omp-gateway/references/operations.md). These procedures identify the active units, artifact, configuration and credential sources, then cover draining, backup, upgrade, rollback, and service lifecycle. Do not reset or recreate a shared database, start a second PostgreSQL container, reuse a Compose volume, or give Runtime a cluster-superuser credential. If the compatible database, application role, or its credential is missing, stop and report the blocker or obtain explicit provisioning authorization; do not silently switch databases or create one.

Host service configuration is private and may differ from the checked-in defaults. The checked-in `application.yml` binds the server to `127.0.0.1` and defaults the datasource URL to `jdbc:postgresql://localhost:5432/session_agent`; confirm the actual service's effective configuration locally without printing secrets. Host logs go to the systemd journal; use `journalctl --user -u session-agent-runtime`. Journal retention is controlled by host policy, not the Docker rotation settings below.

## Explicit opt-in: standalone Docker Compose

Choose this only when a separate full-stack deployment is intended. The helper operates on `docker/compose.yaml` and `docker/.env`, starts its own Runtime and PostgreSQL service, publishes the Runtime port on loopback, and stores its database in a project-scoped `session-agent-postgres` volume. It does not use the host's shared PostgreSQL or `gamecenter-local-postgres-data`. Do not run this as a host deployment update or alongside another instance on the same ports.

Run from the repository root. `init` creates `docker/.env` from its example only if it does not already exist; it does not overwrite existing settings. Provide the required `SESSION_AGENT_POSTGRES_PASSWORD`, credentials for configured models, and any optional Slack, MCP, port, or log settings in the untracked file. The helper may ask for `sudo` only when preparing the host log directory.

```bash
./docker/runtime.sh init
./docker/runtime.sh start
./docker/runtime.sh status
./docker/runtime.sh logs
./docker/runtime.sh restart
./docker/runtime.sh stop
```

`start` validates Compose, builds the image, and starts the complete project detached. `restart` takes down the entire project, rebuilds, and starts it again; use it only for this standalone Compose deployment, not for the systemd host service. `stop` takes down the project without deleting its PostgreSQL named volume. The helper has no data-reset command; never use `down --volumes` or remove the volume as part of routine operation.

Runtime writes one JSON object per line to Compose logs and a rolling host file. The active file is `session-agent-runtime.log`; archives are `session-agent-runtime.YYYY-MM-DD.N.log.gz`. Runtime rolls the file at 100 MB, retains seven days, and caps archives at 500 MB. Docker's independent `json-file` driver retains up to five files of 100 MB each. Follow the host file from the repository root with:

```bash
tail -F logs/session-agent-runtime.log
```

When `SESSION_AGENT_LOG_DIR` is set in `docker/.env`, use that absolute host path instead. The log directory is Git-ignored. Lifecycle events contain safe event, correlation, and outcome fields; Runtime does not emit conversation text, Slack/model credentials, tool arguments or results, provider continuation data, or raw exception details in lifecycle events. This POC does not deploy ELK or a log shipper.

## Data and change safety

Conversations and tool results are retained indefinitely. There is no automatic TTL, cleanup, backup schedule, or recovery workflow. Before an upgrade, use the host [operations runbook](../.agents/skills/deploying-with-omp-gateway/references/operations.md) to identify the deployment, drain work, preserve the current artifact/configuration, and assess schema compatibility. Switching back to an older JAR does not undo database migrations; restoration and any possible data loss require the runbook's safeguards and authorization.

For model, Slack, skills, and MCP settings, see [configuration](configuration.md) and the canonical [deployment configuration](../.agents/skills/deploying-with-omp-gateway/references/configuration.md) and [MCP](../.agents/skills/deploying-with-omp-gateway/references/mcp.md) runbooks. Do not commit local credentials or treat a successful health check as proof of model, MCP, or Slack end-to-end acceptance.
