# Gateway Deployment and Credential Management

[Home](../README.md) | [繁體中文](gateway-deployment.md) | [Question index](README.md)

This page is the entry point for deploying to a new host: choose a deployment mode, then follow the existing runbooks for installation, configuration, and verification. The Java Runtime does not manage OAuth, start a gateway, or read the OMP credential database.

## 1. Choose a deployment mode

| Mode | Does a new Runtime host need OMP OAuth login? | Procedure |
| --- | --- | --- |
| Standalone Runtime, gateway, and broker on the same host | The broker needs a valid provider credential; the operator signs in on a new installation | Login below → [standalone installation](../.agents/skills/deploying-with-omp-gateway/references/install.md) |
| Runtime-only on a new host, using an existing shared gateway | No OMP installation or OAuth credential copy is required | Cross-host connection below → dedicated client token → [model configuration](../.agents/skills/deploying-with-omp-gateway/references/configuration.md) |
| Runtime connects directly to a supported model provider | No OMP; the provider's API credential is required | [Model keys and direct-provider example](configuration.md#model-configuration) |

A new host still needs Java 21, a database, external Runtime configuration, and any required MCP/Slack configuration. Sharing a gateway does not mean sharing the Runtime database or starting a second Slack Socket consumer. Database and gateway choices are independent: first reuse an existing compatible local PostgreSQL instance. If application credentials are missing, report a blocker or obtain explicit provisioning authorization; do not automatically start another database, connect to an unapproved remote database, or give Runtime cluster-superuser credentials.

The current local host mode uses standalone Docker PostgreSQL 17 `local-postgres` on `127.0.0.1:5432`; its physical legacy volume `gamecenter-local-postgres-data` remains unchanged. `gamecenter_local`/owner `gamecenter` and `session_agent`/owner `session_agent` are separate. Runtime reads `SESSION_AGENT_DATASOURCE_URL`/`USERNAME`/`PASSWORD` from the private `~/.local/share/session-agent-runtime/runtime.env` through external YAML placeholders (the full names of the latter two are `SESSION_AGENT_DATASOURCE_USERNAME` and `SESSION_AGENT_DATASOURCE_PASSWORD`). Set directory permissions to `700` and secret environment-file permissions to `600`; never commit secrets. Starting or stopping Runtime does not start or stop the shared database. `docker/runtime.sh start/restart` is only for the explicitly opted-in standalone full Docker mode; do not use it to update this host deployment or adopt its shared volume.

