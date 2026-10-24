# 驗證：分開確認服務、模型與 Slack

## 不消耗模型額度的檢查

```bash
export DEPLOY_ROOT="$HOME/.local/share/session-agent-runtime"
systemctl --user is-active session-agent-omp-broker session-agent-omp-gateway session-agent-runtime
curl --fail --max-time 10 http://127.0.0.1:8765/v1/healthz
curl --fail --max-time 10 http://127.0.0.1:4000/healthz
curl --fail --max-time 10 http://127.0.0.1:8090/actuator/health
curl --fail --max-time 10 http://127.0.0.1:8090/actuator/mcpConnections
ss -ltn 'sport = :8090 or sport = :4000 or sport = :8765 or sport = :15432'
```

預期 Runtime `status: UP`；listener 均為 loopback。MCP 未設定時 `connections` 為空是正常狀態，不代表 OMP 工具已接入。首次啟動用有上限的等待，逾時檢查 journal，不無限重試。

有 MCP 時依 [MCP 操作指南](mcp.md) 逐一驗證 `state`／`toolCount`，再經授權檢查模型產生的 calls 與持久化 observations。下方一般模型 smoke 明確禁止 tools，不能作為 MCP 驗收。

`omp auth-gateway status` 不是 gateway listener 的 health probe。查 `/healthz` 與 authenticated `/v1/models` 才能分別證明 listener 與 bearer 可用：

```bash
# 全新安裝使用 gateway.env；既有部署改成實際持有 OPENAI_API_KEY 的檔案。
export GATEWAY_ENV_FILE="${GATEWAY_ENV_FILE:-$DEPLOY_ROOT/gateway.env}"
python3 - <<'PY'
import json
import os
import re
import urllib.request
from pathlib import Path
path = Path(os.environ['GATEWAY_ENV_FILE'])
values = dict(line.split('=', 1) for line in path.read_text().splitlines()
              if line and not line.startswith('#') and '=' in line)
token = values['OPENAI_API_KEY'].strip()
if len(token) >= 2 and token[0] == token[-1] and token[0] in ('"', "'"):
    token = token[1:-1]
assert re.fullmatch(r'[A-Za-z0-9._~+/=-]+', token), 'Unsupported bearer file encoding; use the installed secret adapter'
request = urllib.request.Request('http://127.0.0.1:4000/v1/models',
    headers={'Authorization': 'Bearer ' + token})
with urllib.request.urlopen(request, timeout=15) as response:
    catalog = json.load(response)
for model in catalog['data']:
    if model['id'].startswith('openai-codex/'):
        print({key: model.get(key) for key in
               ('id', 'context_length', 'max_output_tokens', 'supports_tools')})
PY
```

既有部署若 bearer 在 `runtime.env`，先設定 `export GATEWAY_ENV_FILE="$DEPLOY_ROOT/runtime.env"`，不必建立新檔或輪換 token。此 reader 支援單行 `KEY=value` 與無跳脫的單／雙引號 token；不使用 shell `source`、不輸出憑證。多行／跳脫或其他 secret manager 由既有部署 adapter 提供，不猜測解析。若有多個 environment files，選 service 最後生效的來源，並確認沒有 unit inline override。`supports_tools` 未出現不等於已證明工具呼叫成功。

查目前 artifact checksum／revision、OMP version、Flyway migration 成功與 service journal；輸出只摘要安全欄位，不分享完整 log 或 environment。典型失敗定位：

| 現象 | 檢查 |
|---|---|
| `google-primary` 缺 key | 外部完整 config 是否真正透過 `SPRING_CONFIG_LOCATION` 取代預設 |
| `/app/logs` 無法建立 | unit 的 `LOGGING_CONFIG` 是否指向主機範本 |
| MCP connections map 無法 bind | 無連線時是否誤寫空 map；應省略該欄位 |
| `EADDRNOTAVAIL` | 是否把 Docker VM bridge IP 當成本機可綁位址 |
| Gateway 401／模型不可見 | bearer 與 catalog；不輸出 token，不自動重建登入 |
| PostgreSQL connection refused | Docker Desktop、資料庫健康狀態與 loopback port mapping |
| Runtime UP 但 Slack 沒回覆 | Socket、事件訂閱、權限、bot 是否在頻道、投遞狀態分開查 |

## 經授權的真實模型 smoke

以下會消耗 provider 額度並在資料庫保存一個獨立測試 session。必須先取得此操作授權；一般 health/check 不執行。它不發送 Slack 訊息。

```bash
python3 - <<'PY'
import json
import time
import urllib.request
import uuid
base = 'http://127.0.0.1:8090'
key = 'deployment-smoke-' + str(uuid.uuid4())
body = {'sessionKey': key, 'participantId': 'deployment-check',
        'sourceMessageId': key,
        'message': 'Reply with exactly DEPLOYMENT_OK. Do not call tools.'}
request = urllib.request.Request(base + '/internal/messages',
    data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'}, method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    receipt = json.load(response)
print({'sessionId': receipt['sessionId'], 'messageJobId': receipt['messageJobId']}, flush=True)
deadline = time.monotonic() + 90
while True:
    with urllib.request.urlopen(base + '/internal/message-jobs/' + receipt['messageJobId'], timeout=5) as response:
        job = json.load(response)
    if job['status'] in ('DONE', 'CANCELLED'):
        break
    if time.monotonic() >= deadline:
        raise SystemExit('Smoke still pending for job ' + receipt['messageJobId'] + '; inspect it, do not resubmit.')
    time.sleep(1)
with urllib.request.urlopen(base + '/internal/sessions/' + receipt['sessionId'] + '/messages', timeout=5) as response:
    history = json.load(response)
answers = [event.get('message', '') for event in history if event['type'] == 'ASSISTANT']
assert job['status'] == 'DONE' and not job.get('failureCode'), job
assert answers and answers[-1].strip() == 'DEPLOYMENT_OK', 'Unexpected terminal answer'
print({'status': job['status'], 'modelCallCount': job['modelCallCount'],
       'retryCount': job['retryCount'], 'answer': answers[-1]})
PY
```

`DONE` 單獨不足以證明成功，還須檢查 failureCode 與最終 ASSISTANT 內容。90 秒 smoke deadline 不會取消工作；逾時依原 job ID 查詢，不重送同題製造多次付費請求。模型回覆成功不單獨證明 provider 接受了每個 generation option；reasoning 設定來源、adapter 對應與 gateway 版本也須核對。

## Slack 真人驗收

1. Journal 看到 `slack_connection_available`／`AVAILABLE`；這只證明 Socket Mode 連線。
2. 由使用者在 bot 已加入的頻道發送 `@bot` 問題，確認排隊通知及模型回答在同一 thread。
3. 在已綁定 thread 發送 `@bot stop` 或 `@bot 停止工作`。若沒有未完成工作，應收到 no-work 提示；若有工作，先收到請求確認，執行中工作等安全點後才取消。
4. 有需要才測不帶 mention 的 thread 續聊，並先確認 message events/history scopes。

Bot 發出的訊息會被 noise guard 過濾，不能用 `chat.postMessage` 偽造真人停止指令來宣稱 inbound end-to-end 成功。也不能偷偷使用使用者 token 模擬真人。

停止的邊界／去重／滿載行為由 repo fake-backed 與 PostgreSQL integration suite 覆蓋；live Slack 驗收與它們是不同證據。回報清楚列出已驗證、尚未驗證及設定不足之處，不將 Socket AVAILABLE 或健康檢查當作全面驗收。
