# MCP：連線、憑證與工具驗收

## 邊界與操作順序

MCP 設定屬於 **Runtime**，不屬於 OMP Gateway。Gateway 提供模型 API；Runtime 自己發現及執行 MCP 工具。OMP 的 MCP 設定、skills、server instructions 不會因使用 Gateway 而自動成為 Runtime 的提示或工具。此文件是部署參考，不是 agent 的業務查詢 skill。

| 操作 | 做法 | 完成條件 |
|---|---|---|
| 新增連線 | 合併具名 `connections`，注入該服務的憑證 | 每個預期連線各自 `AVAILABLE` 且有工具 |
| 實測工具 | 經授權提交獨立 Runtime job，檢查持久化 calls／observations | 無 job failure、兩個工具皆成功、最後有 assistant 回覆 |
| 暫停一個服務 | 該 entry 設 `enabled: false`，排空後重啟 Runtime | 該連線 `DISABLED`，其他連線仍可用 |
| 移除連線 | 刪除該 entry；最後一個移除時省略整個 `connections` | 診斷不再包含該連線 |
| 更換 token／URL | 更新私有來源，排空後重啟 Runtime | 重新驗證 discovery 與工具呼叫 |

Runtime 目前使用 **Streamable HTTP**，`url` 是完整 MCP endpoint，例如 `/mcp`，不是網站首頁或 health URL。不是 stdio `command/args` 或舊 SSE URL 設定格式。連線名稱須符合 `[A-Za-z][A-Za-z0-9-]{0,31}`；模型工具名稱為 `{connectionName}_{rawToolName}`。改名會改工具名稱，驗收請使用新 session，勿任意改動既有工作中的名稱。

## 範例：java-code-intelligence 兩個唯讀服務

這個範例假設已部署的 Source Query 與 Document Query 綁定主機 loopback：

| 連線名稱 | MCP endpoint | Header 的私有變數 | 權限 |
|---|---|---|---|
| `java-source` | `http://127.0.0.1:18080/mcp` | `SEMANTIC_QUERY_API_TOKEN` | 已發布、allowlist 內的 source/index 查詢 |
| `java-documents` | `http://127.0.0.1:18082/mcp` | `DOCUMENT_QUERY_API_TOKEN` | 已發布文件導覽查詢 |

兩個 token 分開管理；**不要連接 Indexer 管理介面或使用 `SEMANTIC_INDEXER_ADMIN_TOKEN`**。唯讀／allowlist 由 MCP provider 保證，不是 Runtime 根據名稱判定。註冊連線不會建立 source snapshot、index 或發布文件，也不授予新 repository 權限。

若使用既有 `java-code-intelligence` checkout，先閱讀它的 `docs/operations/source-mcp.md`、`docs/operations/document-mcp.md`，確認實際服務與私有 Compose 路徑。`.local/dual-mcp-trial/docker/compose.yml` 是某些主機的既有測試部署，不是每個 checkout 必有的安裝範本。已在運作就直接重用；不要重建 image、重置 volume、啟動 Indexer 或重新發布資料。已停止的既有 Compose readers 可用核對過的 project／compose files 執行 `docker compose ... start source documents`；缺少部署時依 provider 的安裝文件另行配置。

```bash
curl --fail --max-time 10 http://127.0.0.1:18080/actuator/health
curl --fail --max-time 10 http://127.0.0.1:18082/actuator/health
```

這只證明 provider health，不證明 token、MCP 初始化或工具成功。主機 Runtime 才可使用這兩個 `127.0.0.1` URL；若 Runtime 在容器裡，loopback 指容器自身，需使用實際可達且已授權的網路端點。非 loopback 使用受控 TLS／網路存取，不為了測試改成公開綁定。

### 1. 合併到生效的私有 application.yml

先依 [operations.md](operations.md) 辨識 unit、drop-ins、environment files 及 `SPRING_CONFIG_LOCATION`；保存私有備份。以下是 **局部片段**，合併到現有 `session-agent.mcp`，不要另建重複 YAML root 或覆寫資料庫、Slack、模型等設定：

