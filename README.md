# Session Agent Runtime

Session Agent Runtime is a standalone, provider-neutral conversation service. It owns durable conversation history, conversation-job ordering, model calls, tool execution, and PostgreSQL storage. Semantic remains a separate HTTP service that owns repository discovery, repository and revision validation, and code analysis.

## Conversation loop

One submitted user message starts one **conversation turn**. A turn can make one or more **model calls**. A model call either returns direct assistant text or requests one or more tools. Tool execution is not a model call.

```text
load complete ordered session history and available tools
                         |
                         v
                    call model
              +----------+----------+
              |                     |
           direct text          tool requests
              |                     |
     append assistant text   run requests in order
     complete the job        append one ordered batch
                                    |
                                    v
                             call model again
```

The model may request multiple tools in a response. They run sequentially in the model-provided order. Their observations become visible only on the next model call, so calls in the same batch must be independent. A call that needs an earlier result must wait for a later model response. One ordinary tool failure is recorded as that tool's observation and does not prevent later requests in the batch.

Every model call receives all committed messages for the session in sequence, including messages from completed earlier turns. User messages retain their participant ID, so the history preserves speaker identity. Assistant text and tool input/output are opaque runtime data: the service does not impose a response format or interpret a tool's payload.

The runtime exposes the registered tools to the model but does not choose repositories. When a Semantic tool requires `repositoryId`, the model supplies it, using available repository information when needed. Semantic validates `repositoryId` and revision values; values returned by Semantic remain inside that tool's opaque output.

## Durable history and HTTP API

Submit a message, poll its job, then read the complete session history:

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
GET  /internal/sessions/{sessionId}/messages
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same session. The accepted response contains `sessionId` and `messageJobId`. The job response contains its IDs, `status`, `retryCount`, and `modelCallCount`.

The history endpoint returns ordered records with shared `sequence`, `createdAt`, `messageJobId`, and `type` fields. Its four response types are:

| Type | Type-specific fields |
| --- | --- |
| `USER` | `participantId`, `message` |
| `ASSISTANT` | `message` |
| `TOOL` | `observationId`, `toolName`, `input`, `output` |
| `RUNTIME` | `code`, `message` |

Tool observations are part of history and contain their own opaque input and output. There is no separate result-lookup endpoint.

## Limits, retries, and recovery

`session-agent.model.max-model-calls-per-message` controls the maximum provider requests for one user message. Its environment form is `SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE`; the default is 12. The count is reserved immediately before each real provider request, including failed attempts. On the final allowed call, a direct answer completes normally; tool requests are not run and the runtime appends `MODEL_CALL_LIMIT_REACHED` instead of making another forced call.

Spring AI retries are disabled with `spring.ai.retry.max-attempts=1`. Retrying transient provider failures is owned by the runtime's conversation-job loop, which preserves the already-counted model call and avoids duplicate history.

The runtime does not persist provider request or response payloads, provider metadata, or model diagnostics. It emits content-free operational telemetry for model-call outcome, latency, token usage when available, error category, and job/session correlation.

Tool execution has no runtime deduplication layer. A read-only tool can run again if the process crashes after execution and before its observation batch commits. Any future side-effecting tool must provide idempotency at its adapter or external-service boundary.

## Database and local runtime

The shipped schema is a fresh V1 schema. Reset a disposable PostgreSQL database before starting this version; do not reuse a database from an earlier schema. The supplied live runner creates a unique Compose project and removes only that project's services and PostgreSQL volume on exit.

The Compose service binds the runtime HTTP port to loopback only. Its required credentials are intentionally not committed. For a syntax-only Compose check, provide contract-only nonsecret values:

```bash
SEMANTIC_API_TOKEN='contract-semantic-token' \
SESSION_AGENT_POSTGRES_PASSWORD='contract-only-password' \
docker compose -f docker/compose.yaml config --quiet
```

## Verification

Default tests use fakes and make no provider calls:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress test
```

PostgreSQL integration tests verify the fresh schema, atomic ordered history batches, complete-history loading, and persistence contracts:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

The shell contracts validate repository boundaries plus Compose, live-runner, and credential-handling contracts:

```bash
bash src/test/shell/docker-contract-test.sh
bash src/test/shell/repository-contract-test.sh
```

The minimal provider smoke test is opt-in and makes one user-only model call. It requires a configured Google API key and model:

```bash
GOOGLE_MODEL_LIVE=true mvn -Dtest=GoogleModelLiveTest test
```

The full-system opt-in test starts a fresh, uniquely named local Compose project, waits for runtime health, then verifies Semantic tools, observations, final text, and the history API. It requires a running Semantic Query service plus its token, a Google API key, model name, and a local disposable PostgreSQL password:

```bash
export SEMANTIC_BASE_URL='<Semantic Query URL>'
export SEMANTIC_API_TOKEN='<Semantic Query token>'
export GOOGLE_API_KEY='<Google API key>'
export GOOGLE_GENAI_MODEL='gemini-3.1-flash-lite'
export SESSION_AGENT_POSTGRES_PASSWORD='<local disposable PostgreSQL password>'
SESSION_AGENT_LIVE=true bash live-test.sh
```

The live report under `target/live-reports/` contains safe operational metadata only. It excludes user questions, prompts, raw tool outputs, HTTP bodies, provider payloads, and credentials.
