# Session Agent Runtime

[English](README.md) | 繁體中文

以 Java 21、Spring Boot、Spring AI 與 PostgreSQL 建構的獨立、供應商中立對話服務。
Runtime 負責對話迴圈、持久化歷史、有序訊息工作與工具執行，不負責工具供應商的業務規則。

## 功能概覽

- **持久化對話：**一則受理的使用者訊息建立一個有序工作；同一 session 依 FIFO 處理，不同 session 可並行。
- **模型／工具迴圈：**載入已提交的上下文、呼叫所選模型、依序執行獲准的工具呼叫，再原子提交觀察結果。
- **獨立 MCP 連線：**具名 Streamable HTTP 伺服器各自重連與重新整理；工具 schema、參數與結果維持不透明。
- **有界工作與接續：**調查額度耗盡時詢問要繼續、整理保留的證據，或開始新問題。等待不是執行中的工作；取消採協作式處理。
- **本機 skills：**使用明確設定、由操作者持有的 `SKILL.md` 程式庫；支援明確的 `/skill:name` 選擇或模型要求讀取，不執行 shell。
- **選用 Slack 整合：**Socket Mode 收訊、每題進度、持久化回覆、原生 Markdown，以及 Markdown／CSV 附件。

Runtime 不加入供應商專屬工具 DTO、工具結果快取或業務驗證。一般最終回答為純文字，不套用 Runtime 自訂 schema。

## 文件導覽

從[問題索引](docs/README.zh-TW.md)直接前往負責該問題的章節。

| 需求 | 文件 |
| --- | --- |
| 使用 Runtime | [HTTP API 與 Slack 傳送](docs/http-api.zh-TW.md) · [對話指南](docs/conversations.zh-TW.md) · [Slack 指南](docs/slack.zh-TW.md) |
| 部署與維運 | [Gateway 部署與憑證管理](docs/gateway-deployment.md) · [設定指南](docs/configuration.zh-TW.md) · [部署維運指南](docs/operations.zh-TW.md) |
| 開發與驗證 | [套件模組與相依邊界](docs/package-architecture.zh-TW.md) · [開發指南：檢查、PostgreSQL 模式、測試職責與授權](docs/development.zh-TW.md) · [儲存庫規範](AGENTS.md) |

儲存庫的[部署 skill](.agents/skills/deploying-with-omp-gateway/SKILL.md) 連結這些操作指南與非機密範本。
它們是部署指示，**不是**可掛載為 Runtime 業務 skills 的程式庫。

## 建置與測試

需要 Java 21 與 Maven。一般套件使用 model/tool 替身，不需要 PostgreSQL、
model key／額度，也不連線實際 MCP 或 Slack。定向／完整套件命令、私人
PostgreSQL 測試設定與模式見[開發指南](docs/development.zh-TW.md)。執行完整
套件或真實模型、部署中 MCP／Slack 驗收前，須針對目前任務與測試範圍取得
明確授權；這些都不是一般測試。Spring AI 固定為預發布版本；版本與 adapter
限制見[設定指南](docs/configuration.zh-TW.md)。

## 本機執行

只選擇**一種**部署路徑；主機與容器 Runtime 不得共用連接埠，也不要變更既有資料庫的 Compose project／volume 身分。

### 主機 Runtime 搭配原生 OMP Gateway

建議方式是主機 Java Runtime、原生 OMP Auth Gateway，搭配既有且相容的共用
PostgreSQL。新安裝、既有服務更新，以及部署專用的角色、volume 身分、私有
設定與生命週期，請從[部署維運指南](docs/operations.zh-TW.md)進入其
runbook。

缺少資料庫、應用程式角色或憑證時，須停止並回報 blocker，或先取得明確
provisioning 授權。不得另建資料庫、將 cluster-superuser 憑證交給 Runtime，
或讓 Compose 接管共用 volume；停止共用資料庫不是 Runtime 生命週期步驟。
Gateway 轉送模型請求；Java Runtime 負責 session、工具與 agent 迴圈，不會
匯入 Gateway 的 skills 或 MCP 設定。

**Gateway 相容性很重要：**未修正的 OMP 18.12.6 會遺失工具的 `strict: false`。
須使用已驗證的修正版或包含修正的上游版本；相同版本字串或 health check
不能證明相容。請依[部署操作手冊的 Gateway 相容性與 provenance 要求](.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries)。

