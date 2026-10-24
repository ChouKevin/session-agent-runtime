# 設定：模型、reasoning、Slack 與 MCP

以下以 `$DEPLOY_ROOT=$HOME/.local/share/session-agent-runtime` 為例；先用已安裝的 unit 確認真正的 config location。設定只留在主機，repo 的 assets 是無秘密範本。

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

## Slack

私有 `runtime.env`：

```dotenv
SESSION_AGENT_POSTGRES_PASSWORD=由本機產生的資料庫密碼
SLACK_APP_TOKEN=由Slack產生的App層級token
SLACK_BOT_TOKEN=由Slack產生的Bot token
SLACK_BOT_USER_ID=Bot使用者ID
```

這是欄位示意，不是可直接登入的範例。實際 token/password 不提交。Slack 欄位全空可停用整合；啟用時三個必須齊全。

- App-level token 要有 `connections:write`，啟用 Socket Mode。
- Bot 至少有 `app_mentions:read`、`chat:write` 並訂閱 `app_mention`，把 bot 加入目標頻道。
- 不帶 mention 的 thread 續聊／DM 需要相應 message events 與 scopes：`message.channels` / `channels:history`、`message.groups` / `groups:history`、`message.im` / `im:history`、`message.mpim` / `mpim:history`。只開需要的對話範圍；新增 scopes 後依 Slack 要求重新安裝／授權。
- 可用 `auth.test` 驗證 token 並取得 `user_id`，回報只保留狀態與 ID，不輸出 token、headers 或 Socket URL。
- 在已綁定 thread 使用 `@bot stop` 或 `@bot 停止工作`；停止當時該 thread 未完成工作，不影響後來新提交。採合作式取消，已發生的工具副作用不會回滾。

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
