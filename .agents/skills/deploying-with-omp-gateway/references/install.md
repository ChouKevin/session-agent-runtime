# 安裝：主機 Runtime + OMP Gateway

只適用全新部署。既有服務走 [更新流程](operations.md)，不可用範本覆寫私有設定、改 Compose project name 或另建 volume。此方案不啟動 repo `docker/compose.yaml` 的 Runtime container，也不使用 `docker/runtime.sh start/restart`。

## 1. 前置檢查

需要 Linux/WSL、可用的 systemd user manager、Java 21、Maven、Python 3、Docker Compose v2，以及已安裝並完成模型 provider 登入的 OMP。OMP 安裝／登入由操作者透過核准的發行來源完成；不要替使用者選擇新帳號、匯入 OAuth 檔或自動升級。

本方案使用的是修正過的 native OMP source build，不是未修正的 **18.12.6** 發行 binary；只看 `omp --version` 不能證明相容。未修正的 18.12.6 會在 Chat Completions → provider protocol 轉換時遺失 tool `strict: false`。前置條件是已驗證的修正版 build，或包含同一修正的 upstream release：明確 `false`／`true` 必須保留，省略 strictness 時仍須保持 Chat Completions 的 non-strict 預設。保存 source revision／patch 與實際 service executable 的 SHA-256 checksum；本機 patched build 不是官方 upstream release。不要因版本字串相同就替換成未修正 binary，也不在此安裝流程隱含重建或升級 Gateway。完整邊界見 [模型與 Gateway 邊界](configuration.md#model-adapter-and-gateway-boundaries)。

其他版本仍須核對 `omp auth-broker serve --help`、`omp auth-gateway serve --help` 與 OMP `auth-broker-gateway` 文件；模型 catalog 是動態資料。首次啟用或更換 Gateway 後，取得明確 live 授權並完成 [模型 → MCP → history 驗收](mcp.md#經授權的模型--mcp--history-實測)；health、`/v1/models` 或 discovery 成功不能替代工具參數及持久化 observations 驗收。未獲授權時明確回報此邊界未驗證。

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

若要交付 Markdown/CSV 附件或自動長回答 `answer.md`，先核准 app 的
`files:write` scope 與 Slack 要求的 reinstall／重新授權，再另行核准 live 檢查。
不把部署授權當成修改 scope/token 的授權；未核准就保留現況並回報 delivery
限制，不用截斷／純文字 fallback 假裝成功。Native Markdown 的實際支援
（含 workspace/platform feature 條件）必須在安裝 app 的環境另驗證。
細節及 CSV 轉換／保留政策見 [configuration.md](configuration.md)。

附件 bytes 與 upload capability 由 PostgreSQL 保存，不需要 public download
目錄、額外檔案服務或放寬 filesystem 權限。更新前沿用
[operations.md](operations.md) 的備份／migration 相容性流程，把 artifact、
upload stages、對話及備份視為同一敏感資料邊界；本功能不新增自動刪除。
尚有 `FINALIZING`／`UNCONFIRMED` 證據時，不用重置 stage 或重送問題修復；
保留原 file/job ID 供操作者核對，避免一次性 finalize 被重播。

若明確啟用 Runtime skills，另備操作者管理的內容目錄，依
[configuration.md](configuration.md) 設定 roots 與 `SKILL.md`。Runtime service
帳號只需 read/traverse，不應持有寫入權限；同帳號部署的 Unix owner mode 不能
形成對操作者的安全隔離，需獨立 owner／帳號或唯讀 mount 才能限制 Runtime。
容器部署必須掛載指定內容 root 為 `:ro`，不能掛整個 checkout、home 或憑證目錄。
不要把本部署指南自動安裝成 Runtime skill，也不增加 shell 工具或放寬檔案權限。
roots／祖先不能是 symlink；啟動前確認 service account 能讀檔。
啟用 roots 需要 Java filesystem provider 支援 `SecureDirectoryStream`；
不支援會 fail closed、拒絕啟動。預期使用 Linux 上支援此 API 的本地 filesystem
provider，不能假設所有 Linux filesystem／mount 都支援。檔案大小限制只限制
讀取 bytes，不保證 open latency；若具權限的並行 writer 將檔案換成 FIFO／其他
special file，regular-file 檢查與 no-follow open 不等於 timeout 防護。這是可信
操作者管理 roots 的邊界，不是對抗惡意 writer 的隔離機制。

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

`verify` 會產生 executable jar；integration tests 需要 Docker，但不使用部署資料庫或模型額度。固定 Runtime SHA 並記錄 OMP 版本、source revision／patch 與實際 Gateway executable checksum，不能把重建中的 `target/` 當正式服務 artifact，也不能以 OMP 版本字串代替 build provenance。

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
