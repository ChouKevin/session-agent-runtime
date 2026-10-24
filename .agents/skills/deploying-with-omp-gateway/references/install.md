# 安裝：主機 Runtime + OMP Gateway

只適用全新部署。既有服務走 [更新流程](operations.md)，不可用範本覆寫私有設定、改 Compose project name 或另建 volume。此方案不啟動 repo `docker/compose.yaml` 的 Runtime container，也不使用 `docker/runtime.sh start/restart`。

## 1. 前置檢查

需要 Linux/WSL、可用的 systemd user manager、Java 21、Maven、Python 3、Docker Compose v2，以及已安裝並完成模型 provider 登入的 OMP。OMP 安裝／登入由操作者透過核准的發行來源完成；不要替使用者選擇新帳號、匯入 OAuth 檔或自動升級。

本方案已使用 OMP **18.12.6** 驗證；其他版本先核對 `omp auth-broker serve --help`、`omp auth-gateway serve --help` 與 OMP `auth-broker-gateway` 文件。模型 catalog 仍是動態資料。

在 repo root 的 Bash 執行；後續同一 shell 使用這些變數：

```bash
set -euo pipefail
export REPO="$PWD"
export SKILL="$REPO/.agents/skills/deploying-with-omp-gateway"
export DEPLOY_ROOT="$HOME/.local/share/session-agent-runtime"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
/usr/bin/java -version
mvn -version
docker compose version
docker info >/dev/null
systemctl --user show-environment >/dev/null
command -v omp
omp --version
ss -ltn 'sport = :8090 or sport = :4000 or sport = :8765 or sport = :15432'
wait_http() {
  python3 - "$1" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.request
deadline = time.monotonic() + 90
while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen(sys.argv[1], timeout=2) as response:
            value = json.load(response)
        if value.get('ok') is True or value.get('status') == 'UP':
            print('Ready:', sys.argv[1])
            break
    except (urllib.error.URLError, TimeoutError, ValueError):
        pass
    time.sleep(1)
else:
    raise SystemExit('Readiness timed out; inspect the service journal before continuing.')
PY
}
```

`JAVA_HOME` 範例適用 Debian/Ubuntu；其他系統改成已安裝的 Java 21。服務範本使用 `/usr/bin/java`，若不是 Java 21，修改安裝後 unit 的 `ExecStart` 為已確認的絕對路徑。OMP unit 使用 `%h/.local/bin/omp`，若 `command -v omp` 不相符，也須調整兩個 OMP units。

若任一 port 已使用、部署目錄已存在或同名 user service 已安裝，先識別既有部署；不要覆寫或終止未知服務。Docker Desktop 的 bridge 在 VM 內，不能假設其 gateway IP 能在 WSL 綁定。所有服務維持 loopback。

## 2. 建立私有部署目錄及環境檔

確認為全新部署後：

```bash
test ! -e "$DEPLOY_ROOT"
install -d -m 700 "$DEPLOY_ROOT" "$DEPLOY_ROOT/releases" "$DEPLOY_ROOT/backups"
for name in application.yml models.yml logback.xml postgres.compose.yaml; do
  install -m 600 "$SKILL/assets/$name" "$DEPLOY_ROOT/$name"
done
python3 - <<'PY'
import os
import secrets
from pathlib import Path
source = Path(os.environ['SKILL']) / 'assets/runtime.env.example'
target = Path(os.environ['DEPLOY_ROOT']) / 'runtime.env'
text = source.read_text().replace('SESSION_AGENT_POSTGRES_PASSWORD=',
                                 'SESSION_AGENT_POSTGRES_PASSWORD=' + secrets.token_hex(32), 1)
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(text)
PY
```

以本機 editor 填入 `runtime.env` 的 Slack 三個欄位；不在聊天、shell history、Git 或 log 顯示憑證。三個全空代表停用 Slack，部分填入會啟動失敗。`SLACK_BOT_USER_ID` 是 bot user ID，不是名稱或 App ID。憑證格式與事件訂閱見 [configuration.md](configuration.md)。

`runtime.env` 是 systemd EnvironmentFile 與 Compose dotenv 的共同子集：一行一個 `NAME=value`；範例的 token/password 都使用不含空白、引號、`$` 或換行的值。不使用 `export`、命令替換，也不 `source` 這個檔案。若要使用含特殊字元的外部秘密，先分離各工具的編碼與 secret 注入方式，不能假設解析規則相同。

## 3. 建置並安裝精確版本

在已確認要部署的乾淨 checkout 執行：

