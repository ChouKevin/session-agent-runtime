# 設定

[首頁](../README.zh-TW.md) | [English](configuration.md) | [問題索引](README.zh-TW.md)

<a id="purpose-and-scope"></a>

## 目的與範圍

本參考文件定義模型、Runtime 本機 skills 與 MCP 的通用設定 keys、選擇與意義。[部署設定操作手冊](../.agents/skills/deploying-with-omp-gateway/references/configuration.md)負責實際私有主機設定、主機範例、變更／套用順序與閘道相容性／provenance；[閘道部署指南](gateway-deployment.md)負責 gateway／OAuth 設置；[MCP 操作手冊](../.agents/skills/deploying-with-omp-gateway/references/mcp.md)負責 MCP 憑證注入、輪替與經授權驗收。以下範例不是即時可用性保證。

<a id="contents"></a>

## 目錄

- [Runtime 與閘道邊界](#runtime-boundaries)
- [模型 keys 與直接服務提供者範例](#model-configuration)
- [Runtime 本機 skills](#runtime-local-skills)：[定義](#skill-definition)、[重新載入與重播](#skill-lifecycle)
- [MCP keys、預設值與停用](#mcp-connections)

<a id="runtime-boundaries"></a>

## Runtime 與閘道邊界

Runtime 透過已設定的 Spring AI adapter 呼叫模型。它不會原生執行 Codex OAuth 登入或更新、啟動或管理 OMP Gateway，也不繼承 OMP 的 agent loop、提示、對話歷史、MCP 設定或 skills。`provider: openai` 選用 OpenAI 相容 Chat Completions adapter，不代表憑證發行者：直接使用 OpenAI 時提供 API key；透過 gateway 時，`base-url` 指向閘道，`api-key` 使用閘道 bearer 憑證，而不是 OAuth access token。憑證應放在私有環境設定或密鑰管理服務，不得提交至版本庫。

建置固定使用 Spring AI **2.1.0-RC1**，這是 release candidate，並非穩定版。Google 與 OpenAI adapters 仍可使用；相依套件支援 Responses API，不代表 Runtime 已啟用該 API 或原生 Codex OAuth。OpenAI adapter 使用 Chat Completions 並設定 `store=false`。

工具回合期間，有序訊息部分會成為私有、opaque、綁定路由的 continuation state，讓 reasoning、各部分的 Google thought signatures、OpenAI 相容額外 tool-call 屬性，以及工具呼叫旁的 assistant 文字可在重播時保留。Provider response metadata 不是 replay 契約。工作完成後會移除 continuation；永久的 provider-neutral history 不變。Runtime 負責依序執行工具與原子提交歷史，不使用 Spring AI memory 或 advisor-managed tool loop。M1 升級 RC1 前須排空執行中／重試中的工作：RC1 不會重播舊版僅含 Google metadata 的 continuation 格式；已完成歷史不需遷移。

Runtime 將 OpenAI option 設為 `.strict(false)`，並傳遞 tool schemas，不把選用欄位改成必填。保留的 fake-HTTP assertions 會拒絕 `strict:true` 並比對範例 schema，但不證明 wire 上有明確的 `strict:false`，也沒有獨立的選用欄位範例。這些 assertions 涵蓋工具 observation 提交／重播與最終回應，不證明閘道下游轉換；此處不聲稱執行了新測試。[主機相容性與 provenance 要求](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries)定義必要的修正版本、strictness 保留、固定路由的 generation 控制限制與請求計數界線。設定輸出預算不一定能限制 provider 端輸出。

<a id="model-configuration"></a>

## 模型設定

`src/main/resources/application.yml` 匯入隨附的 `src/main/resources/models.yml`，選用 `google-primary`，並包含未啟用的 OpenAI 片段。金鑰使用環境變數佔位符。每個已設定項目都需要有效憑證與完整明確容量；缺少憑證、容量或 default-name mapping 會在啟動時失敗，而非靜默改選其他模型。

以下 keys 相對於 `session-agent.model`（entry keys 位於 `models.<name>`）：

| Key | 意義／選擇 |
| --- | --- |
| `default-model` | 新 session 的設定名稱，必須存在於 `models`；變更不會改變既有 session。 |
| `models.<name>` | Runtime 邏輯設定名稱，與 provider 模型 ID 不同。 |
| `provider` | Wire adapter；可使用 Google 與 OpenAI adapters。`openai` 不代表直接使用 OpenAI 憑證。 |
| `model-id` | Provider 模型識別碼；主機 gateway 的完整 `provider/id` 選擇由 runbook 定義。 |
| `api-key` | 選定 route 的直接 provider key 或 gateway bearer，私下注入；不是可用 OAuth access token 替代的欄位。 |
| `base-url` | 僅適用 OpenAI 的相容 base，包含 `/v1`，不含 `/chat/completions`；OpenAI 省略時預設為 `https://api.openai.com/v1`。Google 須省略：`google-genai` 設定非空白值會拒絕啟動。 |
| `context-window-tokens` | 必須為正數。實際模型容量；依選定模型核對，不沿用其他模型數值。 |
| `max-output-tokens` | 必須為正數。Runtime 輸出預算，不得超過模型公布上限；閘道是否轉送會影響限制效果。 |
| `reasoning-effort` | 僅適用 OpenAI 的選用請求設定；`google-genai` 設定非空白值會拒絕啟動。支援值依模型／gateway 版本決定。OMP 終端 model-role／reasoning 偏好不會設定 Runtime。 |
| `timeout` | 選用；提供時必須為正數。Google 的有效值必須為 `1..2147483647` 毫秒。這是單次 Runtime 請求逾時，不是整個工作的總時間上限。 |

直接使用 OpenAI API 時，選用項目的非機密格式如下：

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

這些是範例值，不是通用或未來模型／容量保證。若要與 bundled Google 並用，將 entry 以正確縮排加入 bundled `models.yml` 的 `session-agent.model.models`，與 `google-primary` 同層；不要只取消未縮排片段的註解。提供兩組憑證並重新建置映像檔。Docker Compose 在未追蹤的 `docker/.env` 設定 `OPENAI_API_KEY`；Google entry 仍存在時須保留 `GOOGLE_API_KEY`。啟用 bundled `models.yml` 中的項目需要重建映像檔。透過 session API 選擇已設定項目不需重啟，只影響之後受理的訊息，不改變已排隊／執行中的工作。

systemd OMP Gateway 不應複製直接 provider 範例，請依[模型格式](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#模型格式)及[設定來源與套用](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#設定來源與套用)。該 owner 定義 `SPRING_CONFIG_LOCATION` 選用的完整私有 `application.yml`、同目錄 `models.yml`、取代而非合併、排空／重啟／驗收與既有 session 遷移。

<a id="runtime-local-skills"></a>

## Runtime 本機 skills

Skills 為選用功能，預設停用。Keys 位於 `session-agent.skills`：

| Key | 已提交預設值 | 意義 |
| --- | --- | --- |
| `roots` | `[]` | 明確指定操作者管理的共享 roots；不自動探索 home、OMP 或 repository。 |
| `max-file-bytes` | `65536` | 必須 `>0` 且 `< Integer.MAX_VALUE`；完整 UTF-8 檔案 bytes（含 frontmatter）須符合實際限制。 |
| `max-skills` | `128` | 必須為正數；可探索的技能總數上限。 |

所有 Runtime 使用者都能讀取已設定 roots。Roots 與 references 必須是可信、由操作者維護的內容，Runtime 僅有 read/traverse 權限；容器採唯讀 bind mount。路徑與祖先不可使用 symlink。不得暴露 home、OMP 憑證、`.agents/skills` 維護／部署指南或其他秘密。Runtime 讀取提示內容，不執行 shell 命令／scripts；已載入 skill 不是 validator，也不證明遵循指引或報表正確。[Filesystem 安全契約](architecture/skills.zh-TW.md#filesystem-security-boundary)定義 provider 支援、fail-closed discovery，以及 privileged writer／開檔延遲界線。

<a id="skill-definition"></a>

### Skill 定義

Root 的直接子目錄為 skill，內含 `SKILL.md`。Frontmatter `name` 必須等於目錄名，只用小寫字母／數字與分隔連字號，上限 64 字元；`description` 非空，上限 1024 字元。檔案必須是有效 UTF-8，含 frontmatter 的完整 bytes 計入大小限制。重複／不合法 metadata 會拒絕啟動。[主機 skill 安裝範例](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用)負責 root 設定、檔案格式示例、權限、重啟與驗證順序。

Skill 內容及其 references 是使用者維護的輸入，不是 Runtime 內建業務規則。資訊涵蓋是否足夠、業務結果是否正確，由外部評估；再更新 skill 來源，依下方生命週期部署修改後的檔案。

<a id="skill-lifecycle"></a>

### Skill 重新載入、選擇與重播

- 新名稱、description 或 roots 需要重啟才更新 discovery metadata。每次新的 `readSkill` 或新提交的 `/skill:review 問題` 讀取當時 body／相對 reference bytes。SHA-256 是讀取證據，不是鎖版本。
- 同一 source message 重送不是新讀取；已提交 selection 的工作復原重用 durable content。見 [discovery／current-file reads](architecture/skills.zh-TW.md#discovery-and-current-file-reads)與[選擇／復原](architecture/skills.zh-TW.md#selection-and-recovery)。
- HTTP message 文字使用 `/skill:review 問題`；Slack 使用普通 `@bot /skill:review 問題`（已綁定 thread 也適用），不是註冊 Slack slash command。不帶問題的有效選擇仍要求模型確認載入。
- 無效選擇保留 USER／安全錯誤 audit，但不送模型、summary 或 token 估算：該工作零模型請求。自動工具讀取失敗仍是普通 TOOL observation。
- 一般 compaction 可摘要／移出已載入內容，不永久啟用、不自動還原；要再次載入須提交新的 `/skill:review`。

<a id="mcp-connections"></a>

## MCP 連線

MCP 屬於 Runtime，不是 OMP Gateway；gateway 路由不匯入 OMP MCP servers、工具或 skills。在 `session-agent.mcp.connections` 下設定零個或多個具名連線。Connection entry 與 MCP-wide timing keys 的層級不同，見[部署操作手冊 YAML](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#1-合併到生效的私有-applicationyml)。

**連線項目：`session-agent.mcp.connections.<name>`**

| Key／選擇 | 意義／預設值 |
| --- | --- |
| 連線名稱 | `[A-Za-z][A-Za-z0-9-]{0,31}`；工具名稱為 `{connectionName}_{rawToolName}`。 |
| `enabled` | 預設 `true`；`false` 仍須合法 URL，保留的 header placeholders 仍須可解析。 |
| `url` | 完整且絕對的 Streamable HTTP MCP endpoint，含路徑；不是網站／health URL、stdio 或舊 SSE 設定。 |
| `headers` | 選用的 provider-defined headers；不需驗證則省略，否則私下注入值。 |

**MCP 共用時間設定：`session-agent.mcp`（與 `connections` 同層，不放在個別連線內）**

| Key | 意義／預設值 |
| --- | --- |
| `refresh-interval` | `60s` 工具目錄更新，不重新載入設定／環境變數。 |
| `request-timeout` | `30s`。 |
| `initial-backoff` / `maximum-backoff` | `1s` / `60s`，各連線獨立重連退避。 |
| `shutdown-timeout` | `5s`。 |

所有 MCP 時間設定都必須為正值。`maximum-backoff` 的有效硬上限為 `60s`。

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

這是通用局部範例，不是完整 application config；合併至既有 root，不覆寫模型、Slack 或 datasource。使用 provider 規定的 header 名稱／值；bearer 值包含 `Bearer `。憑證不得寫進 URL 或版本庫，使用私有環境變數或密鑰管理服務。

不需要 MCP 時，省略 `session-agent.mcp.connections`。移除最後一個 entry 時，省略整個 `connections`，不要留下 `connections: {}` 或無法解析的憑證佔位符。Empty-map 載入／binding 行為依 loader／版本而異；Java properties constructor 接受實際的空 map，因此 YAML `{}` 啟動失敗不是由 source 證明的通用規則。仍建議省略，以避免 loader-dependent binding 問題。停用 entry 不會讓保留的 placeholders 變成選用；移除 token 時一併移除不再需要的 headers 或整個 entry。[MCP 操作順序](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#邊界與操作順序)負責停用、私有注入、排空／重啟、輪替與驗收。

Docker Compose 在未設定 `SESSION_AGENT_MCP_CONFIGURATION_JSON` 時以零連線啟動。等價設定示例為 `{"session-agent":{"mcp":{"connections":{"catalog":{"enabled":true,"url":"https://host/custom/mcp"}}}}}`。選用 headers 私下注入；Runtime 接收完整 URL／headers，不加入 provider-specific 行為。

未設定連線或所有 server 都不可用，不會阻止啟動。各連線獨立重連／更新；一個失敗不會隱藏另一個可用連線的工具。`GET /actuator/mcpConnections` 僅揭露安全狀態（`DISABLED`、`CONNECTING`、`AVAILABLE`、`DEGRADED`、`UNAVAILABLE`、`STOPPED`）、已探索工具數量及適用的安全 failure code/message，不揭露 URL、headers、tokens、provider 原始回應或例外細節。Health／discovery／直接 MCP 成功不證明模型能產生相容工具呼叫；請採經授權的 [model-to-tool 驗收](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#經授權的模型--mcp--history-實測)。
