# Configuration

[Home](../README.md) | [繁體中文](configuration.zh-TW.md) | [Question index](README.md)

<a id="purpose-and-scope"></a>

## Purpose and scope

This reference owns generic configuration keys, choices and meaning for models, Runtime-local skills and MCP. The [deployment configuration runbook](../.agents/skills/deploying-with-omp-gateway/references/configuration.md) owns actual private host configuration, host examples, update/apply sequence and gateway compatibility/provenance. The [gateway deployment guide](gateway-deployment.en.md) owns gateway/OAuth setup; the [MCP runbook](../.agents/skills/deploying-with-omp-gateway/references/mcp.md) owns MCP credential injection, rotation and authorized acceptance. Examples below are illustrative, not live availability guarantees.

<a id="contents"></a>

## Contents

- [Runtime and gateway boundaries](#runtime-boundaries)
- [Model keys and direct-provider example](#model-configuration)
- [Runtime-local skills](#runtime-local-skills): [definition](#skill-definition), [reload and replay](#skill-lifecycle)
- [MCP keys, defaults and disabling](#mcp-connections)

<a id="runtime-boundaries"></a>

## Runtime and gateway boundaries

Runtime uses its configured Spring AI adapter to call a model. It does not perform native Codex OAuth login or refresh, start or manage OMP Gateway, or inherit OMP's agent loop, prompt, conversation history, MCP configuration or skills. `provider: openai` selects the OpenAI-compatible Chat Completions adapter, not a credential issuer: direct OpenAI uses its API key; gateway routing uses the gateway bearer credential with the gateway `base-url`, not an OAuth access token. Keep credentials in private environment configuration or a secret manager, never committed files.

The build pins Spring AI **2.1.0-RC1**, a release candidate rather than a stable release. Google and OpenAI adapters remain available; dependency support for Responses API does not enable that API or native Codex OAuth in Runtime. The OpenAI adapter uses Chat Completions with `store=false`.

During a tool turn, ordered message parts become private, opaque, route-bound continuation state, preserving reasoning, per-part Google thought signatures, OpenAI-compatible extra tool-call properties and assistant text alongside tool calls on replay. Provider response metadata is not the replay contract. Continuation is removed when the job finishes; permanent provider-neutral history is unchanged. Runtime owns sequential tool execution and atomic history commits, not Spring AI memory or an advisor-managed tool loop. Before replacing M1 with RC1, drain in-flight/retry jobs: RC1 cannot replay the old Google metadata-only continuation format; completed history needs no migration.

Runtime configures the OpenAI option `.strict(false)` and passes tool schemas without making optional fields required. Retained fake-HTTP assertions reject `strict:true` and compare an example schema, but do not establish explicit wire `strict:false` or an optional-field example. They cover committed/replayed tool observations and final responses, not downstream gateway translation; no new test execution is claimed here. The [host compatibility and provenance requirements](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries) define the required fixed gateway build, strictness preservation, pinned-route generation-control limitations and request-count boundary. A configured output budget is not necessarily a provider-side cap.

<a id="model-configuration"></a>

## Model configuration

`src/main/resources/application.yml` imports bundled `src/main/resources/models.yml`, which selects `google-primary` and includes an inactive OpenAI fragment. Keys are environment placeholders. Every configured entry requires valid credentials and complete explicit capacities; missing credentials, capacity or default-name mapping fails startup rather than silently selecting another model.

Keys below are relative to `session-agent.model` (entry keys under `models.<name>`):

| Key | Meaning / choice |
| --- | --- |
| `default-model` | Configuration name for new sessions; must exist in `models`. Changing it does not change existing sessions. |
| `models.<name>` | Runtime logical configuration name, distinct from the provider's model ID. |
| `provider` | Wire adapter; Google and OpenAI adapters are available. `openai` does not imply direct OpenAI credentials. |
| `model-id` | Provider model identifier; the host gateway's full `provider/id` choice is defined in the runbook. |
| `api-key` | Direct provider key or gateway bearer for the selected route, injected privately; never a substitute OAuth access token. |
| `base-url` | OpenAI-only compatible base including `/v1`, not `/chat/completions`; omitted OpenAI base defaults to `https://api.openai.com/v1`. Omit for Google: a nonblank value on `google-genai` fails startup. |
| `context-window-tokens` | Must be positive. Actual model capacity; verify for the selected model, never copy another model's capacity. |
| `max-output-tokens` | Must be positive. Runtime output budget, not above the model's published maximum; gateway forwarding may limit enforcement. |
| `reasoning-effort` | OpenAI-only optional request setting; a nonblank value on `google-genai` fails startup. Supported values depend on model/gateway version. OMP terminal model-role/reasoning preferences do not configure Runtime. |
| `timeout` | Optional; must be positive when supplied. Google requires an effective `1..2147483647` milliseconds. Single Runtime request timeout, not a whole-job time limit. |

For direct OpenAI API use, the optional non-secret entry shape is:

```yaml
session-agent:
  model:
    default-model: openai-primary
    models:
      openai-primary:
        provider: openai
        model-id: gpt-5-mini
        api-key: ${OPENAI_API_KEY:}
        context-window-tokens: 400000
        max-output-tokens: 16384
        timeout: 30s
        base-url: https://api.openai.com/v1
```

These are example values, not a universal or future model/capacity guarantee. To enable this alongside bundled Google, add the entry as a correctly indented sibling of `google-primary` under `session-agent.model.models` in bundled `models.yml`—do not merely uncomment the unindented fragment—supply both credentials and rebuild the image. Docker Compose uses `OPENAI_API_KEY` in untracked `docker/.env`; retain `GOOGLE_API_KEY` while its entry remains configured. Enabling an entry in bundled `models.yml` requires an image rebuild. Selecting among already configured entries through the session API needs no restart and affects only subsequently accepted messages, not queued/running jobs.

For systemd OMP Gateway, do not copy the direct-provider example: follow [model format](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#模型格式) and [configuration source and application](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#設定來源與套用). That owner defines the complete private `application.yml` selected by `SPRING_CONFIG_LOCATION`, sibling `models.yml`, replacement rather than merge, drain/restart/acceptance and existing-session migration.

<a id="runtime-local-skills"></a>

## Runtime-local skills

Skills are optional and disabled by default. Keys are under `session-agent.skills`:

| Key | Checked-in default | Meaning |
| --- | --- | --- |
| `roots` | `[]` | Explicit operator-managed shared roots; no automatic home, OMP or repository discovery. |
| `max-file-bytes` | `65536` | Must be `>0` and `< Integer.MAX_VALUE`; full UTF-8 file bytes, including frontmatter, must fit the effective limit. |
| `max-skills` | `128` | Must be positive; maximum discovered skills. |

All Runtime users can read configured roots. Roots and references must be trusted operator-maintained content with Runtime read/traverse access only; use read-only container bind mounts. Paths and ancestors must not be symlinks. Do not expose home directories, OMP credentials, `.agents/skills` maintenance/deployment guides or other secrets. Runtime reads prompt material, not shell commands/scripts; a loaded skill is neither a validator nor proof of compliance/report correctness. The [filesystem security contract](architecture/skills.md#filesystem-security-boundary) defines provider support, fail-closed discovery and the privileged-writer/file-open-latency boundary.

<a id="skill-definition"></a>

### Skill definition

Each root's direct child directory is a skill with `SKILL.md`. Frontmatter `name` must equal the directory name, use lowercase letters/digits separated by hyphens and be at most 64 characters; `description` must be nonempty and at most 1024 characters. Files must be valid UTF-8; full bytes including frontmatter count toward the limit. Duplicate/invalid metadata rejects startup. The [host skill installation example](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用) owns root configuration, on-disk example, permissions, restart and verification sequence.

Skill content and its references are user-maintained inputs, not bundled business
rules. Evaluate information coverage and domain correctness outside Runtime, then
update the skill's source and deploy the revised files using the lifecycle below.

<a id="skill-lifecycle"></a>

### Skill reload, selection and replay

- New names, descriptions or roots require restart to refresh discovery metadata. Each new `readSkill` or newly submitted `/skill:review 問題` reads current body/relative reference bytes. SHA-256 is read evidence, not a version lock.
- Replaying the same source message is not a new read; recovery after committed selection reuses durable content. See [discovery/current-file reads](architecture/skills.md#discovery-and-current-file-reads) and [selection/recovery](architecture/skills.md#selection-and-recovery).
- HTTP message text uses `/skill:review 問題`; Slack uses ordinary `@bot /skill:review 問題` (also in bound threads), not a registered Slack slash command. Without a question, a valid selection still asks the model to confirm loading.
- Invalid selection retains USER/safe-error audit but sends no model request, summary or token estimate: that job has zero model requests. Automatic tool-read failure remains an ordinary TOOL observation.
- Ordinary compaction may summarize/remove loaded content. It is not permanently enabled or automatically restored; submit a new `/skill:review` to load it again.

<a id="mcp-connections"></a>

## MCP connections

MCP belongs to Runtime, not OMP Gateway; gateway routing imports no OMP MCP servers, tools or skills. Configure zero or more named connections under `session-agent.mcp.connections`. The connection-entry keys and MCP-wide timing keys have different scopes, as shown in the [deployment runbook YAML](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#1-合併到生效的私有-applicationyml).

**Connection entries: `session-agent.mcp.connections.<name>`**

| Key / choice | Meaning / default |
| --- | --- |
| Connection name | `[A-Za-z][A-Za-z0-9-]{0,31}`; exposed tool names are `{connectionName}_{rawToolName}`. |
| `enabled` | Defaults to `true`; `false` still requires a valid URL and resolvable retained header placeholders. |
| `url` | Exact absolute Streamable HTTP MCP endpoint including path, not a website/health URL, stdio or old SSE configuration. |
| `headers` | Optional provider-defined headers; omit when authentication is unnecessary, otherwise inject values privately. |

**MCP-wide timing: `session-agent.mcp` (siblings of `connections`, not keys inside a connection)**

| Key | Meaning / default |
| --- | --- |
| `refresh-interval` | `60s` catalog refresh; not configuration/environment reload. |
| `request-timeout` | `30s`. |
| `initial-backoff` / `maximum-backoff` | `1s` / `60s` independent reconnection backoff. |
| `shutdown-timeout` | `5s`. |

All MCP timing values must be positive. `maximum-backoff` has an effective hard cap of `60s`.

```yaml
session-agent:
  mcp:
    connections:
      catalog:
        enabled: true
        url: ${SESSION_AGENT_MCP_CATALOG_URL}
        headers:
          Authorization: ${SESSION_AGENT_MCP_CATALOG_AUTHORIZATION}
```

This is a partial generic example, not a complete application config; merge into the existing root without overwriting model, Slack or datasource settings. Use the provider's required header name/value; a bearer value includes `Bearer `. Keep credentials out of URLs and committed files, using private environment variables or a secret manager.

When MCP is unnecessary, omit `session-agent.mcp.connections`. When removing the last entry, omit the entire `connections` property rather than leave `connections: {}` or unresolved credential placeholders. Empty-map loading/binding behavior depends on the loader/version; the Java properties constructor accepts an actual empty map, so startup failure from YAML `{}` is not a source-proven universal rule. Keep the omission recommendation to avoid loader-dependent binding problems. Disabling an entry does not make retained placeholders optional; remove obsolete headers or the whole entry when removing its token. The [MCP operation sequence](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#邊界與操作順序) owns disabling, private injection, drain/restart, rotation and acceptance.

Docker Compose starts with zero connections when `SESSION_AGENT_MCP_CONFIGURATION_JSON` is unset. An illustrative equivalent configuration is `{"session-agent":{"mcp":{"connections":{"catalog":{"enabled":true,"url":"https://host/custom/mcp"}}}}}`. Supply optional headers privately; Runtime receives exact URL/headers without provider-specific behavior.

No connections, or all configured servers unavailable, do not prevent startup. Connections reconnect/refresh independently: one failure does not hide another available connection's tools. `GET /actuator/mcpConnections` exposes safe state (`DISABLED`, `CONNECTING`, `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, `STOPPED`), discovered tool count and applicable safe failure code/message, never endpoint URLs, headers, tokens, raw provider responses or exception details. Health/discovery/direct MCP success is not proof of model-compatible tool calls; use the authorized [model-to-tool acceptance](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#經授權的模型--mcp--history-實測).