For backup, isolated restore, and preserving the rollback source, see [operations](../.agents/skills/deploying-with-omp-gateway/references/operations.md#回滾).

## 2. Standalone deployment: installation and OAuth login

1. Install OMP from an approved source and check the [compatibility prerequisites](../.agents/skills/deploying-with-omp-gateway/references/install.md#1-前置檢查). The same version string does not prove that two artifacts are the same patched build.
2. On the **broker host, as the OS user running the broker and with the same OMP profile/config root**, run:

   ```bash
   omp auth-broker login openai-codex
   ```

   Select an authorized account in the interactive flow. If a broker or remote credential architecture already exists, confirm its source first; do not create or overwrite another one. Do not copy `agent.db` from the repository or automatically import another person's OAuth dump.
3. Follow the [installation guide](../.agents/skills/deploying-with-omp-gateway/references/install.md) to create private directories, confirm restricted application-owner credentials for the existing shared PostgreSQL database, install systemd units, and configure Runtime model profiles. This does not authorize creating another database container.
4. Check gateway `/healthz`, bearer-authenticated `/v1/models`, and Runtime health. Obtain separate authorization for real model and Slack tests; see the [verification guide](../.agents/skills/deploying-with-omp-gateway/references/verification.md).

The local shell environment may differ from the service environment. The gateway unit's `OMP_AUTH_BROKER_URL` is not automatically available in the login shell, so CLI output of `not_configured` alone does not prove that the running gateway is broken. Perform status checks with the same broker URL, profile, and credential source as the service, without printing the complete environment.

## 3. Shared gateway: cross-host connection

The standalone template binds to `127.0.0.1:4000` and cannot be accessed directly from another host. Do not make it a public, unauthenticated service to enable connectivity. Options include:

- An SSH tunnel that uses the gateway's loopback listener.
- A controlled VPN or TLS reverse proxy that restricts sources, protects the bearer credential, and preserves gateway authentication; have the network administrator configure certificates and ACLs.

SSH example (run on the new Runtime host; replace the approved user and hostname first):

```bash
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 -L 127.0.0.1:14000:127.0.0.1:4000 operator@gateway-host
```

Set Runtime `base-url` to `http://127.0.0.1:14000/v1`. Verify the SSH host key first; do not disable host-key checking. This command is a foreground connection example. In production, the operator must configure a supervised tunnel service and startup order; if the SSH connection drops, model calls fail. Do not share the broker port or OAuth database with the Runtime host.

The Runtime unit for a shared gateway **must not copy the standalone OMP service dependencies**. Deploy only the required Runtime/database services and configure the actually reachable endpoint. Set dependencies and `EnvironmentFile` for the deployment environment.

## 4. Three credential layers and their lifecycle

| Credential | Holder and purpose | Lifecycle |
| --- | --- | --- |
| Gateway bearer or scoped client token | Runtime → gateway | The OMP mechanism described here has no automatic TTL; the operator revokes or rotates it |
| Broker bearer | Gateway → broker | Static token; the operator rotates it; do not give it to Runtime |
| Provider OAuth access/refresh token | Broker-managed; the gateway uses the access token to call the provider | Access token expires; the broker attempts refresh, but refresh authorization can still fail or be revoked |

By default, the OMP broker checks in the background every 60 seconds and attempts to refresh OAuth credentials within 5 minutes of expiration; follow the installed version and actual broker configuration. A successful refresh does not require changing the Runtime bearer. A temporary network error is not permanent invalidation. A confirmed authorization failure may disable the credential and require a new login. Insufficient quota is not token expiration; do not repeatedly log in or resend model requests to handle it.

Default files are `~/.omp/auth-gateway.token`, `~/.omp/auth-broker.token`, and `~/.omp/agent/agent.db`; a custom config root/profile changes their locations. Set private directory permissions to `700` and credential-file permissions to `600`; file permissions are not a guarantee of at-rest encryption. Never commit the OAuth database, environment files, tokens, backups, or raw diagnostics to Git, and do not print a complete broker snapshot (it may contain access tokens).

## 5. Use a dedicated client token for each Runtime

The standalone installation template reuses a **shared gateway bearer**, not an account-restricted client token. For multiple hosts or applications, scoped tokens are recommended. This section is an operational procedure; it does not modify a running service automatically.

First check whether the installed `omp auth-gateway --help` supports `--clients` and read that OMP version's `auth-broker-gateway` documentation. The scoped client configuration is a JSON map from client name to authorization rules, for example:

```json
{
  "runtime-host-a": {
    "tokenSha256": "<64-character hexadecimal SHA-256 of the client token>",
    "models": ["openai-codex/gpt-6.1-sol", "openai-codex/gpt-6-luna"],
    "accounts": {
      "openai-codex": ["<exact OAuth identityKey, including organization qualification>"]
    }
  }
}
```

The placeholders above must not be enabled as-is; confirm model IDs in the catalog first. Obtain the identity from the broker's secure account listing; do not guess based on email alone. If the installed version provides `omp usage accounts`, query it with the same broker/profile environment as the gateway; do not print access or refresh tokens.

Operational sequence:

1. On a restricted host, generate a unique token for each client using a cryptographically secure source (for example, at least 32 random bytes). Write it to a private secret file, not a command line or Git. Compute SHA-256 over the **raw token without a newline**; store only the digest in JSON.
2. Use a private JSON configuration to set the model and account allowlists. `accounts` also restricts the provider; do not grant a provider that is absent from `accounts`. Omitting a restriction broadens access; it is not a secure default.
3. Add `--clients=/absolute/private/gateway-clients.json` to the existing gateway service's `ExecStart`, preserving its bind, broker, and other settings. Schedule the service change, then daemon-reload/restart and verify. Do not start a second gateway on the same port.
4. Deliver the client token securely to that Runtime and update the **actually effective** source of `OPENAI_API_KEY`. First use the new token to query `/v1/models` and confirm the visible scope, then drain and restart Runtime. Do not assume the running service reads `gateway.env`.
5. Remove the old client entry or replace its digest to revoke that client. This version rereads configuration about every 10 seconds while serving; in-flight requests may still finish. Atomically replace the file using a temporary file on the same filesystem so the service never reads partial JSON.

A client token cannot query the all-account `/v1/usage` or `/v1/credentials/check` endpoints (403 is the expected restriction). An allowlist containing multiple accounts does not define a fixed primary/fallback order. Conversations for the same client/provider share an account pin and may switch within the allowlist after quota limits or authentication recovery. To pin an account, list only that exact identity; do not treat policy preference as an isolation boundary.

**The shared gateway bearer remains valid and is not restricted by the client allowlist.** Enabling `--clients` does not revoke a previously distributed shared token. After migrating clients, assess rotating the shared bearer so that an old token cannot bypass the restrictions.

## 6. Revocation, rotation, and failure handling

| Situation | Action |
| --- | --- |
| A single Runtime client token is exposed | Atomically remove its entry or replace its digest; securely update that Runtime's secret, drain and restart it, then verify that the new token works and the old token is rejected |
| The shared gateway bearer is exposed | Inventory all holders and coordinate a transition. `omp auth-gateway token --regenerate` creates and prints a new secret; run it only in a controlled terminal/secret-capture workflow. Securely distribute it to clients that still need the shared bearer, then restart the gateway for the new value to take effect |
| The broker bearer is exposed | Inventory all broker clients; run `omp auth-broker token --regenerate` in a controlled environment, update each client's credential source, and coordinate broker/gateway restarts. Do not use the new token as the Runtime API key |
| OAuth authorization is permanently invalid | Re-run the login flow on the broker host as the same user/profile; then verify account health and catalog, and separately authorize any model test |
| Model returns 401/403/429 | Check gateway bearer, client allowlist, provider authentication, and quota respectively; do not automatically rotate tokens, add retries, or switch to an unauthorized account |

Static-token rotation is not guaranteed to be interruption-free: the server may still retain the old allowlist, and changing the disk file does not prove revocation. Verify that the old token is rejected and the new token can access only the expected scope. Disabling a shared OAuth account may affect other clients; check the impact first.

## 7. Verification and version interpretation

Use the [verification guide](../.agents/skills/deploying-with-omp-gateway/references/verification.md) to record separately:

- Gateway/broker health, actual executable version, and checksum; health does not prove tool-conversion compatibility.
- The authenticated model catalog; checking account access does not prove a real completion succeeded.
- An authorized model request/usage result; the presence of a field does not prove a cache hit and is not billing audit evidence.
- The Runtime's active release JAR, revision/checksum, process start time, and health. `git pull`/`git push` does not update a running JAR.
- Authorized production Slack/MCP acceptance separately from local fake-backed tests. Health alone is not end-to-end success.

When comparing versions, fetch the remote first, confirm `origin/main`, then compare the service's actual JAR release, deployment record, and checksum. If the JAR was replaced after the process started, inspecting the file path alone does not prove which version is running. For an upgrade, follow the separate [backup and update procedure](../.agents/skills/deploying-with-omp-gateway/references/operations.md#更新-runtime不更新-omp); do not restart services or apply migrations just to check versions.
