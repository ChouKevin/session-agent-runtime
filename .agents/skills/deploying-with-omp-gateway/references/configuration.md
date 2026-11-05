# 設定：模型、reasoning、Slack 與 MCP

[問題索引](../../../../docs/README.zh-TW.md) | [English question index](../../../../docs/README.md)

以下以 `$DEPLOY_ROOT=$HOME/.local/share/session-agent-runtime` 為例；先用已安裝的 unit 確認真正的 config location。設定只留在主機，repo 的 assets 是無秘密範本。所有主機路徑、模型與數值範例均不是 live availability 保證。

本頁負責實際私有設定、主機範例、前置→變更→套用→驗證，以及 Gateway compatibility／provenance。通用 keys、choices、skills reload/replay 與 MCP defaults 的定義見[設定參考](../../../../docs/configuration.zh-TW.md)（[English](../../../../docs/configuration.md)）。部署模式、OAuth 登入、共用 gateway 跨主機連線與 allowlist 見 [Gateway 部署與憑證管理](../../../../docs/gateway-deployment.md)。不要把 provider OAuth token 填入 gateway 的 `api-key`。

查找：[模型主機範例](#模型格式) · [設定來源與套用](#設定來源與套用) · [Slack](#slack) · [MCP 與 logging](#mcp-與-logging) · [本地 skills](#runtime-本地-skills選用預設停用) · [Gateway compatibility](#model-adapter-and-gateway-boundaries)。

## 模型格式

以下為私有 `models.yml` 的主機範例，不是完整 application config，也不是直接 OpenAI API 設定：

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

通用欄位意義由[模型設定表](../../../../docs/configuration.zh-TW.md#model-configuration)定義。此主機範例另有以下 route 選擇：

| 主機選擇 | 操作要求 |
|---|---|
| `default-model: omp-codex` | Runtime 設定名稱，不是 provider ID；既有 session 遷移見下節。 |
| `provider: openai` + gateway `base-url` | 使用 OpenAI-compatible adapter；base 包含 `/v1`，不含 `/chat/completions`。 |
| `model-id` | 從 OMP `/v1/models` 核對完整 `provider/id`，避免 bare ID 歧義。 |
| `api-key: ${OPENAI_API_KEY}` | 私有 environment 的 gateway bearer，不是 OAuth access token；fresh install 使用 `gateway.env`，既有部署沿用有效來源。 |
| 容量／輸出預算 | 從實際 catalog 核對模型容量與公布上限，不沿用其他模型數值；route 限制見下方 compatibility。 |
| `reasoning-effort: medium` | 本部署範例的選擇；支援值依模型／gateway 版本確認。 |

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

### 隔離模型驗收

真實模型驗收使用經授權的獨立 profile/session；model ID、reasoning、context
window 與 output limit 依測試模型的實際能力設定，不沿用其他模型的容量。
核對可用模型 catalog 與成功 response 的 model ID，不能只看 YAML。

保留 production model selection 與 allowance。新 Slack thread 尚未建立
session binding 時，使用預設已選測試 profile 的隔離 Runtime，並在第一則
訊息前完成設定。有效 `/skill:review` 即使不帶問題仍會發模型確認，不是
零模型 bootstrap。需要驗證 handoff 時，僅調整隔離環境的 allowance。
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

一般回答以完整原文 native `markdown` block 呈現；行內上限以原文加上存在時的
`"\n\n" + cache footer`，依 Java `String.length()` 計算，最多 10,000 個 UTF-16
code units（含 10,000），不是 Unicode code point 計數。超過時，只有原始回答的
UTF-8 bytes 存為 `answer.md`；固定說明通知保留 footer，檔案不含 footer。
不解析、拆分、修補 Markdown 或另呼叫模型摘要。Markdown／附件不受支援或
權限不足會明確 delivery failure，不靜默改純文字或截斷。

`createMarkdownReport`／`createCsv` 只對有受支援附件 route 的正常工作開放，
不對 compaction 開放；工具結果 `SAVED_PENDING_DELIVERY` 不是 Slack 已送達。
CSV 原字串留在 audit；輸出加 UTF-8 BOM、CRLF、全欄引號／雙引號 escaping，
公式開頭欄位加 apostrophe 並在工具結果揭露，不能保證所有試算表匯入模式皆安全。

Artifact、長回答 bytes、upload stages 與敏感 upload capability 存 PostgreSQL，
繼承對話／備份的存取及保留政策；本功能無 TTL／自動刪除或 public UUID URL。
`FINALIZING` 發生不明結果會保留 `UNCONFIRMED` 並顯示 delivery failure，
不自動再次 finalize、重新配置檔案或重跑模型；人工核對前不要重設狀態強制 retry。

## MCP 與 logging

先確認生效的 Runtime application config 與私有 environment 來源；設定不放在 Gateway 或 OMP client。通用格式、connection keys、defaults、Compose JSON 與安全 diagnostics 定義見[設定參考：MCP 連線](../../../../docs/configuration.zh-TW.md#mcp-connections)。

主機連線操作統一依 [MCP 操作指南](mcp.md)：前置辨識／備份 → 合併私有設定與注入憑證 → 停止新提交並排空 → 重啟 Runtime → 各連線 discovery 與經授權的 model-to-tool/history 驗收。該 owner 包含 java-code-intelligence 雙 reader 範例、systemd 注入、停用／輪換及失敗定位；只看 health 或 discovery 不算工具驗收。

移除最後一個連線時省略 `connections`，不要留下 `connections: {}` 或無法解析的 header 憑證；此 omission 建議避免 loader／版本相關的 empty-map binding 問題，不表示 Java constructor 拒絕實際空 map。`enabled: false` 不免除 URL／保留 placeholder 的有效性。完整定義與操作分別見上述兩個 owner，不在此維護第二份 MCP 範例。

主機範例以 JSON 寫到 systemd journal，不使用容器固定 `/app/logs` 路徑。用 `journalctl --user -u session-agent-runtime` 查詢；journal 的保留期依主機政策，不等於 repo Docker logging 的 7 日輪替設定。不要將完整 log 當作公開驗收附件。

## Runtime 本地 skills（選用，預設停用）

前置：依 [install.md](install.md) 確認部署權限與可信目錄；roots 與祖先不可用 symlink，僅由操作者維護，Runtime 只能 read/traverse，容器掛載維持 `:ro`。所有使用者均可讀取；不要放秘密、home、OMP credentials 或 `.agents/skills` 維護指南。Gateway 不管理這些檔案，Runtime 不執行 shell/scripts 或自動發現 repo skills。

變更：將以下**局部片段**合併至既有私有 application config 的同一 `session-agent` 節點，不另建重複 root、不覆寫模型、Slack 或 datasource。Keys/defaults 定義見[Runtime 本機 skills](../../../../docs/configuration.zh-TW.md#runtime-local-skills)：

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

建立前先核對[skill 定義與 metadata 限制](../../../../docs/configuration.zh-TW.md#skill-definition)：目錄名／name、description、UTF-8 與完整檔案大小都須合法；重複或無效 metadata 會拒絕啟動。完整 trust／filesystem 邊界見[架構契約](../../../../docs/architecture/skills.zh-TW.md#filesystem-security-boundary)。

套用與驗證：

1. 依 [operations.md](operations.md) 停止新提交並排空；roots、新名稱或 description 變更後重啟 Runtime，確認 startup discovery metadata。僅更改 body／reference 的新讀取不需重啟。
2. 經授權以 HTTP `/skill:review 問題` 或 Slack 普通訊息 `@bot /skill:review 問題` 驗證；不是 Slack slash command。不帶問題的有效選擇仍會發模型確認，不是零模型 bootstrap。
3. 依[重新載入、選擇與重播定義](../../../../docs/configuration.zh-TW.md#skill-lifecycle)區分新讀取、同一 source replay、已提交 selection 復原與 compaction；不要靠重送原訊息測試檔案更新。SHA-256 僅是讀取證據，不是鎖版本。
4. 依 [verification.md](verification.md) 分開核對已提交內容與真實模型行為；載入成功不是 validator 或遵循指引的證明。

### 安裝使用者自訂 skill

Skill 的內容來源由使用者自行管理，不隨 Runtime 提供業務指引。由操作者將
核准的 skill 目錄與 references 複製到設定的 root，例如上方的 `review`
目錄；不以 checkout symlink 代替複製，不額外掛載整個 repo。保留 operator
ownership 與 Runtime 唯讀存取，不放主機私有設定或 credentials。

依上述步驟確認 metadata、`skill://review` 讀取、`/skill:review` 選擇及
更新／重播語義；不需修改 Runtime core prompt、MCP tool names 或 Gateway
設定。Runtime 行為驗證見 [skills 驗收](verification.md#runtime-skills-驗收與-gateway真人-slack-分開)。

資訊是否查得完整、證據是否足夠及業務結果是否正確，由 skill 維護者在外部
檢測並修訂內容。Runtime 的載入通知與讀取紀錄只證明已提交的 bytes，
不替代該評估，也不替使用者自動更新 skill。

## Generic MCP connection reference

通用 connection 命名／工具名、完整 URL、headers、預設逾時／backoff、Compose `SESSION_AGENT_MCP_CONFIGURATION_JSON`、停用與 safe diagnostics 的唯一設定定義在[設定參考：MCP 連線](../../../../docs/configuration.zh-TW.md#mcp-connections)（[English](../../../../docs/configuration.md#mcp-connections)）。

主機私有連線、雙 reader 憑證、停用／輪換與 model-to-tool history 驗收直接見 [MCP setup and acceptance runbook](mcp.md)。本 heading 保留作為舊連結入口；不另維護重複的 MCP 設定或操作步驟。

## Model adapter and Gateway boundaries

一般 adapter 選擇、Spring AI **2.1.0-RC1** 的 release-candidate 狀態、Chat Completions `store=false`、私有 route-bound continuation／重播，以及 direct-provider／bundled image／session 選擇契約見[設定參考：Runtime 與閘道邊界](../../../../docs/configuration.zh-TW.md#runtime-boundaries)與[模型設定](../../../../docs/configuration.zh-TW.md#model-configuration)（[English](../../../../docs/configuration.md#runtime-boundaries)）。本節只擁有主機 Gateway compatibility／provenance 與固定路由限制。

M1→RC1 替換前必須排空執行中／retry jobs；舊 Google metadata-only continuation 無法由 RC1 重播，已完成歷史不需遷移。Runtime 設定 `.strict(false)`；保留的 fake-HTTP assertions 拒絕 `strict:true` 並比對範例 schema，但不證明 wire 上有明確的 `strict:false` 或獨立的選用欄位範例。工具 observation／final response 的提交及重播有對應 assertions；這些定義不是新的測試執行結果，也不能代替 Gateway 下游相容性驗收。

The currently pinned host-local OMP gateway does not run the OMP agent loop or add
its agent prompt, skills, or conversation history. It translates the caller's
messages/tools into the Codex protocol, manages OAuth and transport, and can apply
protocol/history normalization and internal retries. It is not a byte-for-byte
relay. Runtime's `modelCallCount` counts pre-dispatch reservations for gateway requests,
not proof of dispatched/billed requests or a bound on underlying Codex attempts.

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