```bash
REV="$(git -C "$REPO" rev-parse HEAD)"
# 若有未提交的產品變更，先處理版本追蹤，不把它偽稱為 REV。
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
install -m 600 "$REPO/target/session-agent-runtime-0.0.1-SNAPSHOT.jar" \
  "$DEPLOY_ROOT/releases/$REV.jar"
sha256sum "$DEPLOY_ROOT/releases/$REV.jar"
ln -s "releases/$REV.jar" "$DEPLOY_ROOT/session-agent-runtime.jar"
printf '%s\n' "$REV" > "$DEPLOY_ROOT/runtime-revision"
omp --version > "$DEPLOY_ROOT/omp-version"
```

`verify` 會產生 executable jar；integration tests 需要 Docker，但不使用部署資料庫或模型額度。固定 Runtime SHA 並記錄 OMP 版本，不能把重建中的 `target/` 當正式服務 artifact。

## 4. 啟動 PostgreSQL 與 OMP

Fresh install 使用固定 Compose project `session-agent-runtime`；日後所有操作保持相同 project、compose file 與 volume。所有 Compose 命令移除呼叫端的同名 password 環境變數，避免 shell 覆寫 `--env-file`，造成資料庫初始化密碼與 Runtime 不一致。

```bash
env -u SESSION_AGENT_POSTGRES_PASSWORD docker compose -p session-agent-runtime --env-file "$DEPLOY_ROOT/runtime.env" \
  -f "$DEPLOY_ROOT/postgres.compose.yaml" config --quiet
env -u SESSION_AGENT_POSTGRES_PASSWORD docker compose -p session-agent-runtime --env-file "$DEPLOY_ROOT/runtime.env" \
  -f "$DEPLOY_ROOT/postgres.compose.yaml" up -d --wait postgres

install -d -m 700 "$HOME/.config/systemd/user"
for unit in session-agent-omp-broker session-agent-omp-gateway session-agent-runtime; do
  test ! -e "$HOME/.config/systemd/user/$unit.service"
  install -m 600 "$SKILL/assets/$unit.service" "$HOME/.config/systemd/user/$unit.service"
done
# 必要時先修正 units 裡的 OMP / Java 絕對路徑。
systemd-analyze --user verify "$HOME/.config/systemd/user/session-agent-omp-broker.service" \
  "$HOME/.config/systemd/user/session-agent-omp-gateway.service" \
  "$HOME/.config/systemd/user/session-agent-runtime.service"
systemctl --user daemon-reload
systemctl --user enable --now session-agent-omp-broker
wait_http http://127.0.0.1:8765/v1/healthz
systemctl --user enable --now session-agent-omp-gateway
wait_http http://127.0.0.1:4000/healthz
```

Broker 讀取該使用者的既有 OMP credential store；gateway 使用 broker URL 與本機 token file。若原本採用遠端 broker 或不同 OMP config directory，先沿用既有憑證架構，不在此重建第二套登入。不要使用 `--no-auth`。

產生 Runtime 專用的本機 gateway 環境檔；這裡複用該 gateway 的 bearer，不是 provider OAuth token。管線不顯示 token，也不把它放進命令列參數：

```bash
omp auth-gateway token --json | python3 -c '
import json, os, sys
from pathlib import Path
data = json.load(sys.stdin)
target = Path(os.environ["DEPLOY_ROOT"]) / "gateway.env"
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as output:
    output.write("OPENAI_API_KEY=" + data["token"] + "\n")
'
```

不使用 `--regenerate`，否則已啟動的 gateway 不會自動接受新 bearer。若需要隔離多個應用，另外依 OMP 文件配置受限 client token；本單機範例的 bearer 可存取 gateway 已開放的模型，私有檔案存取權即信任邊界。

## 5. 檢查模型並啟動 Runtime

先按照 [verification.md](verification.md) 查 `/v1/models`，確認範例模型可用及容量正確；如果不可用，依 [configuration.md](configuration.md) 選擇已授權模型，不填假的 API key 或靜默切換 provider。

```bash
systemctl --user enable --now session-agent-runtime
wait_http http://127.0.0.1:8090/actuator/health
```

剛啟動可能尚未就緒；在有上限的啟動等待後查 service journal，不能把程序 `active` 當作完整成功。首次啟動會套用 Flyway migrations。完整驗收見 [verification.md](verification.md)。

需要登出後仍執行 user services 時，可由操作者執行 `loginctl enable-linger "$USER"`。Docker Desktop／WSL 本身仍須運行；linger 不會啟動 Windows Docker Desktop，也不代表主機重開後所有服務已可用。PostgreSQL 尚未可用時 Runtime 可能啟動失敗並由 systemd 重試。