```yaml
session-agent:
  mcp:
    connections:
      java-source:
        url: http://127.0.0.1:18080/mcp
        headers:
          X-Api-Token: ${SEMANTIC_QUERY_API_TOKEN}
      java-documents:
        url: http://127.0.0.1:18082/mcp
        headers:
          X-Api-Token: ${DOCUMENT_QUERY_API_TOKEN}
    refresh-interval: 60s
    request-timeout: 30s
    initial-backoff: 1s
    maximum-backoff: 60s
    shutdown-timeout: 5s
```

`enabled` 預設 `true`。Headers 使用 provider 規定的名稱和值；一般 bearer server 可設定 `Authorization: ${MCP_AUTHORIZATION}`，變數值包含 `Bearer ` 前綴。不需要認證時省略 `headers`。不要把 token 寫進 URL。

### 2. 注入私有環境變數

可加到 **現有 unit 已載入**的私有 environment file，保留所有原內容。也可建立獨立 `$DEPLOY_ROOT/mcp.env`，並加入 unit drop-in；以下採後者，`DEPLOY_ROOT` 沿用既有部署目錄：

```dotenv
SEMANTIC_QUERY_API_TOKEN=替換為SourceQuery唯讀token
DOCUMENT_QUERY_API_TOKEN=替換為DocumentQuery唯讀token
```

用本機安全編輯器／secret manager 填入真值，目錄 `700`、檔案 `600`，建立時使用 `umask 077`。這是欄位示意，不能直接登入。不要在 shell command、聊天、Git 或驗收輸出中輸入真值；不要 `source` env file。既有 provider token 可從其安全來源取得，勿輪換共享 token 只為了建立新 client。

此範例使用單行 `NAME=value`，無 `export`，token 不含空白、引號、`$` 或換行。若憑證有特殊字元，依實際 secret injection／systemd EnvironmentFile 編碼，不自行用 shell 展開。確認其他 environment files 與 inline `Environment=` 沒有同名值覆蓋。

建立 `~/.config/systemd/user/session-agent-runtime.service.d/mcp.conf`；若檔案已存在，先讀取並合併，不覆寫使用者設定：

```ini
[Service]
EnvironmentFile=%h/.local/share/session-agent-runtime/mcp.env
```

若私有部署目錄不同，改成其實際路徑。只載入 reader 所需的兩個 token，不載入包含 Indexer／Git credentials 的整包 provider env。

### 3. 排空、重啟、等待 discovery

依 [operations.md 的更新前排空步驟](operations.md) 協調停止新 HTTP／Slack 提交，確認未完成 jobs 與 Slack deliveries 都排空。單次查到零筆不等於已阻止新提交；不為部署方便取消工作。設定需重啟 Runtime，catalog 定時 refresh **不會重新載入 YAML 或 env**。

```bash
# 有新增／修改 unit 或 drop-in 才需要 daemon-reload。
systemctl --user daemon-reload
systemctl --user restart session-agent-runtime
python3 - <<'PY'
import json
import time
import urllib.request
expected = {'java-source', 'java-documents'}
deadline = time.monotonic() + 90
while True:
    try:
        with urllib.request.urlopen('http://127.0.0.1:8090/actuator/mcpConnections', timeout=5) as response:
            diagnostics = json.load(response)
        connections = diagnostics['connections']
        if all(connections.get(name, {}).get('state') == 'AVAILABLE'
               and connections[name].get('toolCount', 0) > 0 for name in expected):
            print({name: connections[name] for name in sorted(expected)})
            break
    except (OSError, ValueError):
        pass
    if time.monotonic() >= deadline:
        raise SystemExit('MCP discovery timeout; inspect safe diagnostics and local journal, do not resubmit jobs.')
    time.sleep(1)
PY
```

診斷格式為 `{"connections":{"java-source":{"state":"AVAILABLE","toolCount":10}}}`；10 是格式示例，不是固定契約。失敗時可附 `code`、`message`，不包含 URL、headers、token 或原始例外。Runtime 即使所有 MCP 不可用仍可啟動，所以 `health: UP` 不代表 MCP 成功。每個連線獨立重連，不用因一個服務失敗而重啟所有 MCP。

`enabled: false` 仍需合法的 connection URL；保留 headers 時其 `${...}` 也仍須能解析。若要移除 token，應一併移除不再需要的 header 或整個 connection entry，而非留下 unresolved placeholder。移除最後一個 connection 時省略 `connections`，不要填 `connections: {}`。

