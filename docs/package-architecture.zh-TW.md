# 目前的套件架構

[首頁](../README.zh-TW.md) | [English](package-architecture.md) | [問題索引](README.zh-TW.md)

本指南描述儲存庫目前存在的正式環境程式套件。原始碼根目錄為 `src/main/java/com/java/system/sessionagent/`。以下 11 個頂層套件是封閉的 Spring Modulith 模組。子套件表達目前程式碼的職責；模組不會為了補齊某種模式而增設空的層。


<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責模組、具名介面與 dependency ceiling；不是執行、skills、artifacts、Slack 投遞或測試命令的詳細契約 owner。請由下方舊入口直達主題文件。

<a id="contents"></a>

## 目錄

- [模組與邊界](#模組與邊界)
- [具名介面與相依性上限](#具名介面與相依性上限)
- [對話執行](architecture/conversation-execution.zh-TW.md)
- [歷史與用量](architecture/history-and-usage.zh-TW.md)
- [共用 PostgreSQL 介面](#shared-postgresql-interface)
- [Provider 與模組隔離](#provider-and-module-isolation)
- [Skills](architecture/skills.zh-TW.md)
- [Artifacts](architecture/artifacts.zh-TW.md)
- [Slack](architecture/slack-delivery.zh-TW.md)
- [測試職責與位置](development.zh-TW.md#test-responsibilities-and-locations)

## 模組與邊界

| 模組與目前套件 | 職責與邊界 |
| --- | --- |
| `conversation` — `domain`、`application`、`port/in`、`port/out` | 負責 session、message、job、history、context，以及 model/tool loop 規則。`domain` 存放供應商中立的狀態與不變條件；`application` 協調 intake、查詢與 message-job 處理；`port/in` 存放由 adapter 與 worker 呼叫的契約；`port/out` 存放由模組外部實作的 model、storage 與 telemetry 契約。 |
| `tool` — `domain`、`port` | 定義通用工具名稱、定義、快照、呼叫、執行器與輸出。工具名稱、schema、arguments 與結果都屬於供應商資料；此模組不依賴 MCP 或 model SDK。 |
| `skill` — `domain`、`port`、`configuration`、`adapter/filesystem`、`adapter/tool` | 負責僅於啟動時載入的 skill metadata、有界限的目前檔案讀取，以及供應商中立的 `readSkill` tool。Bootstrap 負責 library lifecycle，並將 skill 與 MCP leaves 組合成唯一的 catalog。 |
| `artifact` — `application`、`port` | 負責有界限的第一方 Markdown/CSV 編碼、受 route gate 控制的建立工具，以及持久化 artifact 儲存契約；不依賴 provider SDK 或 conversation。 |
| `model` — `adapter/springai` | 將供應商中立的 conversation model 契約，對應至已設定的 Spring AI OpenAI 與 Google GenAI adapters，包括 prompts、history projection、tool callbacks、usage 與 continuation data。Provider types 留在此 adapter 內。 |
| `mcp` — `domain`、`configuration`、`adapter/sdk`、`adapter/actuator` | 負責具名 MCP connection state 與 properties、SDK connection lifecycle、discovery、tool routing/adaptation、refresh/reconnect，以及安全的 actuator diagnostics。原始 MCP metadata、schemas 與 results 不會變成 Runtime DTO。 |
| `storage` — `adapter/postgres`、`adapter/postgres/jdbc` | 以 PostgreSQL、Flyway 管理的 tables、MyBatis mappers、有序 history、job claims 與 history 原子寫入，實作 conversation persistence。範圍狹窄的 JDBC export 負責 PostgreSQL adapters 共用的 intake admission 與 UUID mapping。 |
| `web` — `adapter/http` | 為 message intake、job polling、session history、validation 與 error handling 轉接 HTTP requests 與 responses。MCP diagnostics 由 `mcp/adapter/actuator` 負責。 |
| `worker` — `application`、`configuration` | 輪詢佇列中的 message jobs、認領並續租 ownership、呼叫處理流程、回報完成／重試／ownership-loss／recovery 結果，並綁定 worker properties。 |
| `slack` — `domain`、`application`、`port/out`、`adapter/sdk`、`adapter/postgres`、`configuration` | 處理 Slack intake、filtering、delivery lifecycle、Socket Mode、Slack SDK calls，以及自身的 idempotency、thread binding、delivery persistence 與 properties。Provider details 留在所屬 adapter。 |
| `bootstrap` — `configuration`、`adapter/metrics` | 透過 Spring configuration 組合應用程式，並提供 Micrometer telemetry adapter。Bootstrap 沒有匯出的具名介面；composition classes 是啟動 wiring 的內部實作。 |

各模組的 `package-info.java` 檔案是封閉 dependency allowlist 的事實來源；`NamedInterface` annotations 是 exports 的事實來源。`module :: named-interface` 表示目標模組公開具名介面，例如 `conversation :: port.out`；單獨的模組名稱，例如 `web`，表示目標模組的 unnamed/root interface。允許的 dependency 是上限，不代表程式碼目前有使用。

## 具名介面與相依性上限

完整的 named-interface map 刻意集中在 `ApplicationModulesTest` 的一項 assertion 中。以下是唯一公開的 named exports：

| 模組 | 具名介面 |
| --- | --- |
| `tool` | `domain`；`port` |
| `skill` | `domain`；`port`；`configuration`；`adapter.filesystem`；`adapter.tool` |
| `artifact` | `application`；`port` |
| `conversation` | `domain`；`application`；`port.in`；`port.out` |
| `model` | `adapter.springai` |
| `mcp` | `configuration`；`adapter.sdk`；`adapter.actuator` |
| `storage` | `adapter.postgres`；`adapter.postgres.jdbc` |
| `web` | 無 |
| `worker` | `application`；`configuration` |
| `slack` | `domain`；`application`；`port.out`；`adapter.sdk`；`adapter.postgres`；`configuration` |
| `bootstrap` | 無 |

下表保留完整的 allowed dependency map，以差異欄列出未使用的 allowances，不重複 actual map；actual 等於 allowed 減去差異欄，省略同一模組內的 imports。未使用的 allowances 是刻意保留的：`artifact` 可使用 `tool :: domain`，`skill` 可使用 `tool :: domain`，而 `bootstrap` 可使用未指定 named interface 的 `web` module。其餘 allowances 目前都由正式程式碼使用。

| 模組 | 允許的相依性 | 未使用的 allowance（actual = allowed − 此欄） |
| --- | --- | --- |
| `tool` | 無 | 無 |
| `skill` | `tool :: domain`；`tool :: port` | `tool :: domain` |
| `artifact` | `tool :: domain`；`tool :: port` | `tool :: domain` |
| `conversation` | `tool :: domain`；`tool :: port`；`skill :: domain`；`skill :: port` | 無 |
| `model` | `conversation :: domain`；`conversation :: port.out`；`tool :: domain`；`tool :: port`；`skill :: domain` | 無 |
| `mcp` | `tool :: port` | 無 |
| `storage` | `conversation :: domain`；`conversation :: port.in`；`conversation :: port.out`；`tool :: domain`；`tool :: port`；`skill :: domain`；`artifact :: port` | 無 |
| `web` | `conversation :: domain`；`conversation :: port.in`；`tool :: domain`；`slack :: domain`；`slack :: port.out`；`skill :: domain` | 無 |
| `worker` | `conversation :: domain`；`conversation :: port.in`；`conversation :: port.out` | 無 |
| `slack` | `conversation :: domain`；`conversation :: port.in`；`storage :: adapter.postgres.jdbc`；`artifact :: port` | 無 |
| `bootstrap` | `tool :: port`；`artifact :: application`；`artifact :: port`；`skill :: port`；`skill :: configuration`；`skill :: adapter.filesystem`；`skill :: adapter.tool`；`mcp :: configuration`；`mcp :: adapter.sdk`；`mcp :: adapter.actuator`；`conversation :: application`；`conversation :: domain`；`conversation :: port.in`；`conversation :: port.out`；`model :: adapter.springai`；`storage :: adapter.postgres`；`storage :: adapter.postgres.jdbc`；`web`；`worker :: application`；`worker :: configuration`；`slack :: domain`；`slack :: application`；`slack :: port.out`；`slack :: adapter.sdk`；`slack :: adapter.postgres`；`slack :: configuration` | `web` |

<a id="shared-postgresql-interface"></a>

## 共用 PostgreSQL 介面

`storage :: adapter.postgres.jdbc` 只公開共用 PostgreSQL integration components：`PostgresIntakeCapacity` 與 `PostgresUuidTypeHandler`。Slack 依賴此狹窄 export，而非 `storage :: adapter.postgres` 的 conversation mapper/store export。直接建構的 MyBatis factories 與 bootstrap 的 MyBatis configuration 都註冊相同的 UUID handler；Slack 沒有自己的 UUID implementation。

<a id="provider-and-module-isolation"></a>

## Provider 與模組隔離

Provider SDKs 應留在所屬 adapters 與 bootstrap composition。Google 與 OpenAI SDK/provider-specific Spring AI types 只允許出現在 `model.adapter.springai` 與 `bootstrap`，不能出現在 model package 的其他位置。`RuntimeBoundaryTest` 會對兩個 providers 驗證此狹窄邊界。Conversation domain、application、ports、generic tool module、storage、web 與 worker 不會依賴 model、MCP、Google、OpenAI 或 Slack SDK types。MCP connections 彼此獨立：某個具名 server 無法使用時，不得隱藏其他可用 server 的 tools，也不得阻止啟動。Tools 仍依 model 提供的順序逐一執行，並透過 generic tool 與 conversation contracts 傳回不透明的 observations。

## 稽核歷史與模型准入

完整契約：[稽核歷史與模型准入](architecture/history-and-usage.zh-TW.md#audit-and-context-admission).

## 測試職責與位置

完整契約：[測試職責與位置](development.zh-TW.md#test-responsibilities-and-locations).

## 對話執行契約

完整契約：[對話執行契約](architecture/conversation-execution.zh-TW.md#ordered-model-and-tool-loop).

### 持久化 Markdown 報告與 CSV 匯出

完整契約：[持久化 Markdown 報告與 CSV 匯出](architecture/artifacts.zh-TW.md#route-and-creation-result).

## 限額、重試與復原

完整契約：[限額、重試與復原](architecture/conversation-execution.zh-TW.md#allowance-and-wrap-up).

## Slack 內部契約

完整契約：[Slack 內部契約](architecture/slack-delivery.zh-TW.md).

### 准入與停止交易

完整契約：[准入與停止交易](architecture/slack-delivery.zh-TW.md#admission-and-stop-transactions).

### 進度所有權與協調

完整契約：[進度所有權與協調](architecture/slack-delivery.zh-TW.md#progress-ownership-and-reconciliation).

### 已準備投遞與上傳傳輸

完整契約：[已準備投遞與上傳傳輸](architecture/slack-delivery.zh-TW.md#prepared-deliveries-and-upload-transport).

Skills 的 discovery、read、receipt 與復原見 [Skills](architecture/skills.zh-TW.md)。
