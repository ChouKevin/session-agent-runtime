# 設定：模型、reasoning、Slack 與 MCP

以下以 `$DEPLOY_ROOT=$HOME/.local/share/session-agent-runtime` 為例；先用已安裝的 unit 確認真正的 config location。設定只留在主機，repo 的 assets 是無秘密範本。

部署模式、OAuth 登入、共用 gateway 的跨主機連線與帳號 allowlist 統一見 [Gateway 部署與憑證管理](../../../../docs/gateway-deployment.md)。本頁負責 Runtime 的模型與整合設定；不要把 provider OAuth token 填入 gateway 的 `api-key`。

## 模型格式

`models.yml`：

```yaml
session-agent:
  model:
    default-model: omp-codex
    models:
      omp-codex:
        provider: openai
        model-id: openai-codex/gpt-6.1-sol
        api-key: ${OPENAI_API_KEY}
        base-url: http://127.0.0.1:4000/v1
        context-window-tokens: 500000
        max-output-tokens: 8192
        reasoning-effort: medium
        timeout: 180s
```

| 欄位 | 契約 |
|---|---|
| `default-model` | 新 session 預設使用的 **設定名稱**，必須存在於 `models` |
| `models.omp-codex` | Runtime 邏輯設定名稱，與 provider 的模型 ID 不同 |
| `provider: openai` | Runtime 的 wire adapter；不表示直接使用 OpenAI API key |
| `model-id` | OMP `/v1/models` 的完整 `provider/id`，避免 bare ID 歧義 |
| `api-key` | 私有 environment 提供的 gateway bearer，不是 OAuth access token；fresh install 使用 `gateway.env`，既有部署沿用其有效來源 |
| `base-url` | OpenAI-compatible base，包含 `/v1`，不包含 `/chat/completions` |
| `context-window-tokens` | 實際模型容量；從 gateway catalog 核對，不能沿用其他模型數值 |
| `max-output-tokens` | Runtime 發出的輸出預算；不得超過模型已公布上限 |
| `reasoning-effort` | 本部署預設 `medium`；支援值依模型／gateway 版本確認 |
| `timeout` | 單次 Runtime request timeout，不是整個工作總時間上限 |

這組 model ID／容量在 OMP 18.12.6 catalog 查得；不是所有帳號均可使用，也不是未來版本的保證。Gateway 可能轉換或忽略 generation 控制；不能把 `max-output-tokens` 或 Runtime 的 `modelCallCount` 當作 provider 費用／嘗試次數硬上限。

OMP Gateway 不會套用終端 OMP session 的 model role 或 reasoning 偏好。此 Runtime 透過具體 `model-id` 與 `reasoning-effort` 發送請求；不要以修改 OMP TUI 設定取代 Runtime 設定。

## 設定來源與套用

範本 service 使用：

```ini
Environment=SPRING_CONFIG_LOCATION=file:%h/.local/share/session-agent-runtime/application.yml
Environment=LOGGING_CONFIG=%h/.local/share/session-agent-runtime/logback.xml
```

外部 `application.yml` 以 `spring.config.import: models.yml` 匯入同目錄模型設定。這是**完整替換**預設 application config，不會載入 bundled Google profile。單獨指定 `SPRING_CONFIG_IMPORT` 或新增第二個 model map，可能仍保留 bundled `google-primary`，導致缺 Google key 而無法啟動。

更換模型／reasoning：

1. 停止新提交，排空尚未完成工作，參照 [operations.md](operations.md)。工作受理時會保留 model selection／fingerprint；重試中的工作不應被悄悄換模型。
2. 保留舊 `models.yml`，在私有部署目錄修改設定；所有 model entries 都必須有效。
3. `systemctl --user restart session-agent-runtime`。只修改 YAML 不需重新編譯 jar；不需重啟 gateway。
4. 查 health、啟動錯誤與有授權的模型 smoke；不能只看程序 active。

修改 `default-model` 不會替既有 session 換選擇。若增加另一個 model entry，使用既有 API 選擇：

