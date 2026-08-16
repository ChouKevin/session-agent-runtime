# Standalone Session Agent Runtime

This is an independently built local runtime. It has its own PostgreSQL database,
schema, Docker Compose project, and HTTP API; it does not build, start, or depend
on the existing agent runtime.

## Offline checks

```bash
bash src/test/shell/docker-contract-test.sh
docker compose -f docker/compose.yaml config --quiet
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

The Compose API is bound only to `127.0.0.1`. Docker owns its project-scoped
PostgreSQL volume and uses bounded `json-file` logs, so no host log directory is
mounted.

## Live acceptance

Live acceptance uses an already-running Semantic Service configured with the
`payment-service` and `order-service` fixtures. It also requires a Google GenAI
key in the calling environment. Neither service nor its data is managed by this
project.

Set these environment inputs in your shell (the empty-value template is at
[`docker/.env.example`](docker/.env.example)):

```bash
export SEMANTIC_BASE_URL='<existing Semantic Service URL>'
export SEMANTIC_API_TOKEN='<when required by that service>'
export GOOGLE_API_KEY='<Google GenAI key>'
export GOOGLE_GENAI_MODEL='gemini-3.1-flash-lite'
export SESSION_AGENT_POSTGRES_PASSWORD='<local PostgreSQL password>'
SESSION_AGENT_LIVE=true bash live-test.sh
```

The deployment contract also preserves the empty `SLACK_APP_TOKEN`,
`SLACK_BOT_TOKEN`, and `SLACK_BOT_USER_ID` inputs for a future transport
integration. The current Runtime does not bind or use them.

`live-test.sh` creates a unique Docker Compose project, requests an ephemeral
loopback port, runs the opt-in HTTP acceptance test, and removes only that
project and its disposable database volume through its exit trap. It never
starts, stops, or cleans the externally managed Semantic Service.

`SESSION_AGENT_POSTGRES_PASSWORD` is required for every Compose and live-test
operation. Set it in the calling shell (or through Docker Compose's environment
loading); the scripts never supply or print a fallback value.

The live report is written under `target/live-reports/`. It contains only safe
metadata: IDs, configured model, tool order, repository/revision pairs,
citations, outcome, and available Spring AI usage. It excludes questions,
prompts, raw tool results, HTTP responses, and credentials.
