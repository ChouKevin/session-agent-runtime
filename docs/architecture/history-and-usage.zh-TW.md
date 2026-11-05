# 歷史與用量

[問題索引](../README.zh-TW.md) | [架構總覽](../package-architecture.zh-TW.md) | [English](history-and-usage.md)

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責 audit/context admission、compaction checkpoint coverage 與 durable cache accounting；HTTP 負責 report 計算與公開欄位。本頁不是測試命令指南，也不是 live-provider 證據。

<a id="contents"></a>

## 目錄

- [稽核與 context 准入](#audit-and-context-admission)
- [Checkpoint coverage 與 compaction](#checkpoint-coverage-and-compaction)
- [持久化 cache accounting](#durable-cache-accounting)
- [Response observations](#response-observations)
- [原始 provider usage](#raw-provider-usage)
- [選用 accounting write](#optional-accounting-write)
- [診斷證據](#diagnostic-evidence)
- [Cache report 所有權](#cache-report-ownership)

<a id="audit-and-context-admission"></a>

## 稽核與 context 准入

`ConversationStore.loadHistory` 與其 paged variant 仍是完整 audit APIs。Model response history、compaction input/suffix 與 session usage 則使用同一個 `loadContextHistory` snapshot 上的 `ContextHistoryView`。Pending/rejected skill command USER events 與 rejected-command runtime feedback 依持久化的 disposition 排除，不會比對 error text。Automatic tool failures 仍會顯示。Accepted command 的原始 USER identity 在邏輯上會移至其 resolving SKILL event 之前。兩個 events 的 range boundaries 都使用該 resolution position，因此較舊的 audit USER 可一次跨越先前 summary boundary，但其 resolution 被摘要後，兩個 events 都不會恢復。Native tool batches 保留原始連續的 audit sequences 與 IDs。

Usage projections 包含依排序後的 command IDs、dispositions 與 resolution positions 計算的 deterministic eligibility fingerprint。此 fingerprint 參與 request-shape matching，而不參與 body token estimation；准入狀態改變時會使 provider checkpoints 失效。Summary candidates 與 retained suffixes 來自同一個 eligible history snapshot；SKILL 單獨存在不構成 response boundary。

<a id="checkpoint-coverage-and-compaction"></a>

## Checkpoint coverage 與 compaction

Provider usage checkpoints 另要求在 response commit 時證明 coverage。Worker 傳遞的是其 request 所用確切 history snapshot 中最後一個 **audit** sequence，而不是最後一個邏輯上准入的 message。在 append transaction 中，sequence allocation 會鎖定 session row；只有 assistant response 緊接該 snapshot boundary 時，storage 才寫入 checkpoint。因此，無論 USER 在 model call 期間或 sequential tool execution 期間到達，都會阻止該 response 的 checkpoint 隱藏中間訊息。History 與完整 tool batch 仍會正常提交。Pending/rejected skill audit events 可能保守地阻止重用；准入狀態改變仍由獨立的 eligibility fingerprint protection 處理。

Request-shape fingerprints 已針對此 coverage contract 加入版本，因此不需變更已部署的 migrations，即會忽略尚未有 coverage proof 時寫入的 checkpoints。若沒有符合的 checkpoint，usage 會退回完整估算；較舊且已證明的 checkpoint 仍可重用，因為其後每個准入 event 都包含在估算 suffix 中。一般相鄰 responses 仍重用 provider totals，並從 assistant boundary 後估算 tool observations 與其他後續 events。不需要 schema migration、history rewrite 或 retry/skill-admission change。

<a id="durable-cache-accounting"></a>

## 持久化 cache accounting

V12 新增持久化且 provider-neutral 的 per-job cache accounting，不會回填舊 jobs：既有 rows 保留 NULL accounting，新 jobs 則從零 counters 開始。`ConversationStore.recordModelUsage` 會在 live worker/claim/lease fence 下記錄 sequential reserved-call observation，於 commit 前再次檢查 ownership，並允許在 cancellation 已請求後計入進行中的 response。其 ordinal watermark 會讓重複或較舊的 observations 成為 no-ops；未預留的未來 ordinals 不能修改 accounting。缺少／無效的 usage 會推進 watermark，但不增加已知 token sums，因此未知 reservations 仍列入 request denominator。SQL/arithmetic failures 會 rollback 並傳遞給 caller；此 store 不會隔離 optional-write failures，使其不影響 application processing。

<a id="response-observations"></a>

## Response observations

每個 model port method 都要求一個同步的 `ModelCallObservation` consumer。Spring AI adapter 會保留 pre-request reservation 回傳的 total ordinal，並在 provider response 回傳後、ordinary reply normalization 或 intent/summary validation 之前立即送出恰好一個 observation。這包括 tool-loop responses、丟棄的最後預算 tool responses、compaction、intent 與 wrap-up。Invalid output 仍可能帶有已知 cache usage。Reservation denial、無效的 dispatch 前 history 與 provider exceptions 不會送出 observation：持久化的 reserved attempt 在 retries、reclaim 與 restart 後仍屬未知。Reservation 與 dispatch 之間發生 crash，不能證明 provider 已收費。

<a id="raw-provider-usage"></a>

## 原始 provider usage

Cache coverage 要求明確且非負的 raw input/cache fields，且 cached input 不大於 input；缺少 completion 或 total tokens 不影響此判斷。不支援的 usage 與省略欄位都屬未知，不能推論為零。Provider types 留在 model adapter。Google accounting 讀取 native optional fields，不使用 Spring 預設為零的 prompt getter。Configured OpenAI 使用 typed SDK client/service response decorator，因為固定版本的 Spring mapper 會在回傳 response 前讀取必要的一般 token fields。只有 mapper 無法安全消費的 usage shapes 才會移除標準 usage，並在 adapter-owned response metadata 中保留原始 raw usage。有效的一般 responses 原樣通過；adapter 不會捏造一般 `ModelUsage` 或 checkpoint availability 來補出 cache pair。SDK transport headers、status、body、ownership 與其他 operations 都維持原樣委派；不會改寫 transport、不會增加 request，也不會重試 provider。

<a id="optional-accounting-write"></a>

## 選用 accounting write

`JobCacheUsageRecorder` 會將 ordinary execution、compaction 與 handoff 的 observations 路由至每個 returned response 各自一筆額外的 fenced store transaction。它只捕捉此 optional usage write 的 `RuntimeException`，寫入失敗時該 reservation 仍屬未知。它不會讓有效 answer 失敗、不會啟動 recovery，也不會要求另一個 model response。必要 storage operations 與 fatal errors 維持既有 failure behavior。Response 回傳後才提出的 cancellation 不會抹除其 usage。Durable cache accounting 與既有 context usage checkpoints 所使用的 request-shape 與 response-boundary proof 相互獨立：history/checkpoint atomicity 與 tool-batch recovery semantics 都不變。

<a id="diagnostic-evidence"></a>

## 診斷證據

既有 ordinary-turn `model_response` lifecycle events 會包含 `cacheUsageKnown`，並且只有在已知時才包含 exact `cachedInputTokens` 值（包括零）。這些 fields 使用 aggregate accounting 相同的 raw-normalized `ModelCallObservation.cacheUsage`，而非可能因 provider defaults 遺失欄位存在性的 ordinary `ModelUsage` cache getter。缺少 observations，或 cache usage 缺少／無效時，會送出 `cacheUsageKnown=false` 並省略 `cachedInputTokens`；不會沿用前一個 turn 的值。Cache availability 與既有 prompt/completion/total token fields 無關。Session/job IDs 與預留的 total `callOrdinal` 保留 request correlation，不會記錄 conversation 或 tool contents。此 diagnostic 描述觀察到的 response，即使 optional accounting write 失敗亦然；它不證明 accounting 已持久化。Intent、compaction 或 wrap-up calls 不會新增 response events。

<a id="cache-report-ownership"></a>

## Cache report 所有權

`JobCacheReport` 提供 provider-neutral projection；其計算、涵蓋狀態與終止性由 [HTTP 快取報告](../http-api.zh-TW.md#快取報告)定義。Slack 專屬交易與已準備位元組的所有權見 [Slack 投遞](slack-delivery.zh-TW.md#prepared-deliveries-and-upload-transport)。
