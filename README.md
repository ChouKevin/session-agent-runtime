# Standalone Session Agent Runtime

Session Agent Runtime owns the conversation, model/tool loop, feedback, and PostgreSQL history. It is built and deployed independently from Semantic.

Semantic owns repository discovery, exact Git revisions, code analysis, and Mongo index data. Session Runtime does not clone or mount Git repositories, start JDT/JDT LS, contain business fixture source, or read Semantic MongoDB directly. It calls only the Semantic Query API.

## Repository and revision selection

All Semantic tools are visible to the model from its first response. When history does not already contain a reliable repository pair, the model can call `list_repositories`. The catalog is reference data and does not include a repository/revision pair. The model copies an exact `repositoryId` and its paired `revision` into later source tools; Runtime receives and forwards both values without selecting a repository or replacing identifiers.

Semantic validates the pair. If a source tool returns `REVISION_OUTDATED`, Runtime appends non-terminal feedback containing the rejected arguments, requested revision, current revision, and retry guidance. Runtime does not retry the call. The model reads that feedback and decides whether to call the useful tool again with `currentRevision`. The same persisted session history remains intact; earlier messages and results are not rewritten.

## Results and replies

Every successful tool execution stores a `resultId`, tool identity, canonical arguments, result JSON, and repository/revision when the tool is source-backed. Catalog results omit repository/revision. Tool results remain visible in session history and can be fetched from `/internal/results/{resultId}`.

`PLAN` calls may use zero or more sequential tools. `FINAL_REPLY` exposes no tools and stores the model's primary assistant content as an opaque string. Runtime does not parse or constrain prose, Markdown, JSON, code, or another textual format requested by the user. Tool history is inspectable execution history; Runtime does not validate whether a stored result supports a later claim.

Runtime keeps code and runtime knowledge separate. When a value can only come from a database, configuration, secret, user input, or external API, the model must report that the current value is unavailable instead of inventing it. An empty code search supports only a codebase-limited conclusion.

## Model calls

`PLAN` sees the available tools and returns either a tool call or `AnswerReady`. `FINAL_REPLY` hides tools and makes one explicitly reserved call. The twelfth call is the final fallback.

## Diagnostics

`model_call_record` is diagnostic data, not session history. It currently retains raw Spring AI-level prompt and completion data without redaction or TTL.

```bash
docker compose exec -T postgres psql -U session_agent -d session_agent \
  -c "select message_job_id, runtime_call_ordinal, provider_attempt, phase, outcome, raw_completion from model_call_record order by started_at;"
```

Recreate disposable databases after the current `V1` schema change.

## Dependencies

Runtime calls the external Semantic Service over HTTP. The internal Semantic Tool Adapter depends on Tool contracts; Tool has no Semantic dependency.

## Conversation HTTP API

Submit a message, poll its job, then read history with the returned UUID values:

```text
POST /internal/messages
GET  /internal/message-jobs/{messageJobId}
GET  /internal/sessions/{sessionId}/messages
GET  /internal/results/{resultId}
```

`POST /internal/messages` accepts `sessionKey`, `participantId`, `sourceMessageId`, and `message`. Reusing a nonblank `sessionKey` continues the same conversation.

The session-history response exposes sequence/time, role, job/participant IDs, user or assistant text, tool result ID/name/version, repository/revision, and feedback code/terminal/rejected arguments. It does not inline tool result JSON or model-only context. Fetching `/internal/results/{resultId}` is a separate trusted internal operation and returns canonical arguments plus stored result JSON; do not expose these internal endpoints to an untrusted network without a security design.

## Offline checks

```bash
bash src/test/shell/docker-contract-test.sh
docker compose -f docker/compose.yaml config --quiet
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
```

The Compose API is bound only to `127.0.0.1`. Docker owns the project-scoped PostgreSQL volume and uses bounded `json-file` logs; there is no host log mount.

## Live acceptance

Live acceptance uses an already-running Semantic Query service that publishes payment, order, and video UAT repositories. Semantic owns and validates those sources. A Google GenAI key is also required.

```bash
export SEMANTIC_BASE_URL='<existing Semantic Query URL>'
export SEMANTIC_API_TOKEN='<query token>'
export GOOGLE_API_KEY='<Google GenAI key>'
export GOOGLE_GENAI_MODEL='gemini-3.1-flash-lite'
export SESSION_AGENT_POSTGRES_PASSWORD='<local PostgreSQL password>'
SESSION_AGENT_LIVE=true bash live-test.sh
```

`live-test.sh` creates a unique Compose project, requests an ephemeral loopback port, runs the opt-in HTTP acceptance test, and removes only that project and its disposable PostgreSQL volume. It never starts, stops, resets, or cleans Semantic.

The live report under `target/live-reports/` contains safe metadata only: session/job IDs, configured model, tool order, repository/revision pairs, outcome, and available Spring AI usage. It excludes questions, prompts, raw tool results, HTTP bodies, model context, and credentials.

The deployment contract preserves blank `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`, and `SLACK_BOT_USER_ID` inputs for a future transport. No Slack integration is implemented.