## 經授權的模型 → MCP → history 實測

會消耗模型額度並保存獨立測試 session，不發 Slack 訊息。以下只要求兩個 catalog 的第一頁，不讀業務原始碼／文件、不準備或發布任何資料。這是傳輸驗收，不是完整業務探索；實際業務查詢仍須依工具契約完成分頁、固定 source context 及取證。

先在同一終端建立一次唯一 key；**接收結果不明時不要再次執行這行或換 key**：

```bash
export MCP_SMOKE_KEY="mcp-smoke-$(python3 -c 'import uuid; print(uuid.uuid4())')"
```

提交下列固定測試內容。它先印出原 key，再印 receipt；保留這些非秘密識別資訊：

```bash
python3 - <<'PY'
import json
import os
import urllib.request
key = os.environ['MCP_SMOKE_KEY']
body = {'sessionKey': key, 'participantId': 'deployment-check', 'sourceMessageId': key,
        'message': 'Run a bounded MCP connectivity check, not a business search. Call java-source_list_repositories exactly once with arguments {"limit":1}. Call java-documents_list_documents exactly once with arguments {"limit":1}. Omit cursor, nameFilter, and repositoryId entirely: no cursor was returned yet and no repository was selected. Never invent cursor values such as start or a repositoryId from participantId. Do not paginate, read source/documents, retry a tool, or call any other tool. After these two calls, stop using tools and report only whether each call succeeded. If either fails, report failure and stop; do not retry.'}
print({'sessionKey': key, 'sourceMessageId': key}, flush=True)
request = urllib.request.Request('http://127.0.0.1:8090/internal/messages',
    data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'}, method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    print(json.load(response), flush=True)
PY
```

若 POST timeout 沒收到 receipt，保留原 `MCP_SMOKE_KEY`，僅重新執行上面的 Python block，**key、participantId、message 全部不變**。Runtime 的重複提交契約會回傳原 session/job receipt；若前次未被接受，才建立該唯一工作。這是有意識的同請求恢復，不是新測試；不要建立新 key 或設自動重試迴圈。新終端需先從先前輸出恢復原 key。拿到 receipt 後只查原 job，不再 POST。

保留 receipt 的 `sessionId`／`messageJobId`，使用下列 verifier；它只讀原工作，不會 POST。一般模型 smoke 的 `DEPLOYMENT_OK` assertion 不適用 MCP。

```bash
export MCP_SMOKE_SESSION_ID='填入receipt的sessionId'
export MCP_SMOKE_JOB_ID='填入receipt的messageJobId'
python3 - <<'PY'
import json
import os
import time
import urllib.request
base = 'http://127.0.0.1:8090'
session = os.environ['MCP_SMOKE_SESSION_ID']
job_id = os.environ['MCP_SMOKE_JOB_ID']
expected = {'java-source_list_repositories', 'java-documents_list_documents'}
def get(path):
    with urllib.request.urlopen(base + path, timeout=5) as response:
        return json.load(response)
deadline = time.monotonic() + 180
while True:
    job = get('/internal/message-jobs/' + job_id)
    if job['status'] in ('DONE', 'CANCELLED'):
        break
    if time.monotonic() >= deadline:
        raise SystemExit('Pending job ' + job_id + '; inspect the original job, do not resubmit.')
    time.sleep(1)
assert job['sessionId'] == session, 'Receipt IDs do not match'
history = [e for e in get('/internal/sessions/' + session + '/messages') if e.get('messageJobId') == job_id]
calls = [(e['sequence'], c) for e in history if e['type'] == 'ASSISTANT_TOOL_CALLS' for c in e['calls']]
observations = [e for e in history if e['type'] == 'TOOL']
print({'messageJobId': job_id, 'status': job['status'], 'failureCode': job.get('failureCode'),
       'modelCallCount': job['modelCallCount'], 'retryCount': job['retryCount'],
       'tools': [{'name': e['toolName'], 'isError': e['output'].get('isError')} for e in observations]})
assert job['status'] == 'DONE' and not job.get('failureCode'), 'Job did not succeed'
assert len(calls) == 2 and {c['toolName'] for _, c in calls} == expected, 'Unexpected or repeated calls'
assert len(observations) == 2, 'Missing or extra observations'
for sequence, call in calls:
    assert call['arguments'] == {'limit': 1}, 'Unexpected first-page arguments'
    matched = [e for e in observations if e['toolCallId'] == call['toolCallId'] and e['toolName'] == call['toolName']]
    assert len(matched) == 1 and matched[0]['sequence'] > sequence, 'Missing matching persisted observation'
    output = matched[0]['output']
    assert output.get('isError') is False, 'MCP tool failed'
    native = output.get('result', {})
    structured = native.get('structuredContent') or {}
    assert isinstance(structured.get('items'), list) and isinstance(structured.get('page'), dict), 'Not a catalog response'
assert history and history[-1]['type'] == 'ASSISTANT' and history[-1]['message'].strip(), 'No final assistant answer'
print('MCP model round-trip and persisted observations verified')
PY
```

