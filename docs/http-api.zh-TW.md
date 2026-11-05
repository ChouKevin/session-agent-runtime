# HTTP API 與 Slack 投遞參考

[首頁](../README.zh-TW.md) | [English](http-api.md) | [問題索引](README.zh-TW.md) | [架構](package-architecture.zh-TW.md)

請將範例用於已設定的 loopback 位址。API 本身沒有加入驗證層；若要公開存取，必須另設安全邊界，否則請勿公開。

## 訊息提交

提交訊息、輪詢其工作狀態，接著讀取完整 session 歷史：

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
POST /internal/message-jobs/{messageJobId}/cancel
POST /internal/session-lookups
GET  /internal/sessions/{sessionId}
GET  /internal/sessions/{sessionId}/messages
PUT  /internal/sessions/{sessionId}/model
GET  /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...
```

`POST /internal/messages` 接受 `sessionKey`、`participantId`、`sourceMessageId` 與 `message`。重複使用非空白的 `sessionKey` 會繼續使用同一個 session。受理回應包含 `sessionId` 與 `messageJobId`。

四個欄位都必須提供且不得為空白。`sessionKey`、`participantId` 與
`sourceMessageId` 各自最多 256 個字元。成功回應為
`202 {"sessionId":"...","messageJobId":"..."}`。

冪等性範圍是 **來源類型加上 `sourceMessageId`**，不是 session
key。對 HTTP 提交而言，來源 ID 必須在所有 session 之間保持唯一。
使用相同 participant 與 message 重送時，即使 `sessionKey` 已變更，也會回傳原本的收件資訊。若相同識別資料搭配不同 participant 或 message 重用，則回傳
`409 {"code":"message_conflict"}`。重複項目會在容量准入檢查之前解析。

## Session 查詢與詳細資料

`POST /internal/session-lookups` 接受 `{"permalink":"https://...slack.com/..."}`。
Permalink 不得為空白，且必須指向支援的 Slack 訊息或討論串。
成功查詢會回傳 `200 {"sessionId":"...","sessionDetailPath":"/internal/sessions/..."}`。
格式錯誤的參照回傳 `400`；格式有效但沒有已持久化綁定的參照回傳
`404`。查詢不會建立 session。

`GET /internal/sessions/{sessionId}` 以 `200` 回傳：

| 成員 | 結構 |
| --- | --- |
| `sessionId`、`createdAt` | Session UUID 與建立時間戳記 |
| `slack` | 選用的 `{teamId, channelId, rootThreadTs, createdAt}` |
| `currentJob` | 下文所述的選用工作摘要 |
| `latestCompaction` | 選用的 `{generation, coveredThrough, reason, createdAt}` |
| `context` | `{modelId, capacityTokens, estimatedUsedTokens, ratio, basis}` |

若沒有 `slack`、`currentJob` 或 `latestCompaction`，這些成員會**省略**，而非明確回傳 JSON `null`。工作快照中的選用欄位則會明確使用 `null`。Context 使用量是估算值，不是供應商配額或帳單餘額。
未知 session 回傳 `404`；格式錯誤的 UUID 路徑回傳 `400`。

## 驗證與錯誤

未知的 JSON 請求屬性會遭拒。模型選擇鍵為必填、不得為空白，且最多 64 個字元。每個提供的歷史分頁參數都必須剛好出現一次且不得為空白：`afterSequence` 是 0 至
9223372036854775807 的整數，`limit` 是 1 至 2147483647 的整數。

已處理的 API 錯誤會公開安全的 `{"code":"..."}` 本文，不會公開例外詳細資訊：

| HTTP 狀態 | 代碼 | 意義 |
| --- | --- | --- |
| `400` | `bad_request` | 請求本文、識別碼、參照或支援的查詢參數無效 |
| `404` | `not_found` | 找不到要求的資源或 session 綁定 |
| `409` | `message_conflict` | 來源識別資料重用時使用了不同的 participant/message |
| `429` | `queue_full` | 未完成工作數已達上限，無法受理新工作 |
| `503` | `service_unavailable` | 未處理的 Runtime 失敗 |

## 工作、取消與歷史

工作回應包含 `messageJobId`、`sessionId`、`status`、`retryCount` 與
`modelCallCount`，以及 `phase`、`phaseStartedAt`、`currentToolName`、`failureCode`、
`failureMessage` 與 `cancellationRequestedAt`。`GET /internal/sessions/{sessionId}`
公開範圍較窄的 `currentJob` 摘要，欄位為 `messageJobId`、`status`、`modelCallCount`、
`phase`、`phaseStartedAt`、`currentToolName`、`failureCode`、`failureMessage` 與
`cancellationRequestedAt`；其中不包含 `sessionId` 或 `retryCount`。
這些工作回應中的選用欄位若不存在，會明確回傳 JSON `null`。
`modelCallCount` 是供應商請求的總序號，包含一般執行、壓縮、意圖分類與收尾。
另外持久化的一般執行額度屬於操作狀態，不是模型歷史或提示中繼資料；總序號仍是 artifact 識別資料。

### 快取報告

`GET /internal/message-jobs/{messageJobId}`、取消回應，以及
`GET /internal/sessions/{sessionId}` 中的 `currentJob` 都會公開相同的可為 null 的
`cacheReport` 投影。存在時，它只包含以下欄位：

| 欄位 | 意義 |
| --- | --- |
| `phase` | 工作進入終止狀態之前為 `PROVISIONAL`；只有 `DONE` 或 `CANCELLED` 才是 `FINAL`。 |
| `status` | 使用量涵蓋狀態：`NOT_CALLED`、`UNAVAILABLE`、`PARTIAL`、`COMPLETE` 或 `NOT_APPLICABLE`。 |
| `requestCount` | 此訊息工作的供應商請求預留總數；與 `modelCallCount` 相同。 |
| `knownUsageRequestCount` | 有提供且有效的 input/cache token 配對之請求數。 |
| `knownInputTokens` | 僅加總使用量已知之請求的 input tokens。 |
| `knownCachedInputTokens` | 對相同的已知請求加總 cached input tokens。 |
| `hitRatio` | `knownCachedInputTokens / knownInputTokens`，以 HALF_UP 四捨五入至小數六位；分母為零時，JSON 值為 `null`。 |

Token 加總是已知子集合的總數，不是對缺失請求的估算。缺少、不支援或無效的 input/cache 使用量代表未知，不代表零；明確的零配對則是已知使用量。`NOT_CALLED` 表示沒有預留任何請求。`UNAVAILABLE` 表示已預留請求，但沒有任何一筆使用量已知。`PARTIAL` 表示至少有一筆但並非全部預留請求的使用量已知，即使已知 token 加總為零也一樣。`NOT_APPLICABLE` 表示每筆請求的使用量都已知，但已知 input-token 加總為零。`COMPLETE` 表示每筆請求的使用量都已知，且已知 input-token 加總大於零。Completion 或 total-token 使用量是否可用，不影響快取使用量的涵蓋狀態。

`hitRatio` 是比例，不是百分比，也不是逐請求百分比的平均值。例如，`0.555556` 對應到 `55.6%` 的顯示值；呈現端須從已知子集合的整數加總另行計算至小數一位的百分比。這份中立的使用量報告不是帳單估算或承諾。

Slack 直接從相同的整數加總計算百分比，採 HALF_UP 四捨五入至小數一位，而非使用已四捨五入的 HTTP 比例：25000 / 45000 顯示 `55.6%`。部分資料會保留已知子集合與已知／總請求涵蓋標籤（`資料不完整`），即使已知 input 為零也一樣。未知使用量顯示 `未提供`，沒有請求時顯示 `未呼叫模型`，已知 input 為零時顯示 `不適用` 且不提供數值百分比。[Slack footer 適用範圍與位元組歸屬](slack.zh-TW.md#快取-footer)由 Slack 指南定義。

終止性與涵蓋狀態及傳輸狀態彼此獨立。提出取消請求後，`WORKING` 工作仍維持
`PROVISIONAL`；只有取消提交為 `CANCELLED` 後才會成為 `FINAL`。`DONE` 也屬於最終狀態。Slack 投遞狀態不會改變報告的終止性。

`requestCount` 計算供應商派送前已預留的請求數。程序可能在預留呼叫後、派送前當機，因此未知的預留嘗試不能證明供應商請求已送出或已計費。沒有快取使用量追蹤的歷史工作會回傳 `"cacheReport": null`；新追蹤的工作若沒有請求，則會回報 `NOT_CALLED`。報告不會公開原始供應商中繼資料、逐請求或逐模型明細，也不包含帳單資料。

| 階段 | 意義 |
| --- | --- |
| `PREPARING` | 已認領的工作正在準備下一個回合，或在已提交的批次之後繼續處理。 |
| `MODEL_CALL` | 正在進行已計數的模型請求，包含壓縮、意圖與收尾。 |
| `TOOL_EXECUTION` | 由 `currentToolName` 指出的工具正在執行。 |
| `HISTORY_COMMIT` | Runtime 正在提交歷史或壓縮結果；此階段不會公開尚未提交的事件。 |

`phaseStartedAt` 是目前階段的開始時間，不是心跳或進度百分比。只有在
`TOOL_EXECUTION` 期間才會填入 `currentToolName`。階段是持久化快照，不是事件串流；輪詢可能會錯過短暫的轉換。助理工具呼叫與所有相符的工具觀察結果仍會以單一原子歷史批次變為可見。

`DONE` 表示處理已結束，不一定表示成功。請檢查 `failureCode` 與最後一筆歷史事件以區分結果。失敗訊息是固定的安全文字，不會使用原始供應商例外。代碼包括
`MODEL_CALL_LIMIT_REACHED`、`MODEL_OUTPUT_INVALID`、`MODEL_UNAVAILABLE`、
`CONTEXT_TOO_LARGE`、`COMPACTION_FAILED`、`INVALID_CONVERSATION_HISTORY`、
`STORAGE_UNAVAILABLE`、`SKILL_UNAVAILABLE` 與 `PROCESSING_INTERRUPTED`。
一般工具錯誤會保留為該工具的觀察結果，本身不會使整個工作標記為失敗。

重試與完成會清除進行中的階段／工具欄位。新的認領或模型呼叫預留會清除先前的失敗；成功完成也會清除失敗。Worker 租約到期時，輪詢會隱藏過時的進度，並回報
`PROCESSING_INTERRUPTED`，同時在恢復流程認領工作前保留 `WORKING` 狀態。重新認領會初始化為 `PREPARING`；舊認領無法覆寫其進度或歷史。程序重新啟動不需要記憶體內的進度快取。

若儲存失敗在已受理的工具批次開始追加歷史前使其停止，恢復流程會保留已取得的觀察結果，並將尚未開始的呼叫標記為 `TOOL_NOT_EXECUTED`；這些呼叫並未執行。完整的助理／工具批次與 `RETRY` 轉換會在同一個交易中提交。重試轉換失敗時，不會留下另一筆已分開提交的批次。重新認領後，下一次模型請求可使用這些持久化觀察結果，而不必重新執行已完成的工具批次。這不保證任意程序當機後工具只會執行一次。追加回應不確定，不代表可以盲目重送可能已提交的批次。

供應商 continuation 是內部重播狀態，不是 HTTP 歷史欄位。它會在同一工作重試／重新認領時保留，並可在最後一次不含工具的收尾請求中，與可見工具歷史一同使用。工作完成或取消時會移除該狀態，但不會刪除已提交的助理／工具觀察結果。

`POST /internal/message-jobs/{messageJobId}/cancel` 不需要請求本文。已知工作會回傳含目前工作快照的 `200`，未知工作回傳 `404`，格式錯誤的 UUID 回傳 `400`。重複提出請求具冪等性：

- `PENDING` 與 `RETRY` 工作會變成 `CANCELLED`，不會再啟動模型請求。
- `WORKING` 工作會保留目前階段並記錄 `cancellationRequestedAt`。已獲准的模型／工具請求完成或逾時後，工作會在安全點停止。提出取消請求不代表已確認工作停止。
- 在工具批次中，已取得的觀察結果維持不變。尚未開始的呼叫會收到明確的 Runtime 觀察結果，其中 `isError: true` 且 `result: {code: "TOOL_CANCELLED", message: ...}`；這些呼叫**並未執行**。原始助理工具呼叫、所有相符的觀察結果，以及終止用的 `RUNTIME` 事件 `JOB_CANCELLED` 會原子性地提交。不會啟動下一個模型回合。如果儲存失敗已在取消變得可觀察之前停止批次，終止恢復流程會改以儲存失敗原因將那些尚未開始的呼叫標記為 `TOOL_NOT_EXECUTED`。若取消在該次追加期間勝出，不會改寫先前的停止原因或任何工具結果。
- 此端點不會修改 `DONE` 與 `CANCELLED` 工作。如果一般完成先提交，回應仍為 `DONE`；如果取消勝出，終止狀態為 `CANCELLED`，且沒有失敗代碼或進行中的進度。

請求時間戳會在程序重新啟動與租約回收後保留。恢復已提出取消請求的工作時不會重播模型／工具作業。取消不會強制中止遠端 OMP/MCP 作業、不會退還已預留的模型呼叫，也不會回滾工具副作用。程序當機前或資料庫提交結果不確定時尚未提交的結果，無法保證會作為恢復後的觀察結果；Runtime 不會捏造結果，也不會盲目重播可能已提交的批次。

`PUT /internal/sessions/{sessionId}/model` 接受 `{"model":"configured-key"}`，並以 `{sessionId, model, effectiveFor:"NEW_MESSAGES"}` 回傳 `200`。只有變更後受理的訊息會使用所選模型；排隊中、執行中與重試中的工作會保留受理時擷取的模型。選擇已設定的模型不需要重新啟動 Runtime 即可生效。

`GET /internal/slack-deliveries?teamId=...&channelId=...&messageTs=...` 會針對一個 Slack 來源回傳安全的投遞狀態陣列。每筆資料包含 `deliveryId`、`kind`、`status`、`attemptCount`、`failureCategory`、`nextAttemptAt`、`jobId`、`totalParts` 與 `sentParts`。缺少的選用值為 `null`；沒有任何分段時 `totalParts` 為 `null`，而 `sentParts` 一律是數字（尚未送出任何分段時為 `0`）。缺少、空白或重複的查詢參數會回傳 `400`。回應不會包含投遞文字、權杖、URL 或供應商錯誤。Slack 投遞狀態與模型工作結果分開；未知來源會回傳空陣列。

`GET /internal/sessions/{sessionId}/messages` 接受選用的 `afterSequence` 與 `limit` 查詢參數。兩者皆省略時會回傳完整的已提交歷史。只要提供其中一個參數，結果就是 `afterSequence` 之後依遞增順序排列的原始歷史分頁（預設為 `0`），並在提供 `limit` 時受其限制；對已存在的 session，游標耗盡時會回傳空清單；未知 session 則回傳 `404`。

歷史紀錄共用 `sequence`、`createdAt` 與 `messageJobId` 欄位。公開事件格式如下：

| 類型 | 類型專屬欄位 |
| --- | --- |
| `USER` | `participantId`、`message` |
| `SKILL` | `uri`、`sha256`、`content` |
| `ASSISTANT_TOOL_CALLS` | 選用的 `message`、`calls`：依序排列的 `{toolCallId, toolName, arguments}` 值 |
| `TOOL` | `toolCallId`、`toolName`、不透明的 `output`；每個觀察結果都會配對到先前 `ASSISTANT_TOOL_CALLS` 中的呼叫 ID |
| `ASSISTANT` | `message` |
| `RUNTIME` | `code`、`message` |

工具輸入與輸出是 Runtime 的不透明資料。沒有獨立的工具結果查詢端點；工作狀態請使用 message-job 端點，外送投遞狀態請使用 Slack-delivery 端點。

對 MCP 觀察結果而言，`output` 使用通用的 `{isError, result}` 包裝；
`result` 會保留 MCP 原生的 `content` 區塊，並在存在時保留 `structuredContent`。
兩種內容都會進入儲存的歷史與後續模型回合：可供人閱讀的內容可能包含結構化資料中沒有的證據或呈現細節。內容類型判別值會保留；MCP 結果封套的 `_meta` 不會轉送給模型。Runtime 不會剖析工具專屬的來源文字，也不會強制採用引用格式。先前已提交的觀察結果維持不變；Runtime 操作失敗會保留安全的 `{code, message}` 結果。

## 佇列與收訊

預設 Worker 設定為 `session-agent.worker.concurrency: 4`。資料庫佇列容量為 `session-agent.worker.max-unfinished-jobs: 100`，合併計算所有 `PENDING`、`WORKING` 與 `RETRY` 工作。終止狀態的工作會釋出容量。資料庫就是等待佇列：同一個 session 的工作依 FIFO 執行，不同 session 的工作則可並行執行。

未完成工作容量滿載時，新的 HTTP 訊息會以 `429` 和 `{"code":"queue_full"}` 拒絕。已受理訊息成功重送時，會回傳原本的 `202` 收件資訊，而不會建立另一個工作。

HTTP 與 Slack 共用准入容量。兩條路徑都先解析重複訊息，再針對新工作檢查容量，因此即使容量已滿，已受理訊息的重送仍會解析為原工作。共用 PostgreSQL 實作由[准入交易](architecture/slack-delivery.zh-TW.md#admission-and-stop-transactions)定義。

## Slack Socket Mode（選用）

設定、訊息／討論串收訊、停止工作、進度通知、回答、快取 footer 與檔案請參閱 [Slack 指南](slack.zh-TW.md)。[Slack 內部契約](architecture/slack-delivery.zh-TW.md#admission-and-stop-transactions)定義准入交易、lock order、fencing、復原與上傳傳輸。
HTTP 投遞查詢欄位仍在[工作、取消與歷史](#工作取消與歷史)；共用快取計算仍在[快取報告](#快取報告)。

核心安全限制：

- Slack tokens 放在部署環境或 secret manager，不可提交至版本控制。
- `DONE` 表示處理已結束，不代表成功或送達；所有必要文字／檔案項目都必須確認為 `SENT` 才算投遞完成。
- 取消不會回滾已執行的操作；進行中的呼叫可能仍需完成或逾時。
- 不得重設 `FINALIZING`／`UNCONFIRMED`、盲目再次 finalize，或重新提交問題來修復結果不確定的投遞。應保留證據供操作人員協調處理。
- Scope 變更／重新安裝與即時 Slack 檢查都需要明確授權。
