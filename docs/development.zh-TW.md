# 開發與驗證

[首頁](../README.zh-TW.md) | [English](development.md) | [問題索引](README.zh-TW.md) | [架構](package-architecture.zh-TW.md)

本指南是 Java/Maven 測試流程、測試職責與證據邊界的唯一完整 owner；不負責架構執行契約或部署操作。儲存庫特定的服務與環境指引請參考 [AGENTS.md](../AGENTS.md)。

<a id="purpose-and-scope"></a>

## 目的與非目的

本頁負責工具鏈、測試命令與授權、測試職責與 evidence boundaries；不負責產品執行契約或部署程序。

<a id="contents"></a>

## 目錄

- [工具鏈與一般測試](#工具鏈與一般測試)
- [PostgreSQL 整合測試](#postgresql-整合測試)
- [完整測試套件命令與授權](#完整測試套件命令與授權)
- [測試職責與位置](#test-responsibilities-and-locations)
- [整合與 live 證據邊界](#integration-and-live-evidence-boundaries)

## 工具鏈與一般測試

專案以 Java 21 為目標並使用 Maven。一般 Surefire 測試套件使用假的 model 與 tool 邊界；不需要 PostgreSQL、實際 MCP server 或 model key。測試的本機 HTTP 暴露範圍僅限 loopback。`SlackSdkWebApiTest` 會在 loopback address 與 ephemeral port 啟動假的 JDK HTTP server，不會連線到 Slack。使用 Mockito static/final mocking 的測試需要一般 Java 21 process，讓 Mockito/Byte Buddy 能自行 attach。

如要執行範圍限定的一般測試，使用 Surefire 的 `-Dtest` property 選取現有測試類別。以下名稱列於[測試職責與位置](#test-responsibilities-and-locations)：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress \
  -Dtest=MessageJobContextCompactionTest,ApplicationModulesTest \
  test
```

## PostgreSQL 整合測試

在本機共用 PostgreSQL 環境執行時，使用儲存庫內的 launcher：

```bash
bash scripts/test-postgres-local.sh
```

Launcher 使用私人測試設定選取 loopback port `5432` 上受限制的測試角色；必須明確設定 `shared` mode，並使用 Java 21。每個整合測試類別會建立唯一資料庫，清理範圍僅限其擁有的測試資料庫。設定缺少或無效時會直接失敗，不會 fallback 至 Testcontainers；應用程式資料庫與共用 PostgreSQL service 都不是測試資源。Launcher 不接受位置參數，且一律執行 `mvn --batch-mode --no-transfer-progress -Ppostgres-it verify`，包含完整一般測試套件及所有相符的 PostgreSQL 整合測試。它不提供 PostgreSQL 測試的範圍選取功能。

私人設定包含明確信任的 shell-safe assignments。不要顯示或 source 該檔案，也不要以 shell tracing 執行 launcher。資料庫 provisioning、檔案與角色需求、安全邊界及獨立的可攜模式，請依照[驗證指南的本機 PostgreSQL 章節](../.agents/skills/deploying-with-omp-gateway/references/verification.md#本機-postgresql-測試與可攜-ci)操作。

私人測試 env 位於 `~/.local/share/local-postgres/session-agent-tests.env`，必須由目前使用者擁有且 mode 為 600。檢查 migrations、persistence、transaction boundaries、history ordering 或 concurrency 時使用此 profile；仍須符合下方完整測試授權。已授權的 clean gate 先執行 Maven `clean`，再執行上方 launcher。

## 完整測試套件命令與授權

以下命令會執行完整測試套件，不是範圍限定的檢查。Coding agents MUST 只有在目前任務明確授權執行完整測試套件時才執行。一般命令會執行完整的 fake-backed 一般測試套件：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

本機共用 PostgreSQL gate 使用上方的 launcher。它會執行完整一般測試套件及 PostgreSQL 整合測試套件；不要以未設定的 Maven profile 命令取代它。

只有在明確選擇可攜 CI 環境時，才應選用 Testcontainers。此模式需要 Docker，並會刻意移除繼承而來的共用資料庫憑證：

```bash
env -u SESSION_AGENT_TEST_POSTGRES_ADMIN_URL \
  -u SESSION_AGENT_TEST_POSTGRES_USERNAME -u SESSION_AGENT_TEST_POSTGRES_PASSWORD \
  SESSION_AGENT_TEST_POSTGRES_MODE=container \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

此可攜命令同樣會執行完整一般測試套件及所有相符的 PostgreSQL 整合測試。Testcontainers 不是本機預設模式；`mvn test` 仍使用假的邊界，不需要資料庫。

真實模型、deployed MCP 與 real Slack 的驗證須與使用假的邊界之測試套件分開，並需要明確授權。`GoogleModelLiveTest` 由 `GOOGLE_MODEL_LIVE=true` gate 控制；未取得授權前不要啟用這個 live model call。Deployed live acceptance 不在此儲存庫範圍內。

<a id="test-responsibilities-and-locations"></a>

## 測試職責與位置

測試依其要證明的行為邊界組織。測試 package tree 並非正式程式碼 package tree 的逐層鏡像：需要存取 package-private SDK seam 的測試會留在該 seam 所在處，即使 assertions 涵蓋跨層行為亦然。

| 邊界 | 目前的測試組織 |
| --- | --- |
| Domain invariants 與 provider-neutral records | `ConversationDomainTest`、`ContextUsageEstimatorTest`、`GenericToolSnapshotTest` 與 `slack/domain/SlackPermalinkParserTest`；這些測試不需要 Spring、PostgreSQL、MCP server 或 model key。 |
| 使用本機 fakes 的 conversation application 行為 | `MessageServiceTest`、`SessionQueryServiceTest`、`MessageJobContextCompactionTest`、`MessageJobToolExecutionTest`、`MessageJobRecoveryTest`、`MessageJobHandoffTest`、`MessageJobSkillsTest` 與共用的 `MessageJobTestFixtures`；涵蓋 history admission、依 purpose 的 reservations、有界限的 handoff、semantic modes、有序 tools、skill receipt propagation、continuation、claim fencing 與 recovery。 |
| Skill filesystem 與工具邊界 | `skill/FileSystemSkillLibraryTest` 與 `SkillToolCatalogTest`；啟動 metadata、目前檔案／reference 讀取、UTF-8 與大小限制、URI／symlink 安全、內容雜湊與讀取 receipts。 |
| Model adapter 行為 | `model/adapter/springai/HistoryProjectorTest`、`SpringAiConversationModelTest`、`SpringAiToolCallbackFactoryTest`、`SpringAiMessagePartsHandlerTest`、`GoogleGenAiContinuationTest` 與明確 gated 的 `GoogleModelLiveTest`。有序 continuation tests 保留 reasoning、各 part signatures、unknown provider parts 與 media；本機 fake-HTTP provider tests 會在不消耗 model quota 下測試 replay。 |
| MCP adapter 行為 | `mcp/adapter/sdk/McpConnectionClientFactoryTest`、`McpConnectionManagerTest`、`McpToolCatalogTest`、`McpToolResultMapperTest` 與 `McpConnectionsEndpointTest`。Endpoint test 留在此 package，因為它會測試 SDK adapter 的 private seam。 |
| PostgreSQL 與 HTTP adapters | `storage/adapter/postgres/PostgresConversationCommitPostgresIT`、`PostgresMessageIntakePostgresIT` 與 `PostgresMessageJobPostgresIT`；`web/adapter/http/MessageControllerTest`；以及下方描述的 Slack persistence tests。 |
| Durable generated artifacts | `artifact/ArtifactEncoderTest`、`ArtifactToolCatalogTest` 與 `storage/adapter/postgres/ArtifactStorePostgresIT`；涵蓋 bounds、CSV safety、trusted invocation identity、route eligibility、exact actual-observation association、PostgreSQL rollback/lease/restart/replay/isolation，以及 orphan invisibility。 |
| Worker 行為 | `worker/application/MessageJobWorkerTest`，涵蓋 claim、renewal、processing、retry、limit 與 recovery coordination。 |
| Slack application 與 adapters | `slack/application/MessageHandlerTest`、`SlackDeliveryLifecycleTest`、`SlackDeliveryWorkerTest`、`SlackProgressWorkerTest`、`SlackProgressLifecycleTest` 與 `SlackSocketLifecycleTest`；`slack/adapter/postgres/SlackDeliveryPostgresIT` 與 `SlackProgressPostgresIT`；以及 `slack/adapter/sdk` 中的 SDK-seam tests，包括 `SlackConfigurationTest`、`PostgresMessageSubmissionPostgresIT`、`SlackSocketShutdownTest`、`SlackBoltSocketClientTest` 與 `SlackSdkWebApiTest`（以 local fake HTTP 呼叫 real SDK，包括 question permalink identity 與 update errors）。 |
| Bootstrap configuration 與 metrics | `bootstrap/configuration/RuntimeConfigurationTest`、`ApplicationStartupTest`、`RuntimePropertiesTest` 與 `LoggingConfigurationTest`；`bootstrap/adapter/metrics/RuntimeObservabilityTest`。 |
| Architecture 與 dependency boundaries | `ApplicationModulesTest` 驗證 11 個封閉模組、exact allowed dependency map 與完整 named-interface map。`RuntimeBoundaryTest` 驗證 provider boundaries、MCP 與 Slack SDK 僅限於各自 SDK adapters，以及 conversation 與 Slack 的 domain/port dependency direction rules。 |

單獨驗證 skill 讀取：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress \
  -Dtest=FileSystemSkillLibraryTest,SkillToolCatalogTest,MessageJobSkillsTest \
  test
```

這些測試在 JUnit `@TempDir` 建立通用的 `demo`／`review` skills 與 references，不依賴已安裝的業務 skill。Conversation cases 使用假的 model／store 邊界，確認載入內容進入模型請求，以及新讀取、已提交內容復原與工具讀取失敗的處理。不需要資料庫、live provider 或 model key；不證明真實模型會自主選用 skill，也不驗收業務結果品質。

<a id="integration-and-live-evidence-boundaries"></a>

## 整合與 live 證據邊界

整合驗收也需要另行啟動真實 Runtime，並使用隔離 PostgreSQL、實際設定的 model 與 Slack SDK adapters（兩者都指向 local fake HTTP services），再由既有 Slack adapter 注入 synthetic intake。擷取 blocked-model refresh、multipart delivery、independent progress errors、cancellation 以及 restart/history counts；私人 evidence 留在 Git 外，並移除暫存 launchers。這只能證明本機 transport integration，不證明真實 Slack inbound 或 native client UI。

Continuation/skill acceptance 另外涵蓋 cap-discard/one no-tools wrap、restart 後持久化的 waiting、全部四種 follow-up decisions、restricted tools、artifact ownership/release、已提交的 definition/reference/failure notices，以及 history 中沒有 progress noise 的 exact acknowledgement。本機 deterministic boundary responses 能證明 integration，不能證明 model semantic quality。經授權的 real-model samples 必須另外擷取實際 response model identity、real read-only MCP、已儲存的 skill bytes/hash、Slack API updates 與已下載 artifact hashes。維持 production model selection 及其 allowance 不變；隔離的 test profile 必須在有效的 skill-only confirmation 前先選取。Synthetic ingress 與 authenticated browser/human Socket evidence 是不同的 acceptance boundaries。業務 skill 的資訊涵蓋、結果品質與內容修訂由外部評估負責，不屬於 Runtime regression suite。
