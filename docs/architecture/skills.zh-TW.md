# Skills：發現、讀取與 receipts

[問題索引](../README.zh-TW.md) | [架構總覽](../package-architecture.zh-TW.md) | [English](skills.md)

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責啟動 discovery、有界 reads、持久化 selection、receipt/admission 與 notification recovery；不執行 skill scripts、不驗證 model compliance，也不保證 business-report 品質。設定鍵參考由設定指南負責。

<a id="contents"></a>

## 目錄

- [Discovery 與目前檔案讀取](#discovery-and-current-file-reads)
- [Filesystem 安全邊界](#filesystem-security-boundary)
- [Selection 與復原](#selection-and-recovery)
- [Receipts 與持久化通知](#receipts-and-durable-notifications)

<a id="discovery-and-current-file-reads"></a>

## Discovery 與目前檔案讀取

Library 透過 `session-agent.skills.roots`（預設為空）、`max-file-bytes`（65536）與 `max-skills`（128）明確設定。Bootstrap 只在啟動時掃描 direct child `SKILL.md` frontmatter；一般 request tool descriptions 只包含該 metadata，不會預先載入檔案內容。每次 read 都會使用 no-follow directory handles 重新開啟有界限的目前 UTF-8 bytes；回傳的 hash 是 evidence，不是 version lock。新提交 source message 中的 `/skill:name` 會重新讀取；source replay 與已提交 selection 後的 recovery 則重用 durable history。Summarization 不提供 tool definitions，automatic TOOL bodies 與明確 SKILL bodies 都不會在一般 compaction 後固定或還原。

<a id="filesystem-security-boundary"></a>

## Filesystem 安全邊界

啟用的 roots 必須使用支援 `SecureDirectoryStream` 的 Java filesystem provider；不支援時會 fail startup closed。目標環境是使用支援此能力之 local filesystem provider 的 Linux，不保證所有 Linux filesystems 或 mounts 都支援。Read limit 限制的是回傳 bytes，不是 file-open latency：regular-file checks 與 no-follow opens 無法防止具特權的 concurrent writer 將項目替換為 FIFO 或其他特殊檔案，也無法提供 timeout。Roots 必須維持為受信任、由 operator 管理的內容，不能作為防範惡意 writer 的邊界。

此邊界只應包含由 operators 管理的共用 roots。Service accounts 與 container mounts（包括 references）應為唯讀；所有 Runtime users 都能要求讀取這些檔案。此處沒有 shell execution、自動 home/repository skill discovery，也不會隱式匯入此 repository 的 maintenance/deployment skills。[Skill 定義](../configuration.zh-TW.md#skill-definition)與[選擇／重播參考](../configuration.zh-TW.md#skill-lifecycle)定義磁碟格式及 command；[主機安裝操作手冊](../../.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用)負責安裝與套用。

<a id="selection-and-recovery"></a>

## Selection 與復原

`JobProcessor` 只會解析 claim 中選取為 PENDING 的項目，再產生 responses 或 summaries，並重新載入已提交的 context。ACCEPTED selections 會重用已儲存的 bytes。解析作業共用一般 append uncertainty tracker：若寫入結果不明，該次 attempt 會停止，不會重新提交，也不會建立替代的 failure batch。

<a id="receipts-and-durable-notifications"></a>

## Receipts 與持久化通知

`SkillLoadObserver` 與 `SkillReadReceipt` 屬於中立 tool port。Filesystem results 留在 skill adapter；storage 會在同一 transaction 中 finalize attempts 與其 SKILL/TOOL history。Slack 將 resource kinds 對應至自身 presentation type，避免 skill/tool module cycle 或 Slack receipt dependency。Pending outcomes 會排在一般 progress 前面，並依 exact presentation/revision 確認，而非只依 job 確認。Reference success 不代表 definition reload；failed/interrupted reads 不會變成 loaded；已確認的 outcome 會在後續一般 progress 中消失。WAITING_USER 是該 job status 的 terminal 狀態，不代表 report 已 delivery。這些 operational records 都不會成為 history。Loaded notices 只確認已提交的 content。Runtime 不驗證 prompt skills 是否符合要求，也不保證 business-report provenance、完整性或欄位正確性；model-output quality 需要另外審查證據。
