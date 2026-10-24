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

## 每題 Slack 狀態驗收（本地隔離與真人證據分開）

先在經授權的隔離 PostgreSQL、實際 Runtime、實際 configured model adapter 與
Slack Java SDK 執行；外部 HTTP 指向 loopback fake，Socket intake 用既有 adapter
注入 synthetic events。不要新增 production probe hooks，也不要將此流程當作
真人 Slack inbound、Socket 傳輸或 native client UI 的證明。

1. 阻塞模型 HTTP，送同一 thread 兩題與另一 session 一題。記錄每題唯一初始
   notice 的已確認 ts、job/source 對應及 `chat.update` 目標；前題 active、後題
   queued、另一 session 可獨立執行。保持阻塞超過 30 秒，觀察真正 UTC 時間刷新。
2. 確認 `chat.getPermalink(channel, message_ts)` 回傳的特定問題 URL 已驗證並
   持久化；thread reply 保留 provider 的 `thread_ts`／`cid` query，不能以未文件化
   `slack://...&message=` 或猜測 workspace domain 代替。此 API 不增加 scope。
   Lookup 與 update 分不同 claim／poll，避免兩次 HTTP 共用一個 lease。
3. 注入 update 503／429（含 `Retry-After`），確認 progress 延後但獨立 answer
   worker 仍送回答；永久刪除／權限錯誤停止該 target，不產生 replacement spam。
   Multipart 未全數 `SENT` 前只能是準備傳送或傳送失敗，不能因 job `DONE` 宣稱完成。
4. 經既有 HTTP cancel／Slack stop adapter 路徑觀察 queued cancellation、
   active stopping → cancelled；停止 worker renewal 後等真實 lease 到期，應顯示
   interrupted 而非 running。重啟同一隔離資料庫並重播 source，應重用 notice ts
   與 cached permalink；不因 progress／source replay 增加 model calls 或 history。
   真正中斷工作的 recovery 可新增正常模型 request，須與 progress 分開計數。
5. 保存模型 HTTP requests、公開 history、delivery parts 與 progress rows 的對照，
   證明 status 不進模型／history，且沒有 tool arguments、SHA 或內部錯誤內容。
   證據放 Git 外私有位置；移除 launcher source／compiled scaffolds 並停止 fake
   HTTP、Runtime、Testcontainers。完整 gate 為 Java 21 的
   `mvn --batch-mode --no-transfer-progress -Ppostgres-it clean verify`，需報告完整
   ordinary phase 與 PG phase counts；不只挑 progress tests。

預設 poll 2s、同狀態 refresh 30s、workspace progress API spacing 2s；跨 replicas
共用 durable gate，backlog 與 Retry-After 可延遲。這是「最後確認」，不是模型遠端
進度／百分比或外部 heartbeat；整個 Runtime 停機時會留下舊時間。Progress failure
不影響 answer／model retry。Terminal 第一次 ack 後仍保留至少 delivery lease
window 的 reconciliation（預設 30s > call timeout 25s），但 Slack 沒有 remote CAS，
延遲 accepted update 仍可能短暫覆蓋新文字；不得保證 exactly-once 或 remote monotonic。
初始 post outcome 不明也不授權 replacement。

另取得 live 授權後，才由真人檢查特定問題 link 在實際 Slack client 導航及每題各自
更新。Bot 必須有 `chat:write` 且只能 update 自己的訊息；不擴大權限或使用 user
token 冒充真人。官方契約：
[`chat.update`](https://docs.slack.dev/reference/methods/chat.update/)、
[`chat.getPermalink`](https://docs.slack.dev/reference/methods/chat.getPermalink/)。

## Runtime skills 驗收（與 Gateway／真人 Slack 分開）

一般可先在隔離 PostgreSQL 與本地 fake provider 執行，不能以 tests 通過或
health 正常取代實際 Runtime HTTP → worker → PostgreSQL 的觀察。對 live
provider／Slack 的請求仍須另取得授權，不能自動沿用本地 smoke 授權。

1. 用獨立 session/source ID 提交 `/skill:review 問題`，poll 到 DONE 並確認
   failureCode 為空；公開完整／分頁 history 應有原 USER、真實 SKILL
   `uri`／`sha256`／`content` 與 ASSISTANT，不是偽造 native tool call。
2. 修改 body A→B；重送原 source ID 必須回原 receipt、不增加事件／模型呼叫。
   用新 source ID 再送 `/skill:review` 才有 B。metadata 仍是啟動時內容。
3. 自動選擇要觀察模型真實 `readSkill` calls 與 matching TOOL IDs／output，
   同批多 calls 必須完整且按序；缺檔失敗仍是普通 model-visible TOOL observation。
4. 無效 `/skill:../bad 唯一測試標記` 工作應零 model calls、SKILL_UNAVAILABLE，
   USER 與錯誤仍在 audit；後續普通請求與 compaction summary 都不得含該標記
   或該 rejection feedback。不要用錯誤 code 字串過濾自動工具失敗。
5. 檢查 pending 的 queued command 在前一工作 response／summary 中不可見；
   原 USER sequence 即使低於先前 compaction boundary，接受後其問題仍與 SKILL
   進入當次 context 一次。之後一般 compaction 可移出兩者，不得自動復原。
6. 已提交 selection 後中斷並重啟工作，應使用持久化 bytes，即使當前 body 已變；
   記錄 job/source、事件序列、重試與 modelCallCount、正常／summary 請求。
   summary 不帶 tools；初始 metadata description 不預載 body。

Slack fake-backed cases 只證明 adapter normalization／disposition，不證明真人
workspace inbound。真人驗收另用 `@bot /skill:review 問題`，含 bound thread
reply，並確認原 stop 優先序；不註冊 slash command、不以 bot 訊息冒充真人。
證據含對話／技能原文時留在私有 Git 外，公開回報僅列必要結果與限制。
