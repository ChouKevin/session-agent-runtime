# Slack 整合

[首頁](../README.zh-TW.md) | [English](slack.md) | [問題索引](README.zh-TW.md) | [HTTP API 參考](http-api.zh-TW.md)

Slack 是選用的訊息接收與投遞 adapter；對話處理仍維持供應商中立。本指南負責 Slack 設定、使用與可觀察行為。[HTTP API 參考](http-api.zh-TW.md#工作取消與歷史)負責投遞狀態欄位與[共用快取語意](http-api.zh-TW.md#快取報告)；[Slack 投遞內部契約](architecture/slack-delivery.zh-TW.md#admission-and-stop-transactions)負責交易、fencing、復原及上傳傳輸。

## 連線與 app 設定

當 `SLACK_APP_TOKEN`、`SLACK_BOT_TOKEN` 與 `SLACK_BOT_USER_ID` 全部未設定時，Slack 功能停用。啟用 Socket Mode 必須同時設定三者；只設定部分值會導致啟動失敗。Token 應放在部署環境或 secret manager，不可提交至版本控制。

1. 在 Slack app 啟用 Socket Mode，並訂閱 `app_mention` 及公開頻道、私人頻道、MPIM 與直接訊息的 message events。
2. 視需要授予 `app_mentions:read`、`channels:history`、`groups:history`、`mpim:history` 與 `im:history`，並授予傳送回覆及更新 bot 自身通知所需的 `chat:write`。

Socket Mode 負責接收事件與確認事件封包；獨立 worker 輪詢資料庫中的持久化 delivery，並透過 Slack Web API 傳送。Runtime 沒有公開 Slack callback endpoint、signing secret、OAuth 流程或 Slack 事件輪詢迴圈。

Slack 連線失敗不妨礙 Runtime、儲存層與 MCP 啟動；Socket Mode client 會非同步重新連線。

## 收訊與討論串行為

### 開始或繼續對話

- 開始對話：在公開頻道、私人頻道或 MPIM 中傳送提及 bot 的頂層訊息，系統會移除用來標記的 bot mention。頂層 DM 不需要文字 mention。
- 繼續 session：只在已綁定的討論串傳送一般文字回覆，不需要提及 bot。即使在未綁定的討論串中提及 bot，回覆仍會忽略；此時請改為傳送新的、在頂層明確提及 bot 的訊息。
- Root-thread/session 綁定不可變。一般回覆與 DM 文字會去除首尾空白，但保留 mentions；非 DM 的已明確標記頂層訊息會移除用來標記的 bot mention。明確 skill 命令則會另外移除一個位於開頭的自家 bot mention。
- 編輯、刪除、隱藏訊息、含附件的訊息、不支援的 subtype，以及 bot/system 雜訊，不會作為一般使用者工作受理。

### 收件結果

已受理事件會取得一則持久化的排隊確認（`已收到，正在排隊處理。`）。未完成工作容量已滿時，系統會將該來源持久化記錄為忙碌，並通知（`目前工作已滿，這則訊息尚未受理，請稍後重新送出。`）；重送不會在之後將其轉成工作。

這些通知表示投遞狀態，不代表工作已完成，也不能取代 Socket Mode 的事件確認。HTTP 與 Slack 共用資料庫准入機制；檢查容量之前會先解析已受理訊息的重送，因此可回到原本的收件資訊；BUSY 訊息重送後仍維持 BUSY，即使容量已釋出。佇列上限與 HTTP 收訊契約請參閱[佇列與收訊](http-api.zh-TW.md#佇列與收訊)。

## 停止討論串中的工作

### 傳送命令

任何人都可以在已綁定的對話討論串中傳送 `@bot stop` 或 `@bot 停止工作`。
即使在 DM 中也必須提及 bot；移除該 mention 並去除首尾空白後，文字必須剛好是小寫 `stop` 或 `停止工作`。未提及 bot 的文字、大寫 `STOP`、額外文字、編輯訊息、附件、隱藏訊息，以及 bot/system 雜訊都不會成為停止命令。

### 結果與限制

- Runtime 直接處理此命令，即使佇列已滿也不建立使用者訊息或工作、不呼叫模型，也不等待工作佇列。
- Runtime 要求取消該討論串不可變 session 綁定中所有 `PENDING`、`RETRY` 或 `WORKING` 工作，並在原討論串排入一則持久化的 `STOP` 通知。其他 session 與之後受理的提交不受影響。重播相同 Slack 命令不會取消後續工作或重複通知。
- 收據內容為 `已收到停止請求，共 N 項工作`，並提醒進行中的呼叫可能需要完成或逾時；不會聲稱執行中的工作已停止。取消使用與 [HTTP 相同的合作式安全點](http-api.zh-TW.md#工作取消與歷史)：已執行的操作不會回滾，每個工作的正常終止取消結果仍會分別投遞。
- 已綁定但沒有未終止工作的討論串，或未綁定討論串，會收到 `此對話目前沒有可停止的工作`。頂層命令會請發送者改用目標討論串；頂層或未綁定命令都不會建立 session 或工作。
- 命令只能透過一般訊息使用，沒有按鈕、捷徑或 slash command。`STOP` 通知使用持久化投遞／重試機制，且可依 Slack 來源查詢。

## 每個問題的進度通知

### 通知與問題連結

Slack 確認排隊通知的時間戳後，Runtime 會以每個問題各自的狀態與最後確認的 UTC 觀察時間更新同一則 bot 自己發出的通知。同一討論串中的問題各自保有獨立通知；通知不代表該討論串中最新工作的狀態。原始問題連結取自
[`chat.getPermalink`](https://docs.slack.dev/reference/methods/chat.getPermalink/)，
會依來源 channel 與 message 驗證並持久化快取。Progress 使用快取的 permalink 查詢，不需要額外 scope。

### 更新時間與限制

`session-agent.slack.progress` 下的預設值：

| 設定 | 預設值 | 意義 |
| --- | --- | --- |
| `poll-delay` | `2s` | Progress 輪詢延遲。 |
| `refresh-interval` | `30s` | 未變更非終止狀態的更新間隔。 |
| `workspace-interval` | `2s` | 同一 workspace 所有 replica 之間每次 progress API 呼叫的間隔。 |

狀態有變時可較早更新；輪詢會合併中間階段。這些是盡力而為的間隔；積壓、服務中斷與 Slack `Retry-After` 都可能延遲更新。
緩慢或失敗的 progress 呼叫不會阻塞回答發布、不會重試模型，也不會新增對話／歷史事件。永久性的查詢／更新錯誤（例如通知已刪除或權限遺失）會停止更新該目標；Runtime 不會另發替代通知。

時間戳是資料庫最後一次觀察的時間，不代表模型目前正在活動，也不是外部心跳。Runtime 完全停止期間，舊時間戳會一直顯示，直到恢復為止。來源重播與重新啟動會沿用已確認的通知與快取的問題連結。
已接受的延遲更新仍可能短暫覆寫較新的遠端文字；不保證遠端畫面單調更新或只發送一次。初次發文結果不明時，不代表可以再發一則替代通知。

### 判讀狀態

收到回覆代表已排隊，不代表正在執行。Progress 會區分準備、等待模型、查找工具、重試、停止中、所有權中斷、準備回答投遞、完成、取消、處理失敗與投遞失敗。最終回答與可變更的通知彼此分開。

| 觀察結果 | 意義 |
| --- | --- |
| `DONE` | 處理已結束，不代表成功或送達。 |
| 已完成通知 | 完整項目集合已準備好，且最終文字與每個必要附件都確認為 `SENT`。 |
| `DELIVERING` | 文字項目可能已送出，但必要附件仍待處理。 |
| `DELIVERY_FAILED` | 上傳失敗或結果為 `UNCONFIRMED`。 |
| `WAITING_USER` | 調查暫停，等待使用者下一步選擇；不代表報告已送達。 |

Skill 與 reference 通知區分讀取中、已載入、失敗及中斷；失敗／中斷結果不得視為已載入。讀取中／`STARTED` 不是已提交的讀取結果；只有已載入的 outcome 證明讀取已提交，不代表模型遵循或報告品質。Reference read 不等於重新載入 definition。Pending outcomes 優先於一般 progress，且只有確切 presentation／revision 被確認後才會清除。機制詳見 [skill receipt 與通知契約](architecture/skills.zh-TW.md#receipts-and-durable-notifications)。

既有的取消／處理失敗優先順序維持不變，恢復流程不會重新開啟歷史上已完整投遞的問題。Lease 與協調規則請參閱[進度內部契約](architecture/slack-delivery.zh-TW.md#progress-ownership-and-reconciliation)。

## 回答、快取 footer 與檔案

### 回答格式

一般助理回答會使用單一原生 Slack `markdown` block，並附上固定、易於使用的頂層通知。Markdown 不會被解析、拆分、修補或截斷。

- 行內上限包含組合後的回答、兩個換行分隔符與快取 footer，最多 10,000 個 UTF-16 units（也同時限制 code points）。
- 超出上限時，會將完全相同的原始 UTF-8 回答附加為 `answer.md`；說明文字會帶有 footer，檔案本身不包含 footer。不會額外呼叫模型。
- Runtime 通知（包含佇列、取消與終止 Runtime 錯誤）維持純文字。
- 不支援的原生 Markdown 或附件會明確導致投遞失敗；Runtime 不會靜默退回純文字、移除表格／fence、截斷內容或產生替代摘要。

### 快取 footer

Slack 與 HTTP 使用相同的中立逐工作報告。計算、涵蓋狀態、零 input 規則與非帳單承諾的完整定義，請參閱 API 的[快取報告](http-api.zh-TW.md#快取報告)。

- 有追蹤資料的工作會在持久化 `ANSWER` 第 0 部分附加一次 footer，涵蓋一般回答、等待使用者輸入的回答、終止失敗及取消。
- 部分資料標示 `資料不完整`，指出已知子集合與已知／總請求涵蓋數；部分資料但 input 為零時仍保留這些標籤。未知使用量顯示 `未提供`，沒有請求時顯示 `未呼叫模型`，已知 input 為零時顯示 `不適用` 且不提供數值百分比。
- `QUEUED`／`BUSY`／`STOP` 收據與 progress 更新不會附加 footer。
- Footer 會與 delivery parts 一起以交易方式準備，不會存入原始歷史或 artifact 位元組；重試／重新認領會沿用已準備的位元組。未追蹤的舊工作不會附加 footer，已準備的 delivery 也不會回填。
- 此呈現不會解決既有遠端文字發文確認結果不明的情況。

### 檔案與投遞

長回答與符合條件的 artifacts 可作為檔案分享，但檔案投遞與 artifact 建立是不同工作。所有必要項目都確認為 `SENT` 後，父 delivery 才會成為 `SENT`。重試只會續送尚未送出的項目，不會重新產生回答或重跑模型。已準備集合與上傳階段的不變條件，請參閱[投遞內部契約](architecture/slack-delivery.zh-TW.md#prepared-deliveries-and-upload-transport)。

### 權限與不確定結果

- 檔案分享需要已安裝 app 的 `files:write` 權限。Scope 變更及任何必要的 app 重新安裝／重新授權，都必須取得操作人員明確核准；升級 Runtime 不會授予權限，也不代表可以變更 app/token。
- Runtime 不會要求 `files:read`，也不會自動協調處理結果不確定的檔案。權限／類型／過期 capability 錯誤會終止投遞。
- 結果不確定的 finalization 會成為 `UNCONFIRMED`（父項目為 `FAILED`）。不得將 `FINALIZING`／`UNCONFIRMED` 重設為重試、再次 finalize、建立替代檔案或重新提交問題來修復投遞。應保留證據供操作人員協調處理，不可假設 Slack 一定已分享或一定未分享檔案。
- Upload capability 屬於敏感資料；產生的檔案、已接受的長回答位元組、上傳證據／capability 與歷史，都受資料庫存取權、備份安全與保留政策保護。不會自動過期／刪除 artifacts，也沒有公開 UUID 下載 URL。
- 既有初次訊息發文確認結果不明的情況維持不變；這些流程不保證 Slack 只會投遞一次。

使用 `GET /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...` 查詢單一 Slack 來源。端點刻意不回傳投遞文字、憑證、URL 或供應商錯誤。完整欄位與驗證規則請參閱 [HTTP API 參考](http-api.zh-TW.md#工作取消與歷史)。

必須另行確認 Slack 原生功能支援情形（包含 workspace／平台功能是否可用），不能只確認原始 Markdown 值有被保留。原生 Markdown 呈現與 workspace 實際檔案權限，仍須經明確授權後才能進行即時 Slack 檢查；詳見[開發指南](development.zh-TW.md)。部署設定請參閱[維運指南](operations.zh-TW.md)。