### 明確選用的獨立 Docker 替代方案

此明確選用方案會部署獨立 Runtime **及其自己的 PostgreSQL**，不是主機模式。
不得用它的 `start`／`restart` 操作或更新主機服務、與使用相同連接埠的主機
Runtime 並行，或重用共用 volume；`stop` 會停止整個 Compose project。憑證
不得提交至版本庫。指令、權限、記錄與保留規則請見[部署維運指南](docs/operations.zh-TW.md)。

## 最小 HTTP 範例

Runtime 執行後，將 loopback URL 設為實際連接埠（部署範例使用 `8090`）。提交此範例會消耗模型額度：

```bash
BASE_URL=http://127.0.0.1:8090
curl --fail-with-body "$BASE_URL/internal/messages" \
  -H 'Content-Type: application/json' \
  -d '{"sessionKey":"example","participantId":"local-user","sourceMessageId":"example-1","message":"Hello"}'
```

`202` 收件資訊回傳 `sessionId` 與 `messageJobId`。將它們代入下列路徑：

```text
GET /internal/message-jobs/{messageJobId}
GET /internal/sessions/{sessionId}/messages
```

重用 session key 可接續對話。來源訊息 ID 必須在 HTTP sessions 間保持唯一；更換 session key 不會重設來源身分。
完全相同的重送回傳原始收件資訊；改變參與者／文字則回傳 `409`。
`DONE` 只代表處理結束，不一定成功，也不代表 Slack 已傳送；須視情況檢查工作失敗欄位、歷史與傳送狀態。

完整 wire 格式、分頁、模型選擇、取消與傳送語意見 [API 參考](docs/http-api.zh-TW.md)。

## Skills 與產生的報告

Skills 在設定可信任根目錄前停用。啟動時掃描 metadata；後續讀取使用目前內容，限制 UTF-8 讀取大小且不跟隨 symlink。
HTTP 訊息可使用 `/skill:name question`，Slack 可使用 `@bot /skill:name question`。
這是訊息語法，不是已註冊的 Slack slash command。

使用者自行提供及維護 repo 外的 skills，依[skill 定義與安裝說明](docs/configuration.zh-TW.md#skill-definition)，在操作者管理的 root 下新增 `SKILL.md`。

Runtime 負責探索、受限讀取、歷史提交與重播，不維護業務指引。Skill 是否取得足夠資訊、結果是否正確，由外部評估，再由內容維護者據此更新。

報告產生工具只在支援附件的傳送路徑可用，一般 HTTP 工作沒有此路徑。儲存產物不代表已傳送。
等待／釐清不會釋出產物，後續工作也不能發布先前工作的檔案。
詳見[產物契約](docs/architecture/artifacts.zh-TW.md#route-and-creation-result)。

## 安全與操作限制

- 除非另有受保護的存取邊界，HTTP 維持 loopback-only。不得提交憑證、對話資料或診斷證據。
- 更新時不得重設資料庫。Migration 或回滾前先備份並評估 schema／binary 相容性；切換 JAR 不會撤銷 SQL。
- 取消不會強制中止遠端呼叫或回滾工具副作用。具副作用工具須在 adapter／供應商邊界提供冪等性。
- Slack 上傳確認與原生 client 呈現不同。Slack 下載時可能移除 CSV 的 UTF-8 BOM；解析相等不代表位元組相等。
- Slack finalization 結果不確定時不會自動重試。應保留證據供核對，而非重送問題或重設傳送狀態。
- 對話歷史與產生的產物沒有自動 TTL 或刪除機制。

## 參與開發

先閱讀[架構與模組邊界](docs/package-architecture.zh-TW.md)及[儲存庫規範](AGENTS.md)。
供應商 SDK 留在所屬 adapter，MCP 資料維持不透明；工具專屬指示放在工具描述或業務 skill，
不放入通用 Runtime prompt。修改契約時更新負責該契約的參考文件，讓 README 維持入口，而非第二份操作手冊。

README 與主題指南提供語言切換。修改契約時同步兩種語言，API 路徑、設定鍵與範例值須一致。
既有操作 runbook 保留原本語言與正式 URL。
