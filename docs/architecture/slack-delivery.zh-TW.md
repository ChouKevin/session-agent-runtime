# Slack 投遞內部契約

[問題索引](../README.zh-TW.md) | [架構總覽](../package-architecture.zh-TW.md) | [English](slack-delivery.md)

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責 Slack intake/stop transactions、progress fencing、prepared delivery 與 upload transport；Slack 指南負責設定與可觀察呈現，HTTP 負責 delivery query fields。本頁不授權修改 app scopes、部署或呼叫 Slack。

<a id="contents"></a>

## 目錄

- [准入與停止交易](#admission-and-stop-transactions)
- [進度所有權與協調](#progress-ownership-and-reconciliation)
- [呈現與完成判定](#presentation-and-completion)
- [已準備投遞與上傳傳輸](#prepared-deliveries-and-upload-transport)
- [安全、保留與不確定結果](#security-retention-and-uncertainty)

本節負責 Slack 准入、fencing、復原與傳輸不變條件。設定與可觀察行為請參閱 [Slack 指南](../slack.zh-TW.md)；投遞查詢欄位請參閱 [HTTP API](../http-api.zh-TW.md#工作取消與歷史)。

<a id="admission-and-stop-transactions"></a>

## 准入與停止交易

Bootstrap 將同一個 `PostgresIntakeCapacity` instance 提供給 `PostgresConversationStore` 與 `PostgresMessageSubmission`。它擁有已設定的 limit、global transaction-scoped advisory lock，以及計算 `PENDING`、`WORKING` 與 `RETRY` jobs 的 query。Intake adapters 會在 duplicate lookup 前取得此 lock，並只為新 jobs 檢查 capacity；檢查使用與後續 writes 相同的 data source 與 transaction。

Slack 仍擁有自己的 receipts、immutable thread binding 與 delivery notices。Accepted jobs 及其 receipts/binding/queued notice 會原子提交；若 queue 已滿，則提交 BUSY receipts 與 busy notice，不建立 session 或 job。Slack 在巢狀 conversation intake 前檢查 capacity，且不以 throw 方式拒絕，避免 transaction 被標為 rollback-only 而丟棄 BUSY。Core intake 保留其 `QueueFullException` 契約。即使 capacity 已滿，accepted duplicates 仍解析至原 job；capacity 恢復後，BUSY duplicates 仍保持 BUSY。獨立的 delivery worker 與 provider-neutral conversation contracts 不變。

- HTTP 與 Slack 共用一個在 bootstrap 設定的 PostgreSQL 准入元件，負責容量上限、交易範圍 advisory lock 與未完成工作查詢。兩條路徑都先解析重複訊息，再檢查新工作的容量。
- Slack 收訊會原子提交已受理工作、事件／邏輯訊息收據、不可變的討論串綁定與排隊通知；或在不建立 session／工作的情況下，提交 BUSY 收據與忙碌通知。不會拋出例外的容量檢查在巢狀對話收訊之前執行，因此佇列已滿不會使 BUSY 交易進入 rollback-only。即使容量已釋出，BUSY 重送仍維持 BUSY；投遞重試與收訊准入及模型處理彼此獨立。
- Stop 受理會原子擷取並要求取消不可變 session 綁定中所有 `PENDING`、`RETRY` 或 `WORKING` 工作，儲存事件／邏輯訊息收據，並排入一則持久化的 `STOP` 通知。其他 session 與之後受理的提交不受影響。
- 同一 Slack 訊息的事件重播或重疊的 `app_mention`／`message` 事件，不會再次取消工作或重複通知，即使後續已有新工作提交也一樣。交易先取得共用收訊鎖，再依使用者訊息順序鎖定所有快照工作，之後才配置取消歷史序號。Worker 與 HTTP 取消維持先鎖 job 再鎖 session 的順序。

<a id="progress-ownership-and-reconciliation"></a>

## 進度所有權與協調

每個 question 的 status 完全由 Slack 負責，不屬於 conversation history。V9 新增 `slack_question_progress`、`slack_progress_workspace`，以及一致的 database job/answer-part observation view。新 `QUEUED` delivery 確認後，會在同一 transaction 註冊 progress row；有界限的 recovery discovery 會略過已完全 delivery 的歷史 terminal work。Composite foreign key 會將 exact job、source identity 與已確認的 notice timestamp 綁在一起，絕不只指向 thread 中最新的 job。只有未過期的 processing ownership 才能進入 active state。一般成功的 `ANSWER` completion，其 base observation 在完整 prepared delivery set 確認為 `SENT` 前維持 `DELIVERING`。V11 先呈現 pending skill presentations，再將 `DONE`/`WAITING` 顯示為 terminal `WAITING_USER`，但 base observation 為 processing 或 delivery failure 時除外；等待不是報告送達。

`SlackProgressWorker`/`SlackProgressLifecycle` 獨立於 answer delivery 執行。Claims 以 worker identity、claim number、lease 與 observed revision fencing。Durable workspace gate 由 replicas 共用。HTTP 在 database transactions 外執行。若缺少原始 question link，使用一個 SDK `chat.getPermalink` call，依 source channel/message 使用現有 permalink parser 驗證 provider URL，再於 claim 下快取。下一次通過 gate 的 claim 會在 `chat.update` 前重新觀察最新 job；單一 claim window 內絕不執行兩次 outbound calls。Thread lookup 仍將 threaded permalink 解析至 root；question-link validation 則檢查特定的 path message，並原樣保留 provider 的 thread query。

獨立的 retry/cooldown path 不會建立 model requests、conversation events 或替代 notices。Progress failures 不會讓 answer delivery 失敗或遭到抑制。Defaults 為 2s polling、30s unchanged-state refresh 與 2s workspace spacing；使用 coalescing 與持久化的 `Retry-After` delays，不保證 latency。Terminal updates 在首次 acknowledgement 後保留一個 lease-window reconciliation pass；過期的本地 claims 不能確認成功。Slack 沒有 conditional remote write，因此已接受但延遲的 requests 仍可能讓可見文字短暫倒退。Runtime 完全 outage 時，舊 UTC observation 會維持不變，不代表目前仍活著。Replay/restart 會復原相同的已確認 notice 與快取的 provider permalink，不會猜測 workspace URL。

- 原始問題 permalink 會依原始 channel/message 驗證並持久化快取，包含 thread 查詢參數。Canonical root 連結可能包含 `thread_ts`，其值等於該 root 訊息的 timestamp；回覆連結則解析至所查詢討論串的 root。兩種格式都嚴格驗證 Slack host、路徑、timestamp 與相符 channel；問題連結仍必須指向確切的來源訊息。
- 獨立的一次 claim 執行查詢，再由後續 claim 更新通知：單一 claim 不會耗用兩個 Slack 呼叫時段。
- 準備、等待模型及查找工具等作用中狀態需要目前有效的處理 lease。既有取消／處理失敗優先順序維持不變；恢復流程不會重新開啟歷史上已完整投遞的問題。
- Progress 使用既有 delivery lease、重試／退避與嘗試上限，但採用獨立排程器。來源重播／重新啟動沿用已確認的通知時間戳與快取的問題連結；初次發文結果不明不代表可以再發一則 progress 通知。
- [`chat.update`](https://docs.slack.dev/reference/methods/chat.update/) 只能更新 bot 自己的訊息，且遠端沒有 compare-and-swap。Claim/version fencing 拒絕過時的本機確認，但已接受的延遲請求仍可能短暫覆寫較新的遠端文字。終止狀態協調至少等待 delivery lease 的時間（預設 30s，長於 25s 呼叫逾時）才退場。這不保證只發送一次，也不保證遠端畫面單調更新。

<a id="presentation-and-completion"></a>

## 呈現與完成判定

Slack 會將最終 assistant text 作為單一完整的 native Markdown block（固定的 accessible notification）呈現；若 answer + two-newline separator + cache footer 超過 10,000 UTF-16 units（同時也是保守的 code-point bound），則改用 exact UTF-8 `answer.md`。Runtime notices 維持純文字。Typed ordered delivery items 與 released artifact set 會在任何 parent completion 前以原子方式備妥。PostgreSQL 擁有 upload capabilities、file IDs、stages 與 delivery claim fence。每次 allocation/byte-upload/finalization 都受 renewed lease 限制；成功 stage 會 release 並重新排程，不會在 HTTP 呼叫期間持有 database transaction。Finalization intent 會先持久化；尚未確認的 recovered intent 狀態為 `UNCONFIRMED`，絕不自動重送。Parent `SENT` 要求每個 text item 與 upload 都確認為 `SENT`。Slack SDK 與隔離的 no-redirect/no-credential byte transport 留在 `slack/adapter/sdk`；conversation final text 維持 provider-neutral。V10 的 progress view 會在單一 statement snapshot 中觀察完整的 prepared set：一般成功的 `ANSWER` completion，其 base observation 會保持 `DELIVERING`，直到 parent、text/file items 與每個 FILE upload 都是 `SENT`。缺少 preparation 或 upload evidence 不代表完成；`FAILED`/`UNCONFIRMED` upload evidence 會產生 `DELIVERY_FAILED`。Cancellation 與 processing-failure precedence 不變。V11 先呈現 pending skill outcomes，再為 `DONE`/`WAITING` 套用 terminal `WAITING_USER`，但 base observation 為 processing 或 delivery failure 時除外。Discovery 重用 delivered predicate，因此不會為 generic progress 重新開啟歷史上已完成的 questions；pending skill presentations 仍可能需要 acknowledgement。Progress updates 仍使用各 question 自己的 queued notice 與獨立 executor。

<a id="prepared-deliveries-and-upload-transport"></a>

## 已準備投遞與上傳傳輸

- Delivery 先在 PostgreSQL 快照所有必要文字／檔案項目與已釋出的工具 artifacts，之後才確認任何項目。所有必要項目都確認前，父項目不能成為 `SENT`。自動建立的 `answer.md` 使用與工具 artifacts 分開且可確定的 job 識別資料。附件失敗不會重新產生回答或重跑模型。
- V10 將已持久化的 V9 delivery parts 保留為完整的純項目準備集合，保留其識別資料與已送出確認。進行中的 delivery 只續送尚未送出的項目，不會重新產生或重新拆分為整篇回答呈現格式。
- `SlackPostgresDeliveryStore` 在 prepared-item transaction 中使用 `ConversationQueryPort.findJob`；實際 query service 在相同 data source 上委派至 `ConversationStore.readJob`。`SlackCacheReportPresentation` 格式化這份中立 report，不會另行查詢 provider 或 aggregation。[快取語意](../http-api.zh-TW.md#快取報告)與 [footer 適用範圍／呈現](../slack.zh-TW.md#快取-footer)各有其負責文件。已準備 footer 位元組與 released artifact set 會在 retries/reclaim/restart 後保留，不新增 accounting writes。Once-per-job 指持久化組合 delivery，不代表解決遠端文字發文確認結果不明的情況。
- 上傳會持久化 `PREPARED` → `ALLOCATED` → `UPLOADED` → `FINALIZING` → `SENT`，並在有界操作間釋出所有權。只有已明確確認為安全拒絕，且有正向證據表明為文件所述的 rate-limit 拒絕時，才遵守 `Retry-After`；結果不明的完成錯誤即使有時間標頭，也不代表可重播。
- 權限／類型／過期 capability 錯誤會終止投遞。Finalization 意圖在唯一一次 Slack 分享呼叫前提交。回應遺失、確認提交失敗，或 finalization 周圍的 owner 過期，都會成為 `UNCONFIRMED`（父項目為 `FAILED`），絕不盲目再次 finalize 或建立新檔案。可能需要操作人員協調處理；這不保證 Slack 只分享一次，既有初次文字發文結果不明的情況維持不變。
- 敏感 upload capabilities 只為分階段傳輸而持久化，只接受 HTTPS `files.slack.com/upload/`，且不出現在診斷資訊中。位元組上傳 client 沒有 bot credentials，也不跟隨 redirect。[權限、保留與協調邊界](../slack.zh-TW.md#權限與不確定結果)仍由操作指南負責。

<a id="security-retention-and-uncertainty"></a>

## 安全、保留與不確定結果

不支援的 Markdown/file permission errors 是 delivery failures，不會改走 parser、splitting 或 plain-text fallback。`files:write` 及任何 app reinstall/reauthorization 都需要另外核准；Runtime 不新增 `files:read` reconciliation。Artifact bytes、final-answer upload bytes 與敏感 capabilities 會繼承 PostgreSQL/backup access 與 conversation retention。此功能不增加自動 expiry/deletion 或 public artifact URL。不確定的 finalization 會保留供 operator reconciliation，絕不由另一個 model turn 或自動 finalize/reallocation 修補。
