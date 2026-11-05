# 文件問題索引

[首頁](../README.zh-TW.md) | [English](README.md)

## 目的與範圍

依下方問題直接前往負責定義的章節。本索引只負責導覽，不複製設定、執行契約或部署程序。使用者指南說明可觀察行為；參考文件定義契約；操作手冊負責操作步驟。既有 runbook 保留原本語言，不表示另有英文譯本。

## 目錄

- [使用 Runtime](#使用-runtime)
- [設定與操作](#設定與操作)
- [開發與內部原理](#開發與內部原理)

## 使用 Runtime

首次使用：透過 [HTTP 提交訊息](http-api.zh-TW.md#訊息提交)、閱讀[對話循環](conversations.zh-TW.md#對話循環)，或[開始 Slack 對話](slack.zh-TW.md#開始或繼續對話)。[HTTP 參考](http-api.zh-TW.md#工作取消與歷史)負責公開 job／history／投遞欄位與[快取報告計算](http-api.zh-TW.md#快取報告)；[Slack 指南](slack.zh-TW.md#回答快取-footer-與檔案)負責使用者可見的回答與檔案。

| 問題 | 直接負責的章節 |
| --- | --- |
| 5. 何時可產生報告？編碼、限制與釋出如何定義？ | [Artifacts：route 與建立結果](architecture/artifacts.zh-TW.md#route-and-creation-result)；[編碼](architecture/artifacts.zh-TW.md#encoding-and-download-boundaries)；[限制與 release](architecture/artifacts.zh-TW.md#limits-identity-and-release) |
| 6. Slack 收訊、每題進度與投遞有何保證？ | [Slack 投遞：准入與停止交易](architecture/slack-delivery.zh-TW.md#admission-and-stop-transactions)；[進度所有權](architecture/slack-delivery.zh-TW.md#progress-ownership-and-reconciliation)；[已準備投遞與上傳](architecture/slack-delivery.zh-TW.md#prepared-deliveries-and-upload-transport) |

## 設定與操作

| 問題 | 直接負責的章節 |
| --- | --- |
| 8. 設定 keys 與選擇代表什麼？ | [設定：模型 keys 與選擇](configuration.zh-TW.md#model-configuration)；[Runtime／閘道邊界](configuration.zh-TW.md#runtime-boundaries)；[skills](configuration.zh-TW.md#runtime-local-skills)；[MCP 連線與時間設定層級](configuration.zh-TW.md#mcp-connections) |
| 9. 既有服務使用哪份設定？如何套用並驗證變更？ | [部署設定操作手冊：設定來源與套用](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#設定來源與套用) |

新主機從[部署方式與憑證](gateway-deployment.md#1-選擇部署方式)開始；既有服務使用[主機維運](operations.zh-TW.md#建議方式systemd-主機部署)。MCP 私有注入、停用、輪替與驗收由 [MCP 操作順序](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#邊界與操作順序)負責，不由通用 key 參考負責。Gateway build 相容性／provenance 由[部署設定操作手冊](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries)負責。

## 開發與內部原理

| 問題 | 直接負責的章節 |
| --- | --- |
| 1. 各模組負責什麼？允許哪些相依性？ | [套件架構：模組與邊界](package-architecture.zh-TW.md#模組與邊界)；[具名介面與相依性上限](package-architecture.zh-TW.md#具名介面與相依性上限) |
| 2. 訊息執行、額度、交接、取消與重試如何運作？ | [對話執行：有序模型與工具迴圈](architecture/conversation-execution.zh-TW.md#ordered-model-and-tool-loop)；[額度與收尾詢問](architecture/conversation-execution.zh-TW.md#allowance-and-wrap-up)；[重試與復原](architecture/conversation-execution.zh-TW.md#retries-and-atomic-recovery) |
| 3. 稽核歷史、模型 context、compaction 與用量有何不同？ | [歷史與用量：稽核與 context 准入](architecture/history-and-usage.zh-TW.md#audit-and-context-admission)；[checkpoints 與 compaction](architecture/history-and-usage.zh-TW.md#checkpoint-coverage-and-compaction)；[持久化 cache accounting](architecture/history-and-usage.zh-TW.md#durable-cache-accounting) |
| 4. Skills 如何 discovery、讀取、提交與復原？ | [Skills：discovery 與目前檔案讀取](architecture/skills.zh-TW.md#discovery-and-current-file-reads)；[選擇與復原](architecture/skills.zh-TW.md#selection-and-recovery)；[receipts 與持久化通知](architecture/skills.zh-TW.md#receipts-and-durable-notifications) |
| 7. 修改模組後，哪些測試負責提供證據？ | [開發：測試職責與位置](development.zh-TW.md#test-responsibilities-and-locations)；[整合／live 證據邊界](development.zh-TW.md#integration-and-live-evidence-boundaries) |

測試命令與授權由[開發流程](development.zh-TW.md#工具鏈與一般測試)負責，不在架構總覽維護。完整套件與 live 驗收仍須取得目前任務的明確授權。[儲存庫規範](../AGENTS.md)定義工程邊界。
