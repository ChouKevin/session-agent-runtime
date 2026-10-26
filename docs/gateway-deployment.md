# Gateway 部署與憑證管理

本頁是新主機部署的入口：先選部署方式，再依既有 runbook 安裝、設定與驗證。Java Runtime 不管理 OAuth、不啟動 gateway，也不讀取 OMP credential database。

## 1. 選擇部署方式

| 方式 | 新 Runtime host 需要 OMP OAuth 登入嗎？ | 操作路徑 |
| --- | --- | --- |
| 同一 host 獨立部署 Runtime、gateway、broker | Broker 需要有效的 provider 憑證；新建時由操作者登入 | 下方登入步驟 → [單機安裝](../.agents/skills/deploying-with-omp-gateway/references/install.md) |
| 新 host 只部署 Runtime，共用既有 gateway | 不需要安裝 OMP 或複製 OAuth 憑證 | 下方跨主機連線 → 專屬 client token → [模型設定](../.agents/skills/deploying-with-omp-gateway/references/configuration.md) |
| Runtime 直接連支援的模型 provider | 不需要 OMP；需要該 provider 的 API 憑證 | [模型 adapter 與設定邊界](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries) |

新主機仍須準備 Java 21、資料庫、外部 Runtime 設定及所需 MCP／Slack 設定。共用 gateway 不代表共用 Runtime 資料庫或啟動第二個相同 Slack Socket consumer。

## 2. 獨立部署：安裝與 OAuth 登入

