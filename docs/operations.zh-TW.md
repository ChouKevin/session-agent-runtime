# 部署與維運

[首頁](../README.zh-TW.md) | [English](operations.md) | [問題索引](README.zh-TW.md)

建議採用 systemd 主機部署，並使用既有且相容的共用 PostgreSQL 資料庫。版本庫的 Docker Compose stack 是另一種需明確選擇的全套部署方式。不得使用 Compose 輔助程式命令更新、重新啟動或取代主機模式服務。

## 建議方式：systemd 主機部署

主機部署以 systemd user service 執行 Runtime，並使用已由管理者維運、具受限應用程式 owner 的相容 PostgreSQL 資料庫。資料庫是共用基礎設施，其生命週期獨立於 Runtime 或 gateway 服務。OMP Gateway／broker 的選擇與此分開：請透過[閘道部署指南](gateway-deployment.md)選擇本機或共用閘道。

新主機請依[主機安裝操作手冊](../.agents/skills/deploying-with-omp-gateway/references/install.md)操作；既有部署請依[操作手冊](../.agents/skills/deploying-with-omp-gateway/references/operations.md)。這些程序會辨識有效的 units、artifact、設定與憑證來源，並說明排空、備份、升級、回滾及服務生命週期。不要重設或重新建立共用資料庫、啟動第二個 PostgreSQL 容器、重用 Compose volume，或將 cluster-superuser 憑證交給 Runtime。若缺少相容資料庫、應用程式 role 或其憑證，請停止並回報 blocker，或取得明確的 provisioning 授權；不得靜默切換資料庫或建立新資料庫。

主機服務設定為私有內容，可能不同於已提交的預設值。已提交的 `application.yml` 將 server 綁定至 `127.0.0.1`，datasource URL 預設為 `jdbc:postgresql://localhost:5432/session_agent`；請在本機確認服務實際生效的設定，且不要輸出秘密。主機記錄寫入 systemd journal；使用 `journalctl --user -u session-agent-runtime` 查詢。Journal 保留政策由主機管理，不受下方 Docker 輪替設定控制。

## 明確選用：獨立 Docker Compose

只有在明確需要獨立全套部署時才選擇此方式。輔助程式操作 `docker/compose.yaml` 與 `docker/.env`，啟動自己的 Runtime 與 PostgreSQL 服務，將 Runtime 連接埠發布至 loopback，並將資料庫存放在 project-scoped `session-agent-postgres` volume。此部署不使用主機共用 PostgreSQL 或 `gamecenter-local-postgres-data`。不得將它當作主機部署更新，或與另一個使用相同連接埠的執行個體並行。

請從版本庫根目錄執行。只有在 `docker/.env` 尚不存在時，`init` 才會從範例建立該檔，不會覆寫既有設定。請在未追蹤的檔案中提供必要的 `SESSION_AGENT_POSTGRES_PASSWORD`、已設定模型的憑證，以及任何選用的 Slack、MCP、連接埠或記錄檔設定。輔助程式只會在準備主機記錄檔目錄時要求使用 `sudo`。

```bash
./docker/runtime.sh init
./docker/runtime.sh start
./docker/runtime.sh status
./docker/runtime.sh logs
./docker/runtime.sh restart
./docker/runtime.sh stop
```

`start` 會驗證 Compose、建置映像檔，並以 detached 模式啟動完整專案。`restart` 會停止整個專案、重新建置並啟動；僅可用於此獨立 Compose 部署，不可用來操作 systemd 主機服務。`stop` 會停止專案，但不刪除 PostgreSQL named volume。此輔助程式沒有資料重設命令；一般操作不得使用 `down --volumes` 或移除 volume。

Runtime 會將每行一個 JSON 物件輸出至 Compose 記錄與主機輪替記錄檔。目前使用的檔案名稱為 `session-agent-runtime.log`；封存檔名稱為 `session-agent-runtime.YYYY-MM-DD.N.log.gz`。Runtime 記錄檔達 100 MB 時輪替，保留七天，封存檔總量上限為 500 MB。Docker 獨立的 `json-file` driver 最多保留五個檔案，每個檔案 100 MB。從版本庫根目錄使用以下命令追蹤主機檔案：

```bash
tail -F logs/session-agent-runtime.log
```

若在 `docker/.env` 設定 `SESSION_AGENT_LOG_DIR`，請改用該絕對主機路徑。記錄檔目錄已加入 Git 忽略清單。Lifecycle events 僅含安全的事件、關聯及結果欄位；Runtime 的 lifecycle events 不會輸出對話文字、Slack／模型憑證、工具引數或結果、服務提供者 continuation data 或原始例外詳細資料。此 POC 不會部署 ELK 或 log shipper。

## 資料與變更安全

對話與工具結果會無限期保留。系統沒有自動 TTL、清理、備份排程或復原工作流程。升級前請依主機[操作手冊](../.agents/skills/deploying-with-omp-gateway/references/operations.md)辨識部署、排空工作、保留目前 artifact／設定，並評估 schema 相容性。切回較舊 JAR 不會還原資料庫 migration；資料還原及任何可能的資料遺失，必須遵守操作手冊的安全措施並取得授權。

模型、Slack、skills 與 MCP 設定請參閱[設定指南](configuration.zh-TW.md)，以及正式的[部署設定](../.agents/skills/deploying-with-omp-gateway/references/configuration.md)與 [MCP](../.agents/skills/deploying-with-omp-gateway/references/mcp.md) 操作手冊。不要提交本機憑證，也不要將 health check 成功視為模型、MCP 或 Slack 端到端驗收成功。
