# Documentation question index

[Home](../README.md) | [繁體中文](README.zh-TW.md)

## Purpose and scope

Choose a question below to go directly to its owning section. This index owns navigation, not a second copy of configuration, execution contracts or deployment procedures. User guides explain observable behavior; references define contracts; runbooks own operational steps. Existing runbooks retain their original language, with no implied English translation.

## Contents

- [Using Runtime](#using-runtime)
- [Configuring and operating](#configuring-and-operating)
- [Developing and understanding internals](#developing-and-understanding-internals)

## Using Runtime

For first use: [submit a message over HTTP](http-api.md#message-submission), read the [conversation loop](conversations.md#conversation-loop), or [start a Slack conversation](slack.md#start-or-continue-a-conversation). The [HTTP reference](http-api.md#jobs-cancellation-and-history) owns public job/history/delivery fields and [cache-report arithmetic](http-api.md#cache-report); the [Slack guide](slack.md#answers-cache-footer-and-files) owns user-visible answers and files.

| Question | Direct owner section |
| --- | --- |
| 5. When can reports be generated, and how are encoding, limits and release defined? | [Artifacts: route and creation result](architecture/artifacts.md#route-and-creation-result); [encoding](architecture/artifacts.md#encoding-and-download-boundaries); [limits and release](architecture/artifacts.md#limits-identity-and-release) |
| 6. What guarantees govern Slack intake, per-question progress and delivery? | [Slack delivery: admission and stop transactions](architecture/slack-delivery.md#admission-and-stop-transactions); [progress ownership](architecture/slack-delivery.md#progress-ownership-and-reconciliation); [prepared delivery and upload](architecture/slack-delivery.md#prepared-deliveries-and-upload-transport) |

## Configuring and operating

| Question | Direct owner section |
| --- | --- |
| 8. What do configuration keys and choices mean? | [Configuration: model keys and choices](configuration.md#model-configuration); [Runtime/gateway boundaries](configuration.md#runtime-boundaries); [skills](configuration.md#runtime-local-skills); [MCP connections and timing scopes](configuration.md#mcp-connections) |
| 9. For an existing service, which configuration applies, and how do I apply and verify a change? | [Deployment configuration runbook: configuration source and application](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#設定來源與套用) |

For new hosts, start with [deployment modes and credentials](gateway-deployment.en.md#1-choose-a-deployment-mode). For existing services use [host operations](operations.md#recommended-systemd-host-deployment). MCP private injection, disabling, rotation and acceptance belong to the [MCP operation sequence](../.agents/skills/deploying-with-omp-gateway/references/mcp.md#邊界與操作順序), not the generic key reference. Gateway build compatibility/provenance belongs to the [deployment configuration runbook](../.agents/skills/deploying-with-omp-gateway/references/configuration.md#model-adapter-and-gateway-boundaries).

## Developing and understanding internals

| Question | Direct owner section |
| --- | --- |
| 1. Which modules own each responsibility, and which dependencies are allowed? | [Package architecture: modules and boundaries](package-architecture.md#modules-and-boundaries); [named interfaces and dependency ceiling](package-architecture.md#named-interfaces-and-dependency-ceiling) |
| 2. How do message execution, allowance, handoff, cancellation and retries work? | [Conversation execution: ordered model and tool loop](architecture/conversation-execution.md#ordered-model-and-tool-loop); [allowance and wrap-up](architecture/conversation-execution.md#allowance-and-wrap-up); [retry and recovery](architecture/conversation-execution.md#retries-and-atomic-recovery) |
| 3. How do audit history, model context, compaction and usage differ? | [History and usage: audit and context admission](architecture/history-and-usage.md#audit-and-context-admission); [checkpoints and compaction](architecture/history-and-usage.md#checkpoint-coverage-and-compaction); [durable cache accounting](architecture/history-and-usage.md#durable-cache-accounting) |
| 4. How are skills discovered, read, committed and recovered? | [Skills: discovery and current-file reads](architecture/skills.md#discovery-and-current-file-reads); [selection and recovery](architecture/skills.md#selection-and-recovery); [receipts and durable notifications](architecture/skills.md#receipts-and-durable-notifications) |
| 7. Which tests own evidence for the module I changed? | [Development: test responsibilities and locations](development.md#test-responsibilities-and-locations); [integration/live evidence boundaries](development.md#integration-and-live-evidence-boundaries) |

The [development workflow](development.md#toolchain-and-ordinary-tests) owns commands and authorization, not the architecture overview. Full-suite and live acceptance still require explicit current-task authorization. [Repository guidance](../AGENTS.md) defines engineering boundaries.
