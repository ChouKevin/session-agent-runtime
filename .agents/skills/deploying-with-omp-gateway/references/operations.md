# 操作：更新、備份、回滾與啟停

## 先辨識既有部署

先在主機查看 Runtime unit、artifact 連結、config location，以及 Docker Compose labels／volume mounts；敏感值只在本機處理，不回傳完整 environment、`docker inspect` 或 systemd properties。記錄 Runtime SHA/checksum、OMP 版本與 PostgreSQL image identity。

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
