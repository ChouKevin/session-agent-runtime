# 操作：更新、備份、回滾與啟停

憑證到期、OAuth 失效恢復與 token 撤銷／輪換見 [憑證維運流程](../../../../docs/gateway-deployment.md#6-撤銷輪換與故障處理)。版本確認見 [版本判讀](../../../../docs/gateway-deployment.md#7-驗證與版本判讀)；更新 Git checkout 不等於更新已部署的 Runtime。

## 先辨識既有部署

先在主機查看 Runtime unit、artifact 連結、config location，以及 Docker Compose labels／volume mounts；敏感值只在本機處理，不回傳完整 environment、`docker inspect` 或 systemd properties。記錄 Runtime SHA/checksum、OMP 版本、source revision／patch、實際 Gateway service executable 的 SHA-256 checksum 與 PostgreSQL image identity。

先取得只含位置／身分的資料，不輸出秘密：

```bash
systemctl --user show session-agent-runtime \
  -p FragmentPath -p DropInPaths -p WorkingDirectory -p EnvironmentFiles
docker ps -a --filter label=com.docker.compose.service=postgres \
  --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
# POSTGRES_CONTAINER 設成已確認屬於這個 Runtime 的容器名稱。
docker inspect "$POSTGRES_CONTAINER" --format \
  'project={{index .Config.Labels "com.docker.compose.project"}} working_dir={{index .Config.Labels "com.docker.compose.project.working_dir"}} config_files={{index .Config.Labels "com.docker.compose.project.config_files"}} env_file={{index .Config.Labels "com.docker.compose.project.environment_file"}}'
docker inspect "$POSTGRES_CONTAINER" --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}type={{.Type}} name={{.Name}} source={{.Source}}{{end}}{{end}}'
```

若 Runtime 正在執行，以下只投影非秘密的有效設定位置與 jar 路徑；不顯示完整 process environment：

```bash
python3 - <<'PY'
import subprocess
from pathlib import Path
pid = int(subprocess.check_output(
    ['systemctl', '--user', 'show', 'session-agent-runtime', '-p', 'MainPID', '--value'], text=True))
if pid <= 0:
    raise SystemExit('Runtime is stopped; inspect the unit and its listed config files locally.')
args = Path(f'/proc/{pid}/cmdline').read_bytes().decode().split('\0')
if '-jar' in args:
    print('jar:', args[args.index('-jar') + 1])
environment = dict(value.split('=', 1) for value in
    Path(f'/proc/{pid}/environ').read_bytes().decode().split('\0') if '=' in value)
for key in ('SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_IMPORT', 'LOGGING_CONFIG', 'SERVER_PORT'):
    if key in environment:
        print(key + '=' + environment[key])
PY
```

`config_files` 保留原順序。Compose 不一定記錄 env-file label；本 repo 的 `docker/runtime.sh` 固定使用 `$REPO/docker/.env`，其他啟動來源須確認其原始 environment file。缺少來源資訊時停止，不猜密碼或另產新 env。模型 bearer 可能位於既有 `runtime.env`；查檔時只確認 key 是否存在，保留原來源。

本文件的命令針對 [install.md](install.md) 建立的部署：

```bash
set -euo pipefail
export DEPLOY_ROOT="$HOME/.local/share/session-agent-runtime"
PC=(env -u SESSION_AGENT_POSTGRES_PASSWORD docker compose -p session-agent-runtime --env-file "$DEPLOY_ROOT/runtime.env" \
    -f "$DEPLOY_ROOT/postgres.compose.yaml")
```

如果該檔案不存在，或既有 PostgreSQL 使用不同 project／volume，**停止套用預設命令**，從該服務的實際 Compose files/project 還原 `PC`。例如舊部署可能使用 repo 的 `docker/compose.yaml` 加本機 override，且 project 不是 `session-agent-runtime`。改 project name 會建立不同 volume，不能將空資料庫當作升級成功。不要執行 `docker/runtime.sh restart`，它會啟動另一個 container Runtime。

例如已確認 project 為 `docker`、使用 repo Compose 加既有 override：

```bash
# REPO、EXISTING_OVERRIDE、EXISTING_COMPOSE_ENV 均取自已確認的既有部署。
# 原本由 repo helper 啟動時，EXISTING_COMPOSE_ENV="$REPO/docker/.env"。
PC=(env -u SESSION_AGENT_POSTGRES_PASSWORD docker compose -p docker \
    --env-file "$EXISTING_COMPOSE_ENV" -f "$REPO/docker/compose.yaml" -f "$EXISTING_OVERRIDE")
test "$("${PC[@]}" ps -aq postgres)" = "$(docker inspect --format '{{.Id}}' "$POSTGRES_CONTAINER")"
```

執行任何 lifecycle 操作前，確認此命令選到原容器，且其 data mount 與記錄的原 volume 一致。若既有部署刻意透過 shell 密碼覆寫，先在受限檔案中整理相同有效值，確認與現有資料庫吻合後才採用 `env -u`；不要直接套用不同密碼的 env file。

## 日常啟停

```bash
"${PC[@]}" up -d --wait postgres
systemctl --user start session-agent-omp-broker session-agent-omp-gateway session-agent-runtime
systemctl --user status session-agent-runtime --no-pager
journalctl --user -u session-agent-runtime --since '5 minutes ago' --no-pager
```

只重啟 Runtime：`systemctl --user restart session-agent-runtime`。先協調停止新訊息並等待工作完成；不要因為 queue 暫時為零就宣稱後續沒有新訊息。

正常停機：先停止 Runtime，再視需求停止 gateway／broker；資料庫可保留運行。需要停止資料庫時用 `"${PC[@]}" stop postgres`。**不使用 `down --volumes`、`docker volume rm` 或 Flyway clean。** systemd `enable` 與 user linger 不保證 Docker Desktop 在主機開機時已運行。

## 更新 Runtime（不更新 OMP）

更新前確認既有 Gateway 符合 [安裝前置條件](install.md#1-前置檢查)：使用已驗證的 native strictness 修正版 build，或包含同一修正的 upstream release。未修正的 OMP 18.12.6 會遺失 tool `strict: false`；明確 `false`／`true` 必須保留，省略時維持 Chat Completions 的 non-strict 預設。同一版本字串不代表同一 build，須保留 source revision／patch 與實際 executable checksum，不能覆蓋成未修正 binary。缺少相容性／provenance 證據時停止切換並回報，不在 Runtime update 中自行重建或升級 Gateway。Gateway 若另經授權更換，須再經 live 授權完成 [模型 → MCP → history 驗收](mcp.md#經授權的模型--mcp--history-實測)；health、catalog 或 discovery 不替代工具參數與持久化 observations 驗收。

### 1. 準備候選版本，不碰目前 artifact

取得使用者指定的 repo commit／tag，檢查工作目錄；不覆寫未提交修改。可在獨立 detached worktree 建置精確 revision：

```bash
# REPO 是現有 checkout，TARGET 是使用者選定的 commit 或 tag。
install -d -m 700 "$DEPLOY_ROOT/releases" "$DEPLOY_ROOT/backups"
git -C "$REPO" fetch origin
REV="$(git -C "$REPO" rev-parse "$TARGET^{commit}")"
BUILD_ROOT="$(mktemp -d)"
git -C "$REPO" worktree add --detach "$BUILD_ROOT/source" "$REV"
(
  cd "$BUILD_ROOT/source"
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn --batch-mode --no-transfer-progress test
  mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
)
test ! -e "$DEPLOY_ROOT/releases/$REV.jar"
install -m 600 "$BUILD_ROOT/source/target/session-agent-runtime-0.0.1-SNAPSHOT.jar" \
  "$DEPLOY_ROOT/releases/$REV.jar"
sha256sum "$DEPLOY_ROOT/releases/$REV.jar"
```

調整 `JAVA_HOME` 為已驗證 Java 21。若 release 已存在，先比對 checksum 與版本，不覆寫。測試失敗不能切換；保留目前服務。確認 migration 與部署資料庫版本之間的升級路徑；fresh-schema integration tests 不代表舊資料升級／反向相容性已證明。

### 2. 排空並停機

與使用者協調停止 HTTP／Slack 新提交；Runtime 目前沒有通用的 maintenance/intake-disable API。可在本機查詢工作與投遞數，不讀對話內容：

```bash
"${PC[@]}" exec -T postgres psql -U session_agent -d session_agent -Atc \
  "select status,count(*) from message_job where status in ('PENDING','WORKING','RETRY') group by status;"
"${PC[@]}" exec -T postgres psql -U session_agent -d session_agent -Atc \
  "select status,count(*) from slack_delivery where status in ('PENDING','WORKING','RETRY') group by status;"
```

等待完成；取消是另外的使用者決策，不為方便部署擅自取消。如果使用者選擇取消，可使用既有 HTTP cancel API 或 Slack thread stop，但仍須等待執行中的外部呼叫結束／逾時。`cancellationRequestedAt` 不代表已停止。

確認排空後：

```bash
systemctl --user stop session-agent-runtime
```

再查上述工作／投遞狀態，確定最後一刻沒有新工作進入。若有，先恢復舊 Runtime 處理或取得明確保留／取消策略，不帶著不明副作用或 model fingerprint 衝突切換。

### 3. 備份與保留舊版本

Runtime 停止、PostgreSQL 繼續運行：

```bash
umask 077
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP="$DEPLOY_ROOT/backups/$STAMP"
mkdir -m 700 "$BACKUP"
"${PC[@]}" exec -T postgres pg_dump -U session_agent -d session_agent -Fc > "$BACKUP/database.dump"
# 驗證 archive 可解析；不是完整 restore drill 的替代品。
"${PC[@]}" exec -T postgres pg_restore --list < "$BACKUP/database.dump" > "$BACKUP/database.contents"
sha256sum "$BACKUP/database.dump" > "$BACKUP/database.sha256"
install -m 600 "$DEPLOY_ROOT/session-agent-runtime.jar" "$BACKUP/runtime.jar"
for name in application.yml models.yml logback.xml runtime.env gateway.env; do
  install -m 600 "$DEPLOY_ROOT/$name" "$BACKUP/$name"
done
```

既有部署若 environment files 不同，備份其實際檔案，而非假定上列全部存在。另保存 unit、Runtime SHA/checksum、OMP 版本、資料庫 image/schema 版本；不要保存或匯出 broker OAuth store。備份含憑證和資料，留在受限主機儲存，不提交。

對重要環境，啟動新版本前應在隔離資料庫做 restore drill 與升級驗證；沒有可還原證據就不能承諾無損回滾。

### 4. 切換及驗證

只切換 artifact；不覆寫 private YAML/env，不替使用者升級 OMP，也不輪換 tokens：

```bash
# 此時 Runtime 已停止。
ln -s "releases/$REV.jar" "$DEPLOY_ROOT/session-agent-runtime.jar.next"
mv -Tf "$DEPLOY_ROOT/session-agent-runtime.jar.next" "$DEPLOY_ROOT/session-agent-runtime.jar"
printf '%s\n' "$REV" > "$DEPLOY_ROOT/runtime-revision"
systemctl --user start session-agent-runtime
```

若 `.next` 已存在，先調查未完成更新，不直接覆蓋。若修改 unit 才需要 `systemctl --user daemon-reload`；只改 YAML/jar 不需要。

按照 [verification.md](verification.md) 驗證版本、migration、health、gateway、Slack，必要時經授權進行 live smoke。重新開放新提交前保留舊 artifact 與備份。完成後可移除自己建立、沒有未保存資料的 worktree；不能 `--force` 丟掉未知修改。

## 回滾

先協調停新提交、排空並停止新 Runtime。**換回舊 jar 不會撤銷 Flyway migration。**

- 若已證明舊程式與目前 schema 相容：保留目前資料庫，還原備份的程式／所需 config，驗證後再啟動。
- 若 schema 不相容或無證據：先保持服務停止，取得資料還原與可能資料損失的明確授權。將舊備份還原到**新的隔離資料庫／volume**，驗證後讓舊 Runtime 指向它；保留目前資料庫供對帳，不直接清空。
- 備份之後的資料可能不在還原庫內；Slack 已送出的訊息與工具副作用不會回滾。重新處理／重播前必須對帳。

不提供自動破壞性回滾命令，也不在未驗證資料相容性時宣稱回滾完成。

## OMP 更新是獨立操作

Runtime update 不等於 OMP update。使用者另行要求更新 OMP 時，採用安裝的 `updating-omp-priority` skill（若可用）及該發行流程，保留既有 provider 登入、broker/gateway bearer 與回退版本。事先核對 gateway Chat Completions、模型 catalog、reasoning／工具呼叫轉換和重試行為；重啟相關 services 後重新驗證。不要在 Runtime update 中隱含執行 `latest` 升級或 `token --regenerate`。

## Runtime skills 內容更新與復原

- bodies／references 更新後，下次新讀取才用新 bytes；不會改寫既有對話。新提交
  `/skill:name` 可明確重讀，同 source redelivery 不會建立新讀取。
- 新增／改名、description 或 roots 設定異動要依排空流程重啟 Runtime；
  MCP refresh 不會重建 skill registry。不要以 symlink release 指標替代 root。
- 已提交 SKILL 的工作復原使用 PostgreSQL 中的 bytes，不因目前檔案修改或刪除
  而改寫該次載入；未提交的新選擇仍須讀當前檔案。保留 audit 與 source IDs。
- 內容按一般 compaction 處理，summary 可取代原文；不以重啟強制還原技能。
- 備份操作者管理的 skill 內容與私有 roots 設定，和資料庫備份分開記錄；
  SHA-256 是歷史證據，不提供舊檔版本倉庫或自動回滾。維持唯讀 mount／權限，
  不把 Runtime 維護或部署操作交給業務 skill 執行。
- loaded 狀態只確認已提交內容，不保證遵循或報表正確。反覆出現來源／欄位／
  coverage 錯誤時保留證據、停止盲目重試並升級模型品質／架構接受度決策，
  不以成功上傳、已載入或更多 prompt 修飾代替驗收。

## V11 continuation／技能通知升級

- 依前述流程停止新提交、排空 conversation jobs 與 Slack deliveries，再停舊
  worker、複查、備份 DB／jar／實際 private config。不得混跑舊新版本；舊 worker
  不會遵守持久化 report-only／one-shot control。
- 在隔離新 PostgreSQL restore 備份，用 candidate application 實際啟動 Flyway
  V11；Slack 關閉，模型端不可呼叫，MCP 不連正式服務。核對 schema version、
  history row/count/content、job total model calls 保持，ordinary usage 保守回填，
  不因歷史錯誤推測等待狀態。健康成功不等於已做模型或 Slack 驗收。
- 新版以單一 V11 migration 加入 purpose/mode/wait 與 skill attempt/progress
  metadata；不手工改 flyway history、不跳過 constraint、不靠舊 processor
  操作歷史 schema。出現 checksum／migration 錯誤先保留現場。
- 等待選擇是已完成 job 的 session metadata，不是需要強制重播的卡住工作。
  自然語言續查建立新 job；整理模式只用既有證據與本地 report tools。
  不把 paused job 的 artifact 改成 released，也不轉移到另一個 job。
- INTENT／WRAP_UP 已保留但結果遺失時不會自動再呼叫；安全釐清／等待通知
  是 fail-closed 行為，不以改 DB flags 或提高 allowance 掩蓋。
- 技能結果提交後直到精確 Slack revision ack 前仍會待送；重啟不表示重新載入。
  正常 ack 後 badge 消失，不刪 operational rows 來製造成功顯示。
- 回滾前確認 schema 與 binary 相容性；必要時只能按已驗證備份復原 DB。
  不因 jar 可切回就假定 V11 可由舊 worker 安全處理。保留原 DB volume。

## Database contract and Docker alternative

The following Docker commands are an alternative to the host deployment, not an additional Runtime instance. Run them from the repository root; do not reuse host-deployment ports or change an existing Compose project or volume.

The shipped database contract is a fresh schema applied through ordered Flyway migrations V1 through V11. V5 adds durable job progress and safe failure codes; historical terminal failures are backfilled only from an allowlisted final runtime event, not an earlier recoverable failure. Existing working rows may initially have no phase until reclaimed. V6 adds durable cancellation requests and the `CANCELLED` terminal state without rewriting existing history or progress. V7 adds durable Slack stop-command receipts and `STOP` delivery notices. V8 adds skill commands; V9 adds Slack-owned per-question progress and workspace cooldown persistence. V10 adds durable artifacts and Slack file-upload delivery state. V11 adds durable continuation/handoff control, ordinary-call accounting, waiting choices, and skill-load receipts with Slack notifications. Compatibility with removed pre-release schemas is not supported. Do not reset or delete the current database or its data as part of starting this version. The Compose service binds the runtime HTTP port to loopback only. Required local credentials are intentionally not committed.

Conversations and tool results are retained indefinitely. This POC adds no TTL or automatic cleanup. The OMP deployment skill documents operator-run database backup and schema-aware rollback; it does not add automatic backup scheduling or guarantee lossless restoration. Existing temporary continuation cleanup and Docker/host log retention remain unchanged.

## Docker JSON logs

Runtime emits one JSON object per line to both `docker compose logs` and a rolling host file. The active file is always `session-agent-runtime.log`; archives are named `session-agent-runtime.YYYY-MM-DD.N.log.gz`. Runtime rolls at 100 MB, keeps seven days, and caps retained archives at 500 MB. Docker's independent `json-file` driver remains at 100 MB with five files.

Initialize the local deployment configuration and prepare the bind directory for the non-root runtime UID/GID (10001). The default is the repository `logs/` directory:

```bash
./docker/runtime.sh init
# To persist a dedicated absolute host path into a newly created docker/.env:
SESSION_AGENT_LOG_DIR=/srv/session-agent-runtime/logs ./docker/runtime.sh init
```

`init` never overwrites an existing `docker/.env`; edit that file to provide `SESSION_AGENT_POSTGRES_PASSWORD` and any optional model, Slack, MCP, port, or log settings. The helper may ask for `sudo` only while preparing the host log directory. It can be invoked from any working directory.

Manage the complete Compose project through the same helper:

```bash
./docker/runtime.sh start    # validate, build, and run detached
./docker/runtime.sh status
./docker/runtime.sh restart  # down, rebuild, and start
./docker/runtime.sh stop     # preserves the PostgreSQL named volume
```

Follow container output and the active host file independently:

```bash
./docker/runtime.sh logs
# Default bind path, from the repository root:
tail -F logs/session-agent-runtime.log
# When SESSION_AGENT_LOG_DIR is set in docker/.env:
tail -F /srv/session-agent-runtime/logs/session-agent-runtime.log
```

The helper does not contain a data-reset command, and `stop` never passes `--volumes`; PostgreSQL data remains in the project-scoped named volume. The host directory is intentionally Git-ignored and keeps a stable active filename so a future Filebeat or Elastic Agent input can tail it. This POC does not deploy an ELK stack or a shipper. JSON lifecycle events contain only stable event names and safe correlation/outcome fields; Runtime never emits conversation text, Slack/model credentials, tool arguments/results, provider continuation data, or raw exception details in its lifecycle events.