```bash
curl --fail -X PUT "http://127.0.0.1:8090/internal/sessions/$SESSION_ID/model" \
  -H 'Content-Type: application/json' -d '{"model":"omp-codex"}'
```

`model` 是設定名稱，作用於該 session 之後受理的新訊息，不改寫已排隊／執行中的工作。移除既有 session 仍選用的名稱之前，先完成選擇遷移。Slack 沒有切換模型指令。

### 隔離 Luna 驗收 profile

真實模型驗收另用私有 `acceptance-luna` entry：`provider: openai`、
`model-id: openai-codex/gpt-6-luna`、`reasoning-effort: medium`、
`context-window-tokens: 272000`、`max-output-tokens: 8192`；沿用有效的
Gateway route／credential 來源，不複製 Sol 的 500000 容量。實際可用性仍須
核對 catalog 與成功 response 的 model ID，不能只看 YAML。

保留 production default Sol。若新 Slack thread 尚未建立 session binding，
用預設已選 Luna 的隔離 Runtime，且在第一則訊息前完成設定。有效
`/skill:inventory-java-mq` 無問題版本仍會發模型確認，不是零模型 bootstrap。
隔離驗收可將 ordinary allowance 設為3或4以觀察 handoff；production12不變。
外部 config 必須完整替換 bundled config，不能只清空 import 或改 default，
否則原 Google model entry 仍可能合併。隔離 DB／private config 不連 production DB。
同一 Slack app 不啟動第二個 Socket consumer；harness 僅替換 Socket runtime
factory，保留實際 intake／worker／Web API，並明確標示 synthetic ingress。

## Slack

私有 `runtime.env`：

```dotenv
SESSION_AGENT_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/session_agent
SESSION_AGENT_DATASOURCE_USERNAME=session_agent
SESSION_AGENT_DATASOURCE_PASSWORD=既有Runtime應用owner的私有密碼
SLACK_APP_TOKEN=由Slack產生的App層級token
SLACK_BOT_TOKEN=由Slack產生的Bot token
SLACK_BOT_USER_ID=Bot使用者ID
```

這是欄位示意，不是可直接登入的範例。實際 token/password 不提交。Slack 欄位全空可停用整合；啟用時三個必須齊全。

- App-level token 要有 `connections:write`，啟用 Socket Mode。
- Bot 至少有 `app_mentions:read`、`chat:write` 並訂閱 `app_mention`，把 bot 加入目標頻道。
- 不帶 mention 的 thread 續聊／DM 需要相應 message events 與 scopes：`message.channels` / `channels:history`、`message.groups` / `groups:history`、`message.im` / `im:history`、`message.mpim` / `mpim:history`。只開需要的對話範圍；新增 scopes 後依 Slack 要求重新安裝／授權。
- Markdown/CSV 附件與長回答的 `answer.md` 另需 bot `files:write`。這是既有 app 的權限變更，需明確核准並依 Slack 要求 reinstall／重新授權；Runtime 更新或本地 fake smoke 不代表已授權，不偷偷改 token。此功能不要求 `files:read`。
- 可用 `auth.test` 驗證 token 並取得 `user_id`，回報只保留狀態與 ID，不輸出 token、headers 或 Socket URL。
- 在已綁定 thread 使用 `@bot stop` 或 `@bot 停止工作`；停止當時該 thread 未完成工作，不影響後來新提交。採合作式取消，已發生的工具副作用不會回滾。

