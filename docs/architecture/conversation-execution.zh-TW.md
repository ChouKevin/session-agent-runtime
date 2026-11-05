# 對話執行

[問題索引](../README.zh-TW.md) | [架構總覽](../package-architecture.zh-TW.md) | [English](conversation-execution.md)

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責有序 model/tool loop、request allowance、handoff、取消、重試與復原；不負責 HTTP wire fields、usage accounting、artifact encoding 或 Slack transport。

<a id="contents"></a>

## 目錄

- [有序模型與工具迴圈](#ordered-model-and-tool-loop)
- [控制協定所有權](#control-ownership)
- [額度與收尾詢問](#allowance-and-wrap-up)
- [Continuation 生命週期](#continuation-lifecycle)
- [後續決策與僅報告模式](#follow-up-decisions-and-report-only-mode)
- [Worker migration 邊界](#worker-migration-boundary)
- [重試與原子復原](#retries-and-atomic-recovery)

<a id="ordered-model-and-tool-loop"></a>

## 有序模型與工具迴圈

每個已提交的 user message 都會啟動一個有序 job。一個 job 可執行一次或多次 model calls。每次 model call 會回傳 assistant text 或要求執行 tools。在一般 allowance 內，Runtime 依 model 提供的順序逐一執行已准入的 calls，接著以一個 batch 原子附加一個 `ASSISTANT_TOOL_CALLS` event 及其所有配對的 `TOOL` observations；這些 observations 會提供給下一次 model call。最後一個允許的 ordinary request 若回傳 tool calls，不會執行或附加它們；以下的有界限 handoff 優先處理。

Runtime 會向 model 公開已註冊的 tools，但不解讀 provider 的 input schema 或 tool output。一般單一 tool failure 會記為該 tool 的 observation，不會阻止同一 batch 的後續 requests。

一般 conversation prompt 會要求以問題為中心作答，先解釋結果與理由，再提供佐證。除非使用者要求或有助理解，預設省略 commit SHAs、完整 source paths、line citations 與 tool procedures；仍須明確說明相關不確定性與版本限制。這只是 presentation guidance：exact tool identifiers、必要的 source/version verification、不透明 MCP data，以及完整 audit history 都不變。

<a id="control-ownership"></a>

## 控制協定所有權

`JobHandoffCoordinator` 擁有有界限的 control protocol，不是第二個 tool loop。`JobControl` 將持久化的 execution mode/stage、確切的 initiating user 與 waiting generation，和已提交的 conversation history 分開。Semantic decisions 會在切換 tools 前先持久化於 fenced claim。`ToolSnapshot.restrictedTo` 會移交 single-owner cleanup；capability metadata 是本地 composition authority，不能由 MCP names 或 schemas 推論。Allowance、waiting 與 recovery 行為見[handoff 契約](#allowance-and-wrap-up)。

<a id="allowance-and-wrap-up"></a>

## 額度與收尾詢問

`session-agent.model.max-model-calls-per-message` 限制單一 user message 的一般執行與 context-compaction requests。其環境變數形式為 `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`；default 維持 12。失敗的 ordinary attempts 與 retries 都會消耗此 allowance。每個實際 provider request 都會在送出前預留，並增加既有的 `modelCallCount`；intent 與 wrap-up 各有單次 reservation，不會增加 investigation allowance。

最後一個允許的 ordinary request 若直接回覆文字，會正常完成。若該 request 回傳 tool response，則不執行任何 tools，也不附加 assistant calls 或捏造的 tool observations。Allowance 耗盡會進入持久化的 `WRAP_UP`：最多再發一個 no-tools request，簡短描述已確認的進度與缺口，並詢問是否要繼續調查或整理 evidence。Transient directive 不含 counts，也不會寫入 history。問題與持久化的 session waiting generation 會和 job completion 原子提交。Waiting 不是執行中的 job。若所選 request context 過大，Runtime 不送出請求，改用安全的 deterministic waiting notice。若 wrap response 失敗、無效或包含 tools，也使用該 notice；這不是針對回傳文字過大的拒絕規則。若已 reservation 的 request 遺失，recovery 不會重新查詢。Cancellation 優先，不能發布 waiting prompt 或 release artifacts。

Wrap-up 使用同一個 compaction snapshot 作為可見 history、summary 與 continuation selection 的依據。只有在所選 model route 上可見的 `ASSISTANT_TOOL_CALLS` sequences 才會 replay opaque continuation。若某個可見 continuation 屬於其他 route，會使用安全的 waiting fallback，不發 provider request，也不會丟棄 provider state 後送出損壞的 replay。Compacted 與非 tool sequences 不會進入 wrap continuation map。Provider payload interpretation 留在 model adapter。Adapter 會將 base prompt 與 transient purpose directive 合併為一個 system instruction；沒有 directive 的 ordinary prompts 不變，而且 directives 永遠不會成為儲存的 conversation events。

<a id="continuation-lifecycle"></a>

## Continuation 生命週期

Continuation 屬於目前的 job，不屬於 session 未來的 jobs。它會與 assistant/tool batch 一起提交，並在 retry、store reconstruction 與有效 reclaim 後保留。Compaction 會刪除已涵蓋的 entries；job completion（包括 waiting response）與 cancellation 會刪除該 job 剩餘的 continuation。Durable audit history 獨立於此 operational replay state 保留。

<a id="follow-up-decisions-and-report-only-mode"></a>

## 後續決策與僅報告模式

下一個有序 job 若面對 pending choice 執行，會以一個 no-tools semantic intent request，分類該 job 確切的 initiating user message，即使 history 中已有後續排隊的 users 亦然。`CONTINUE` 會消耗已觀察到的 waiting generation，並開啟新的 ordinary allowance。`DELIVER` 會消耗它並持久化 report-only mode：只會公開和允許呼叫受信任的本機 report-generation bindings，不會使用 MCP 或 skill reads，且必須揭露未知／未涵蓋的部分。`NEW_QUESTION` 會消耗它並開始一般工作，不會自動恢復舊調查。`CLARIFY` 會在不使用 tools 的情況下發布問題，並保留 source generation。無效、失敗或遺失的 intent 結果會 fail closed，改用 clarification notice，而非視為授權。Decisions 會在執行前持久化。已接受的明確 `/skill:name` 會開始新工作，並在已提交 skill resolution 時原子清除 waiting；拒絕的 command 會保留 waiting。

Report-only work 使用相同的有界限 ordinary loop，可建立 report、讀取其 receipt，然後回答；若 allowance 耗盡仍會 handoff，不會 release 部分報告。Control JSON（`intent` 與僅供 CLARIFY 使用的 optional `clarification`）屬於內部資料並經嚴格驗證。一般 final answers 即使看起來像 JSON，也只會當作純文字，不會解析。

<a id="worker-migration-boundary"></a>

## Worker migration 邊界

V11 會從既有的 total calls 回填 ordinary usage，但不會推論歷史 waiting state。Migration 前必須排空舊 workers；新舊 workers 混跑不受支援。

<a id="retries-and-atomic-recovery"></a>

## 重試與原子復原

Spring AI retries 透過 `spring.ai.retry.max-attempts=1` 停用。Transient provider failures 的 retry 由 Runtime 的 conversation-job loop 負責；該 loop 保留已計數的 model call，並避免重複 history。Runtime 在 dispatch 前預留每個 provider request，但 crash 或 ownership loss 可能留下未送出的 reservation。因此，預留計數不證明已送出或已計費的請求；gateway 內部的 attempts 不受 Runtime reservation 上限約束。

已准入的 tool batch 若在 append 開始前因已知 storage failure 停止，已取得的 attempt-local observations 會原樣保留，尚未開始的 calls 則會收到 `TOOL_NOT_EXECUTED` observations。Recovery 的 `ConversationStore.appendAndScheduleRetry` 會在一個 PostgreSQL transaction 中提交完整 assistant/tool batch、optional continuation、適用的 usage checkpoint 與 receipt associations，以及受 fence 保護的 `RETRY` transition。Retry transition 失敗也會 rollback 該 batch；不會留下另一個已提交的 working batch。若 cancellation 在此 transaction 中勝出，則改為提交 cancellation，不保留 continuation，也不排定 retry。

Append 或 atomic recovery 一旦開始，response 失敗就可能代表 transaction 已提交。Processor 會回報 state unconfirmed，不會重送該 batch，也不會捏造替代結果；後續由 durable state 與 claim/lease recovery 決定。這不是 tool-result caching，也不是一般的 exactly-once execution。

Tool execution 沒有 runtime deduplication layer。如果 process 在執行後、其 observation batch 提交前 crash，read-only tool 可能再次執行。未來任何 side-effecting tool 都必須在其 adapter 或 external-service boundary 提供 idempotency。