檢查 `ASSISTANT_TOOL_CALLS.calls[]` 與 `TOOL` 的相同 `toolCallId`／`toolName`，以及 `output.isError` 和 provider 原生 `result`，不能只看回答寫「成功」。此 verifier 的 catalog shape 僅適用這兩個工具，不是 Runtime 的工具結果 schema；其他 MCP 要按自己的契約調整。不要輸出完整 arguments/results、對話或日誌作公開證據。

180 秒是驗收等待上限，不會取消工作。逾時查原 job，不重新 POST。`DONE` 可能仍有 `failureCode`，代表終止而非成功；模型可能沒呼叫工具、重複呼叫或產生錯誤參數，必須保留失敗結果，不調高限制來湊通過。

## 故障定位與憑證輪換

| 現象 | 判斷與行動 |
|---|---|
| Provider health UP，Runtime MCP 不可用 | 檢查完整 `/mcp`、Streamable HTTP、Runtime 到端點網路及正確 header；health 不驗證 MCP token |
| 一個 reader 失敗 | 查該 connection 的 safe `code`；維持另一個連線運作，不更換它的憑證 |
| 初始化／工具 401 | 檢查實際 environment source、token 權限與 provider header；不輸出 token、不改用 admin token |
| `AVAILABLE` 但工具 `INVALID_ARGUMENT` | 檢查已存呼叫參數及 provider 原始錯誤；不是 discovery 成功就能排除模型／Gateway schema 問題 |
| 模型自填 `cursor: "start"` 等不存在的 cursor | 第一次呼叫應省略 cursor；以直接 MCP 同參數呼叫及 Gateway 合成工具測試分離層次。不修改 Runtime 來刪除 provider 特定參數，不替 MCP 接受假 cursor |
| `MODEL_CALL_LIMIT_REACHED` 或重複相同失敗 | 該 smoke 不通過。保留 job ID，停止付費重試，定位參數／schema／模型路由；不要只增加呼叫上限 |
| 兩個工具成功但 Slack 沒回覆 | 這是不同邊界；依 [Slack 真人驗收](verification.md#slack-真人驗收) 檢查事件與投遞 |

直接 MCP 驗證需遵循 initialize → initialized → tools/list／tools/call；Source Query 維持 server-issued session ID／協商 protocol，結束 DELETE session；Document Query 為獨立 stateless server。直接 MCP 成功不能替代模型端到端驗收；反之，Gateway 的 optional schema 相容性失敗也不表示 MCP token 壞了。不要把未驗證的 `PI_NO_STRICT` 開關當作通用修復；模型／Gateway 升級或切換須另行核准並重跑同一驗收。

輪換 token：先確認 provider 是否支援新舊憑證並存、哪些 clients 共用舊值；協調維護窗並排空。可並存時先發新 reader token、更新 Runtime 私有值並重啟驗證，再撤銷舊值；不可並存時協調 provider 與所有受影響 clients 一起切換，接受該連線短暫不可用。不要為本 client 單方面輪換共享 token。更動 unit 要 daemon-reload，只改 env／YAML 則重啟 Runtime。撤銷舊 token 後不能宣稱回復舊 env 就能回滾，必須按 provider 的憑證恢復流程處理。
