# 持久化 Markdown 與 CSV artifacts

[問題索引](../README.zh-TW.md) | [架構總覽](../package-architecture.zh-TW.md) | [English](artifacts.md)

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責 artifact route eligibility、encoding、bounds、identity、association 與 release；不負責檔案 upload、Slack delivery、公開下載 endpoints 或測試執行。

<a id="contents"></a>

## 目錄

- [Route 與建立結果](#route-and-creation-result)
- [編碼與下載邊界](#encoding-and-download-boundaries)
- [限制、識別與 release](#limits-identity-and-release)
- [可信 invocation 與關聯](#trusted-invocation-and-association)

<a id="route-and-creation-result"></a>

## Route 與建立結果

一般 jobs 若設定了受支援的 attachment route，`createMarkdownReport(title, content)` 與 `createCsv(title, columns, rows)` 會將持久化 artifacts 儲存在 PostgreSQL。它們不會 upload files，也不宣稱已 delivery；回傳結果為 `SAVED_PENDING_DELIVERY`。第一個 route adapter 會在 Slack 已設定時辨識具有原始持久化 thread binding 的 accepted Slack jobs。沒有該 route 的 HTTP jobs 不會公開建立工具。Compaction、intent classification 與 boundary wrap-up requests 都沒有 tools。

<a id="encoding-and-download-boundaries"></a>

## 編碼與下載邊界

Markdown bytes 是原封不動的 UTF-8，不會解析。CSV 接受 string headers 與 rectangular string rows，輸出 UTF-8 BOM、CRLF，為每個 field 加上引號並將內嵌引號加倍。若儲存格第一個非空白字元為 `=`、`+`、`-` 或 `@`，或開頭為 tab/CR/LF，會加上 apostrophe prefix。Results 會說明此 spreadsheet-safe transformation；這不保證所有 import behaviors 都安全。原始 input 仍保留於 tool-call audit。Runtime storage 與傳給 Slack upload adapter 的 bytes，和 Slack-hosted downloads 是不同邊界：Slack 可能在提供下載時省略 CSV 的 UTF-8 BOM。應在每個 boundary 記錄 raw hashes 與 lengths；解析後等價的 CSV 不能證明下載保留了完全相同的 bytes。

<a id="limits-identity-and-release"></a>

## 限制、識別與 release

Limits 為每個 generated file 1 MiB serialized bytes、每個 job 10 files／5 MiB、100 個 CSV columns、10,000 個 CSV data rows，以及 title 120 個 Unicode code points。Filenames 是不可變的 UUID 加上 `.md` 或 `.csv`；titles 不會選擇 paths。Stable invocation replay 會回傳同一個 artifact；相同 identity 若 input 改變則會被拒絕。只有在 atomic assistant/tool batch 中與 exact successful tool observation 關聯的 artifacts 才能 release。Release 由成功提交且明確標示為 `ANSWER` 的 assistant completion 觸發，早於 Slack 確認投遞；只代表可供投遞，不代表 transport 成功。Waiting questions 與 clarifications 絕不 release artifacts。Paused-job artifacts 會維持 private；後續只產生 report 的 job 會使用保留的 evidence 建立自己的 artifacts，且只能 release 自己擁有的 artifacts。Staged orphans 與 cancelled/failed jobs 不得 publish。Artifacts 繼承 conversation retention；此功能既不提供 public UUID downloads，也不會自動刪除。Attachment upload/delivery 屬於獨立的 adapter responsibility。

<a id="trusted-invocation-and-association"></a>

## 可信 invocation 與關聯

工具 executors 收到受信任的 `ToolInvocation`：其中包含四欄位、穩定的 `ToolExecutionContext`，以及分開保存的 worker/claim-number lease authority。只有 Runtime 能建立 production invocations，且會先計算實際 model request。Catalog snapshots 可接收 job/session identifiers 以判定 route eligibility，無須依賴 conversation types。MCP 會忽略 invocation metadata；第一方 skill reads 使用其 fenced claim 與已計數的 request identity，啟動持久化 operational attempts。Artifact 與 skill receipts 是內部 tool-port metadata，不會暴露給 model 的 JSON。PostgreSQL 會比對成功的 artifact output、原始 arguments、invocation ordinal、tool identity 與 job ownership，並將它們關聯至實際 TOOL event。只有 ANSWER disposition 才能 release，且只屬於該已完成的 job；WAITING/clarification 不會 release artifacts，也不會轉移前一個 job 的 ownership。編碼、檔案界限與 release semantics 見[artifact 契約](artifacts.zh-TW.md#limits-identity-and-release)。建立 artifact 不會產生 Slack side effect，也不接受 model 提供的 channel/path/capability。

附件傳輸見 [Slack 投遞](slack-delivery.zh-TW.md#prepared-deliveries-and-upload-transport)。