1. 從核准來源安裝 OMP，核對 [相容性前置條件](../.agents/skills/deploying-with-omp-gateway/references/install.md#1-前置檢查)。不要把相同版本字串當成相同 patched build。
2. 在 **broker 所在主機，以執行 broker 的 OS 使用者及相同 OMP profile/config root** 執行：

   ```bash
   omp auth-broker login openai-codex
   ```

   依互動流程選擇經授權的帳號。已有 broker 或遠端憑證架構時先確認來源，不另建或覆寫。不要從 repo 複製 `agent.db`，也不要自動匯入別人的 OAuth dump。
3. 依 [安裝指南](../.agents/skills/deploying-with-omp-gateway/references/install.md) 建立私有目錄、PostgreSQL、systemd units 與 Runtime model profiles。
4. 檢查 gateway `/healthz`、帶 bearer 的 `/v1/models` 及 Runtime health。真實模型與 Slack 測試另外取得授權，見 [驗證指南](../.agents/skills/deploying-with-omp-gateway/references/verification.md)。

本地 shell 與 service 的環境可能不同。Gateway unit 的 `OMP_AUTH_BROKER_URL` 不會自動進入登入 shell；CLI 顯示 `not_configured` 不足以證明執行中的 gateway 壞掉。狀態檢查須使用該服務相同的 broker URL、profile 及憑證來源，不輸出完整 environment。

## 3. 共用 gateway：跨主機連線

單機範本綁定 `127.0.0.1:4000`，不能由其他 host 直接存取。不要為了連線改成公開、無認證的服務。可選：

- SSH tunnel：沿用 gateway 的 loopback listener。
- 受控 VPN／TLS reverse proxy：限制來源、保護 bearer，保留 gateway 認證；由網路管理者配置憑證與 ACL。

SSH 範例（在新 Runtime host 執行；先替換核准的使用者與主機名稱）：

```bash
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 -L 127.0.0.1:14000:127.0.0.1:4000 operator@gateway-host
```

Runtime 的 `base-url` 使用 `http://127.0.0.1:14000/v1`。先驗證 SSH host key，不停用 host-key checking。此命令是前景連線示範，正式環境需由操作者配置受監督的 tunnel service 與啟動順序；SSH 連線中斷會使模型呼叫失敗。不要把 broker port 或 OAuth DB 分享給 Runtime host。

共用 gateway 的 Runtime unit **不要直接照抄單機版的 OMP service 依賴**；只部署所需 Runtime／資料庫服務，並設定實際可達的 endpoint。依賴與 EnvironmentFile 必須以部署環境為準。

## 4. 三層憑證與生命週期

| 憑證 | 持有者／用途 | 生命週期 |
| --- | --- | --- |
| Gateway bearer 或 scoped client token | Runtime → gateway | 本指南所述 OMP 機制沒有自動 TTL；操作者撤銷或輪換 |
| Broker bearer | Gateway → broker | 靜態 token；操作者輪換，不提供給 Runtime |
| Provider OAuth access／refresh token | Broker 管理，gateway 使用 access token 呼叫 provider | Access token 有期限；broker 嘗試刷新，refresh 授權仍可能失效或被撤銷 |

OMP 預設 broker 背景每 60 秒檢查，對距到期 5 分鐘內的 OAuth 憑證嘗試刷新；以安裝版本與實際 broker 設定為準。刷新成功不需要改 Runtime bearer。暫時網路錯誤不等於永久失效；確定的授權失敗可能停用該憑證，需要重新登入。額度不足也不是 token 過期，不以重複登入或重送模型請求處理。

預設檔案為 `~/.omp/auth-gateway.token`、`~/.omp/auth-broker.token` 與 `~/.omp/agent/agent.db`；自訂 config root/profile 會改變位置。私有目錄使用 `700`、憑證檔使用 `600`；檔案權限不是靜態加密保證。OAuth DB、env、token、備份與原始診斷不得提交 Git，也不可輸出完整 broker snapshot（可能含 access token）。

## 5. 每個 Runtime 使用專屬 client token

單機安裝範本複用 **共用 gateway bearer**，不是限制帳號的 client token。多 host／多應用部署建議改用 scoped token；此節是操作流程，不會自動修改現有服務。

先核對已安裝的 `omp auth-gateway --help` 是否支援 `--clients`，並閱讀該版本的 OMP `auth-broker-gateway` 文件。Scoped client 設定檔是名稱到授權規則的 JSON map，例如：

```json
{
  "runtime-host-a": {
    "tokenSha256": "<client token 的 64 字元十六進位 SHA-256>",
    "models": ["openai-codex/gpt-6.1-sol", "openai-codex/gpt-6-luna"],
    "accounts": {
      "openai-codex": ["<精確 OAuth identityKey，包含組織限定資訊>"]
    }
  }
}
```

以上占位值不可直接啟用；模型 ID 也須先經 catalog 確認。Identity 必須由該 broker 的安全帳號列表取得，不能只憑 email 猜測。安裝版本提供 `omp usage accounts` 時，用與 gateway 相同的 broker/profile 環境查詢；不要輸出 access／refresh token。

操作順序：

1. 在受限主機以密碼學安全來源產生每個 client 獨立 token（例如至少 32 個隨機 bytes），寫入私有 secret 檔，不放命令列或 Git。以 **不含換行的 token 原文**計算 SHA-256；JSON 只存 digest。
2. 以私有 JSON 設定模型與帳號 allowlist。`accounts` 也限制 provider；不可授予未列入 accounts 的 provider。省略限制代表更寬的存取，不是安全預設。
3. 在原 gateway service 的 `ExecStart` 加上 `--clients=/absolute/private/gateway-clients.json`，保留既有 bind、broker 與其他設定；排定服務變更，再 daemon-reload／重啟並驗證。不要啟動第二個 gateway 搶同一 port。
4. 將 client token 安全交付該 Runtime，更新 **實際最後生效**的 `OPENAI_API_KEY` 來源。先以新 token 查 `/v1/models` 確認可見範圍，再依排空流程重啟 Runtime。不要假設既有服務讀的是 `gateway.env`。
5. 刪除舊 client entry 或替換 digest 即可撤銷該 client。此版本 serving 過程約每 10 秒重讀設定；在途請求仍可能完成。以同檔案系統的暫存檔加 rename 原子替換，不讓服務讀到半份 JSON。

Client token 不能查涵蓋所有帳號的 `/v1/usage`、`/v1/credentials/check`（403 是預期限制）。多個帳號的 allowlist 不是固定主備順序；同一 client/provider 的對話共享帳號 pin，可因額度限制或認證恢復切換，切換仍限於 allowlist。若要指定帳號，只列該精確 identity；不要把政策優先權當成隔離邊界。

**共用 gateway bearer 仍然有效且不受 client allowlist 限制。** 啟用 `--clients` 不會撤銷先前散發的共用 token。完成 client 遷移後，評估下節輪換共用 bearer，避免舊 token 繞過限制。

## 6. 撤銷、輪換與故障處理

| 情況 | 操作 |
| --- | --- |
| 單一 Runtime client token 洩漏 | 原子移除其 entry／更換 digest；安全更新該 Runtime secret、排空重啟，驗證新 token 可用、舊 token 被拒 |
| 共用 gateway bearer 洩漏 | 盤點所有持有者並安排切換；`omp auth-gateway token --regenerate` 會產生並輸出新秘密，僅在受控終端／secret capture 流程執行；安全分發給仍需共用 bearer 的 client，再重啟 gateway 使新值生效 |
| Broker bearer 洩漏 | 盤點所有 broker clients；受控執行 `omp auth-broker token --regenerate`，更新各 client 的憑證來源並協調 broker／gateway 重啟；不把新 token 當作 Runtime API key |
| OAuth 授權永久失效 | 在 broker host、相同使用者/profile 重新執行登入流程；再驗證帳號健康與 catalog，必要時另行授權模型測試 |
| 模型 401／403／429 | 分別檢查 gateway bearer、client allowlist、provider 認證與額度；不要自動輪換 token、增加重試或切換未授權帳號 |

靜態 token 輪換不是無中斷保證；server 可能仍保有舊 allowlist，改磁碟檔不等於已撤銷。輪換需驗證舊 token 被拒，且新 token 能存取預期範圍。停用共享 OAuth 帳號可能影響其他 client，需先確認影響範圍。

## 7. 驗證與版本判讀

按 [驗證指南](../.agents/skills/deploying-with-omp-gateway/references/verification.md) 分別記錄：

- Gateway／broker health 與實際 executable 版本、checksum；健康不代表工具轉換相容。
- 認證後的模型 catalog；帳號檢查不等於真實 completion 已成功。
- 經授權的模型 request／usage；欄位存在不等於正命中 cache，也不是計費稽核。
- Runtime 正在使用的 release JAR、revision／checksum、程序啟動時間與 health。`git pull`／`git push` 不會更新執行中的 JAR。
- 正式 Slack／MCP 驗收與本地 fake-backed 測試分開。不得只用 health 宣稱端到端成功。

比較版本時先 fetch 遠端，確認 `origin/main`，再對照實際 service 的 JAR release、部署紀錄與 checksum；若曾在程序啟動後替換 JAR，僅讀檔案路徑不足以證明正在執行的版本。升級需另依 [備份與更新流程](../.agents/skills/deploying-with-omp-gateway/references/operations.md#更新-runtime不更新-omp)，不要為了版本檢查重啟或套用 migration。