一般 reply 只會在已綁定 thread 續聊；未綁定 thread 即使 mention bot 也不會
建立 session。完整 root／reply、文字正規化與忽略條件見
[Slack intake 契約](../../../../docs/http-api.md#slack-socket-mode-optional)。

一般回答以原文 native `markdown` block 呈現；超過 10,000 UTF-16 units／Unicode
code points 時，整份原文 UTF-8 存為 `answer.md`，搭配固定通知，不解析、拆分、
修補 Markdown 或另呼叫模型摘要。Markdown／附件不受支援或權限不足會明確
delivery failure，不靜默改純文字或截斷。

`createMarkdownReport`／`createCsv` 只對有受支援附件 route 的正常工作開放，
不對 compaction 開放；工具結果 `SAVED_PENDING_DELIVERY` 不是 Slack 已送達。
CSV 原字串留在 audit；輸出加 UTF-8 BOM、CRLF、全欄引號／雙引號 escaping，
公式開頭欄位加 apostrophe 並在工具結果揭露，不能保證所有試算表匯入模式皆安全。

Artifact、長回答 bytes、upload stages 與敏感 upload capability 存 PostgreSQL，
繼承對話／備份的存取及保留政策；本功能無 TTL／自動刪除或 public UUID URL。
`FINALIZING` 發生不明結果會保留 `UNCONFIRMED` 並顯示 delivery failure，
不自動再次 finalize、重新配置檔案或重跑模型；人工核對前不要重設狀態強制 retry。

## MCP 與 logging

沒有 MCP 時，省略 `session-agent.mcp.connections`。在目前綁定流程，YAML `connections: {}` 可能成為空字串造成啟動失敗。需要工具時，於私有 application config 加入一般 MCP 連線：

```yaml
session-agent:
  mcp:
    connections:
      catalog:
        url: ${MCP_CATALOG_URL}
        headers:
          Authorization: ${MCP_CATALOG_AUTHORIZATION}
```

把變數放進私有 environment／secret manager；不用認證就省略 `headers`。Runtime 不會因為模型經過 OMP Gateway 而自動取得 OMP 的 MCP、skills 或工具。每個 MCP 連線獨立管理。

完整連線格式、java-code-intelligence 雙 reader 範例、systemd 憑證注入、停用／輪換及實際工具驗收見 [MCP 操作指南](mcp.md)。設定要加在 Runtime 生效的 application config，而不是 Gateway 或 OMP client 的 MCP 設定。

主機範例以 JSON 寫到 systemd journal，不使用容器固定 `/app/logs` 路徑。用 `journalctl --user -u session-agent-runtime` 查詢；journal 的保留期依主機政策，不等於 repo Docker logging 的 7 日輪替設定。不要將完整 log 當作公開驗收附件。

## Runtime 本地 skills（選用，預設停用）

合併至既有私有 application config 的同一 `session-agent` 節點，不覆寫模型、
Slack 或 datasource。Gateway 不管理這些檔案。

```yaml
session-agent:
  skills:
    roots: [/opt/session-agent/skills]
    max-file-bytes: 65536
    max-skills: 128
```

root 的直接子目錄為 skill；例如 `/opt/session-agent/skills/review/SKILL.md`：

```markdown
---
name: review
description: Review proposed changes for correctness and maintainability.
---
Check the requested behavior and concrete risks.
Read skill://review/references/checklist.md when needed.
```

name 必須等於目錄名，只用小寫字母／數字與分隔連字號，上限 64 字元；
description 非空且上限 1024 字元。檔案必須是有效 UTF-8，含 frontmatter 的完整
bytes 計入大小限制。重複／不合法 metadata 會拒絕啟動；不要放秘密。
新名稱、description 或 roots 設定要重啟才更新 discovery metadata；每次新的
`readSkill` 或 `/skill:review 問題` 都讀當時的 body／相對 reference。
同一 source message 重送不是新讀取，已提交 selection 的工作復原也不重讀。
SHA-256 只是讀取證據，不是鎖版本。

HTTP 的 message 使用 `/skill:review 問題`；Slack 用普通訊息
`@bot /skill:review 問題`（已綁定 thread 也適用），不是註冊 Slack slash command。
不帶問題只要求模型確認載入。無效選擇保留 USER／安全錯誤 audit，但不送模型、
summary 或 token 估算；該工作零模型請求。自動工具讀取失敗仍是普通 TOOL observation。
載入內容可被一般 compaction 摘要／移出 context，不永久啟用、不自動還原；
要再次載入就提交新的 `/skill:review`。

所有使用者都可讀 roots 內容。僅允許操作者維護、Runtime read/traverse 的目錄，
容器以唯讀 bind mount（`:ro`）掛載；路徑與祖先不可用 symlink。不要掛載 home、
OMP credential 或 `.agents/skills` 維護／部署指南。Runtime 不執行 shell/scripts，
也不自動發現 OMP／repository skills。部署權限見 [install.md](install.md)。

### 安裝 Java MQ 業務 skill

本 repo 的業務來源是 `runtime-skills/inventory-java-mq/SKILL.md`，不是
`.agents/skills`。核准後由操作者把 `inventory-java-mq` 目錄複製到上述 root，
例如 `/opt/session-agent/skills/inventory-java-mq/SKILL.md`；不以 checkout
symlink 代替複製，不額外掛載整個 repo。保留 operator ownership，Runtime 僅
read/traverse；容器掛載 root 時維持 `:ro`。不要把主機私有設定或 credentials
放進 skill。設定仍預設停用；repo 內有檔案不代表已部署。

重啟後確認 startup metadata 有 `inventory-java-mq`、正確 description 與
`skill://inventory-java-mq`；body 依 current-file 規則讀取。完整檔案（含
frontmatter）必須落在實際 `max-file-bytes` 內，技能總數落在 `max-skills` 內。
不需修改 Runtime core prompt、MCP tool names 或 Gateway 設定。

HTTP 使用 `/skill:inventory-java-mq 問題`；Slack 使用
`@bot /skill:inventory-java-mq 問題`。只送 `/skill:inventory-java-mq` 仍會載入並
請模型確認；自動選擇則要另在新 context 驗證真實 `readSkill` call。
技能預設盤點共用／靜態 consumer 設定，明確要求環境時才展開該環境；
每個 repo 使用獨立 source pin，不能把靜態證據當成已部署設定。
「技能已載入」只證明該次 bytes 已提交，不證明模型遵守指引或報表正確。
Prompt skill 不是 validator；來源歸屬、coverage 與欄位對應仍須獨立驗收。
驗收見 [verification.md](verification.md#java-mq-業務-skill-驗收)。

## Generic MCP connection reference

Configure zero or more named connections under `session-agent.mcp.connections`. A connection name is portable (`[A-Za-z][A-Za-z0-9-]{0,31}`); discovered tools are exposed as `{connectionName}_{rawToolName}`. The URL is the exact absolute streamable-HTTP endpoint, including any non-default path.

For the host deployment, follow the [MCP setup and acceptance runbook](mcp.md). It includes two independent read-only `java-code-intelligence` readers, private systemd credentials, disabling/rotation, and model-to-tool history verification. Gateway routing does not import OMP MCP configuration or skills. Discovery and direct MCP success do not by themselves prove model-generated tool arguments are compatible.

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

Headers are optional. Omit the header entry when a provider does not require it; otherwise source the value from the environment as shown, rather than committing a credential. Runtime defaults are a 60-second catalog refresh, 30-second request timeout, reconnect backoff from 1 to 60 seconds, and a 5-second shutdown timeout.

Docker Compose starts with zero MCP connections when `SESSION_AGENT_MCP_CONFIGURATION_JSON` is unset. To configure one through Compose, set that environment variable to the equivalent generic configuration, for example `{"session-agent":{"mcp":{"connections":{"catalog":{"enabled":true,"url":"https://host/custom/mcp"}}}}}`. Supply any optional header values through the deployment environment or secret manager; the exact URL and headers are passed to Runtime without provider-specific behavior.

Startup is safe with no configured connections or when every configured server is unavailable. Each connection reconnects and refreshes independently, so a failed provider does not hide tools from an available provider. The `mcp` module exposes `GET /actuator/mcpConnections` with safe per-connection diagnostics: state (`DISABLED`, `CONNECTING`, `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, or `STOPPED`), discovered tool count, and when applicable a safe failure code/message. It never exposes endpoint URLs, headers, tokens, raw provider responses, or exception details.

## Model adapter and Gateway boundaries

The build pins Spring AI **2.1.0-RC1**, a release candidate/pre-release rather than
a stable release. The upgrade retains the existing Google and OpenAI adapters;
availability of Responses API support in the dependency does not enable it in this
runtime or add native Codex OAuth login/refresh.

Spring AI's ordered message parts are captured as opaque, route-bound continuation
state during a tool turn. This preserves reasoning, per-part Google thought
signatures, and OpenAI-compatible extra tool-call properties on replay, including
assistant text alongside tool calls. Provider response metadata is not the replay
contract. The continuation remains private to the model adapter and is removed
when the job finishes; permanent provider-neutral history is unchanged. Runtime
still owns sequential tool execution and atomic history commits, not Spring AI
memory or an advisor-managed tool loop.

Before replacing an M1 runtime with RC1, drain in-flight/retry jobs through
completion. RC1 does not replay the old Google metadata-only continuation format;
already completed conversation history needs no migration. The local fake-HTTP
smoke verifies Runtime sends explicit `strict: false`, keeps optional tool-schema
fields optional, commits/replays the tool observation, and stores the final
response. It does not verify a gateway's downstream translation; the gateway
compatibility requirement below still applies.

`src/main/resources/models.yml` is the bundled model configuration imported by the default `application.yml`. It contains a Google entry and an inactive OpenAI fragment for direct-provider/container deployments. The OMP deployment skill instead selects a complete external `application.yml` with `SPRING_CONFIG_LOCATION`, importing its own `models.yml` without retaining the unused Google entry. Model credentials are environment placeholders only; do not put a key in configuration committed to a repository.

To deploy OpenAI alongside the existing Google entry, edit `src/main/resources/models.yml` to enable the OpenAI fragment, provide both credentials through the deployment environment, and rebuild the image. The entry must have complete, explicit capacities. For example, the non-secret shape is:

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

For Docker Compose, set `OPENAI_API_KEY` in the untracked `docker/.env` (and retain `GOOGLE_API_KEY` while the Google entry is configured). The OpenAI adapter uses Chat Completions with `store=false`; it does not use the Responses API. A configured entry with a missing key, capacity, or default-key mapping fails at startup rather than selecting a different model. Enabling an entry in bundled `models.yml` requires an image rebuild; selecting among already configured entries through the session API does not require a restart and affects only subsequently accepted messages.

Codex OAuth is not a native Runtime authentication mode. The `openai` adapter can
use an external OpenAI-compatible gateway through `base-url`; in that deployment,
`api-key` is the gateway's bearer credential, while the gateway owns Codex OAuth
login, refresh, and provider-protocol conversion. An OAuth access token is not a
drop-in replacement for an OpenAI API key. Keep these provider details outside
the conversation and MCP contracts.

The currently pinned host-local OMP gateway does not run the OMP agent loop or add
its agent prompt, skills, or conversation history. It translates the caller's
messages/tools into the Codex protocol, manages OAuth and transport, and can apply
protocol/history normalization and internal retries. It is not a byte-for-byte
relay. Runtime's `modelCallCount` counts requests to that gateway, not all
underlying Codex attempts.

**Gateway compatibility requirement:** unpatched OMP 18.12.6 drops tool
`strict: false` while translating Chat Completions into the provider protocol.
The deployed workaround is a native OMP source build with that conversion fixed,
not a different agent or proxy implementation. Explicit `false`/`true` must survive,
and omitted Chat Completions strictness must retain its non-strict default.
Use a verified fixed build or an upstream release containing the fix; do not replace
it with an unpatched binary merely because both report the same OMP version.
Record the source revision/patch and executable checksum, and rerun the
[MCP model-to-tool acceptance](mcp.md)
after changing the Gateway. A local patched build is not an official upstream release.

For this pinned Codex route, the gateway does not forward the OpenAI
`max_tokens`/`max_completion_tokens`, temperature, or top-p settings. Configuring
Runtime `max-output-tokens` is therefore not a guaranteed provider-side output
cap on this route. Recheck these boundaries when upgrading or replacing OMP;
this feature does not change gateway behavior.
