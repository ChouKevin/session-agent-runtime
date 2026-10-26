# 操作：更新、備份、回滾與啟停

憑證到期、OAuth 失效恢復與 token 撤銷／輪換見 [憑證維運流程](../../../../docs/gateway-deployment.md#6-撤銷輪換與故障處理)。版本確認見 [版本判讀](../../../../docs/gateway-deployment.md#7-驗證與版本判讀)；更新 Git checkout 不等於更新已部署的 Runtime。

## 先辨識既有部署

先在主機查看 Runtime unit、artifact 連結、config location，以及資料庫身分／volume mounts；敏感值只在本機處理，不回傳完整 environment、`docker inspect` 或 systemd properties。記錄 Runtime SHA/checksum、OMP 版本、source revision／patch、實際 Gateway service executable 的 SHA-256 checksum 與 PostgreSQL image identity。

先取得只含位置／身分的資料，不輸出秘密：

```bash
systemctl --user show session-agent-runtime \
  -p FragmentPath -p DropInPaths -p WorkingDirectory -p EnvironmentFiles
docker ps -a --filter name=local-postgres \
  --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
# 本機預設；其他 host 先確認實際服務身分，不直接照抄。
POSTGRES_CONTAINER=local-postgres
docker inspect "$POSTGRES_CONTAINER" --format 'image={{.Config.Image}} restart={{.HostConfig.RestartPolicy.Name}}'
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

主機部署的有效 datasource 來自私有 `$HOME/.local/share/session-agent-runtime/runtime.env`，由外部 `application.yml` placeholders 讀取。保留實際來源與密碼；不因更新範本而改值。模型 bearer 可能也位於既有 `runtime.env`，查檔只確認 key 是否存在，不顯示完整檔。

本文件的主機命令使用已確認的 standalone Docker 服務，不用 Compose：

```bash
set -euo pipefail
export DEPLOY_ROOT="$HOME/.local/share/session-agent-runtime"
POSTGRES_CONTAINER=local-postgres
```

本機 PostgreSQL 17 是 `local-postgres`，loopback `5432`，實體 legacy volume `gamecenter-local-postgres-data`。不得將它改名或交由 Compose 採用。`gamecenter_local`／owner `gamecenter` 與 `session_agent`／owner `session_agent` 分離；Runtime role 非 cluster superuser。以下 container-local SQL／dump 明確指定 Runtime owner `-U session_agent`，不使用 cluster 管理 role；本機 socket 認證方式須先確認。若其他 host 不支援此方式，使用其核准的 owner-only libpq secret／passfile 注入，不能為省事升權或把密碼放命令列。`pg_isready` 僅證明可接受連線，不證明密碼正確；Runtime health 與 owner 的 TCP 驗證另計。

若服務、mount 或憑證來源不相符，停止並辨識，不推測舊 Compose project、套回 override 或重建 volume。repo `docker/runtime.sh start/restart` 是明確 opt-in 的獨立全套容器部署，不是此主機服務的更新命令。

## 日常啟停

```bash
docker exec "$POSTGRES_CONTAINER" pg_isready -h 127.0.0.1 -p 5432 -U session_agent -d session_agent -t 5
systemctl --user start session-agent-omp-broker session-agent-omp-gateway session-agent-runtime
systemctl --user status session-agent-runtime --no-pager
journalctl --user -u session-agent-runtime --since '5 minutes ago' --no-pager
```

只重啟 Runtime：`systemctl --user restart session-agent-runtime`。先協調停止新訊息並等待工作完成；不要因為 queue 暫時為零就宣稱後續沒有新訊息。

正常停機：先停止 Runtime，再視需求停止 gateway／broker；**共用資料庫繼續運行，不隨 repo 啟停**。資料庫維護需另行協調所有 consumers 並取得授權，不是 Runtime 部署步驟。**不使用 `down --volumes`、`docker volume rm`、共享 DB reset 或 Flyway clean。** systemd `enable` 與 user linger 不保證 Docker Desktop 在主機開機時已運行。

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
  bash scripts/test-postgres-local.sh
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
docker exec "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U session_agent -d session_agent -Atc \
  "select status,count(*) from message_job where status in ('PENDING','WORKING','RETRY') group by status;"
docker exec "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U session_agent -d session_agent -Atc \
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
docker exec "$POSTGRES_CONTAINER" pg_dump -U session_agent -d session_agent -Fc > "$BACKUP/database.dump"
# 驗證 archive 可解析；不是完整 restore drill 的替代品。
docker exec -i "$POSTGRES_CONTAINER" pg_restore --list < "$BACKUP/database.dump" > "$BACKUP/database.contents"
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
- 若 schema 不相容或無證據：先保持服務停止，取得資料還原與可能資料損失的明確授權。先備份目前 target（含切換後寫入），再由核准的管理者提供**新的隔離資料庫**，以專用 owner restore 舊 custom dump，驗證 schema、資料 fingerprints／counts、sequence 與 owner TCP 連線後才讓舊 Runtime 指向它。保留目前資料庫供對帳，不直接清空共用 DB、不 reset volume；restore drill 不連 GameCenter 或正式 Runtime DB。
- 備份之後的資料可能不在還原庫內；Slack 已送出的訊息與工具副作用不會回滾。重新處理／重播前必須對帳。

不提供自動破壞性回滾命令，也不在未驗證資料相容性時宣稱回滾完成。

### 本機 5432 cutover 的保留來源

`retired-session-agent-postgres-15432` 已停止且 restart=`no`，原 `docker_session-agent-postgres` volume 只作 rollback 保留；不是第二個日常 DB。舊 private override 已移至受限備份，不再是 live 部署入口。Migration 備份位於 `$HOME/.local/share/local-postgres/backups/20261026-220504`（目錄700、檔案600），包含原 runtime env、dump 與 fingerprints；不提交或顯示內容。

若要回到保留來源，先停止 Runtime、保存目前 5432 target 的 fresh backup，評估兩邊 divergence。**取得使用者對回到舊資料狀態的明確決策後**，才恢復該備份的 runtime env（600）、啟動已確認的 retired source、讓 Runtime 指回來源並驗證；來源不包含 cutover 後 target 寫入，不能宣稱無損回滾。不自動合併 divergent writes、不刪任一 DB／volume、不停止仍供 GameCenter 使用的 local-postgres；restart policy 只有決定永久 rollback 才另行恢復。

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

The following Docker commands are an **explicit opt-in standalone full-stack deployment**, not the default host workflow or an additional Runtime instance. Run them from the repository root only after choosing this mode; do not reuse host-deployment ports or change an existing Compose project or volume. The helper always operates on `docker/compose.yaml` and `docker/.env`: `start` builds/starts Runtime and its own PostgreSQL, `restart` takes the entire project down and rebuilds it, and `stop` takes the entire project down. It does not manage or reuse standalone `local-postgres`; do not point its named volume at `gamecenter-local-postgres-data`. On the consolidated host, use systemd operations above, not this helper.

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
