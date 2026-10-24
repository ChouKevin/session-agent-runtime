---
name: deploying-with-omp-gateway
description: Use when installing, configuring, updating, checking, or rolling back this repository's Slack agent deployment through OMP Auth Gateway on Linux or WSL, including model selection, reasoning effort, and MCP reader connections or tool-call failures.
---

# 使用 OMP Gateway 部署 Session Agent Runtime

## 原則與適用範圍

本專案目前優先採用 **主機 Java 21 Runtime + 主機 OMP broker/gateway + Docker PostgreSQL**。OMP 是外部依賴；Runtime 只使用 OpenAI-compatible Chat Completions 與 gateway bearer，不持有 provider OAuth 憑證。Gateway 不會替 Runtime 執行 OMP agent loop、skills 或 MCP 工具。

此 skill 是操作參考，不是會自動安裝的程式。路徑皆相對本 skill 目錄；終端命令的 `$REPO` 指 checkout，`$DEPLOY_ROOT` 指私有部署目錄。不把私有設定複製回 repo。

## 操作分類

| 操作 | 必讀文件 | 完成條件 |
|---|---|---|
| 全新安裝 | [install.md](references/install.md) | 資料庫、gateway、Runtime 與選用 Slack 連線就緒 |
| 模型／medium reasoning／Slack 設定 | [configuration.md](references/configuration.md) | 外部設定正確，重啟後驗證；新舊 session 語義清楚 |
| MCP 連線、憑證、停用與工具驗收 | [mcp.md](references/mcp.md) | 每個連線 discovery 與真實工具 calls／observations 分別驗證 |
| 更新、備份、回滾、啟停 | [operations.md](references/operations.md) | 憑證與 volume 保留，版本可追蹤，資料庫相容性確認 |
| 健康檢查、模型與 Slack 實測 | [verification.md](references/verification.md) | 分別報告 health、model、Slack 證據，不互相替代 |
| Runtime 本地 skills／明確重讀 | [configuration.md](references/configuration.md)、[verification.md](references/verification.md) | 唯讀可信 roots；區分 metadata、current body、audit 與模型 context |

範本在 [assets/](assets/)，安裝前核對版本、路徑、連接埠及既有服務。已部署主機走「更新」，不是重新執行 fresh install。

## 預設配置

- Runtime `127.0.0.1:8090`；gateway `127.0.0.1:4000`；broker `127.0.0.1:8765`；PostgreSQL `127.0.0.1:15432`。
- 模型範例 `openai-codex/gpt-6.1-sol`，`reasoning-effort: medium`；可用性與容量須以該 gateway 的 `/v1/models` 核對。
- 外部完整 `application.yml` 配合 `SPRING_CONFIG_LOCATION`，不合併 bundled Google model profile。
- systemd user services；私有目錄 `700`、環境檔 `600`；repo 僅保存不含憑證的範本。
- Runtime skills 預設停用，僅讀操作者明確設定的共享 roots；不自動匯入 OMP 或本 repo 的維護 skills。本部署指南不能當作 Runtime 業務 skill 掛載；不授權 shell 執行。

## 常見錯誤與停止條件

| 情況 | 行動 |
|---|---|
| 發現既有部署、相同 port 或 volume | 先識別 owner；不覆寫、不另建同用途資料庫 |
| Google API key 空白錯誤 | 檢查完整外部 config location；只改 import/default 不等於移除原 model map |
| 主機嘗試寫 `/app/logs` | 使用 host logging 範本，不任意建立容器路徑 |
| `mcp.connections` 空字串綁定錯誤 | 沒有 MCP 時省略 `connections`，不要寫 `connections: {}` |
| MCP `AVAILABLE` 但模型工具失敗 | 檢查持久化參數與 `output.isError`；依 MCP runbook 分離 reader 與 Gateway／模型問題，不提高上限掩蓋失敗 |
| Docker bridge IP 無法綁定 | Docker Desktop bridge 可能在 VM；維持主機 loopback，不改綁 `0.0.0.0` |
| 尚有工作／模型配置 fingerprint 即將改變 | 先停止新提交並排空；不默默取消工作或強制重播 |
| Gateway 無可用模型、憑證失效 | 報告缺少的登入／權限；不拿 OAuth token 冒充 gateway bearer |
| 無備份或 migration 回滾相容性不明 | 停止更新／回滾，先解決資料安全條件 |

## 驗收與提交

先驗證設定與無額度健康檢查。只有取得明確授權後，才發出真實模型請求或 Slack 訊息。Slack `AVAILABLE` 只證明 Socket 連線；真人 thread 接收與回覆另測。MCP `AVAILABLE` 證明該連線可用，不替代真實工具成功與持久化結果驗收。

提交只包含 skill、無秘密範本及相關 README。禁止提交 `.env`、gateway/broker token、OMP credential database、備份、log、對話內容或編譯 artifact。完成時回報實際模型／reasoning、Runtime SHA、OMP 版本、已跑檢查及未驗證邊界。
