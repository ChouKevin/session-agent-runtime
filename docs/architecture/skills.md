# Skills: discovery, reads and receipts

[Question index](../README.md) | [Architecture overview](../package-architecture.md) | [繁體中文](skills.zh-TW.md)

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

Owns startup discovery, bounded reads, durable selection, receipt/admission and notification recovery. It does not execute skill scripts, validate model compliance, or grant business-report quality guarantees; configuration keys belong to the configuration reference.

<a id="contents"></a>

## Contents

- [Discovery and current-file reads](#discovery-and-current-file-reads)
- [Filesystem security boundary](#filesystem-security-boundary)
- [Selection and recovery](#selection-and-recovery)
- [Receipts and durable notifications](#receipts-and-durable-notifications)

<a id="discovery-and-current-file-reads"></a>

## Discovery and current-file reads

The library is configured explicitly through `session-agent.skills.roots` (empty
by default), `max-file-bytes` (65536), and `max-skills` (128). Bootstrap scans
direct child `SKILL.md` frontmatter once; normal request tool descriptions contain
that metadata, never preloaded bodies. Each read reopens bounded current UTF-8
bytes using no-follow directory handles; the returned hash records evidence, not
a version lock. `/skill:name` on a newly submitted source message rereads, while
source replay and recovery after a committed selection reuse durable history.
Summarization has no tool definitions, and neither automatic TOOL bodies nor
explicit SKILL bodies are pinned or restored after ordinary compaction.

<a id="filesystem-security-boundary"></a>

## Filesystem security boundary

Enabled roots require a Java filesystem provider supporting
`SecureDirectoryStream`; unsupported providers fail startup closed. The intended
environment is Linux with a supporting local filesystem provider, not a guarantee
for every Linux filesystem or mount. The read limit bounds returned bytes, not
file-open latency: regular-file checks and no-follow opens do not provide a
timeout against a privileged concurrent writer replacing an entry with a FIFO or
another special file. Roots must remain trusted operator-managed content, not a
hostile-writer boundary.

Only operator-managed shared roots belong at this boundary. Service accounts and
container mounts should be read-only, including references; all Runtime users can
request those files. There is no shell execution, automatic home/repository skill
discovery, or implicit import of this repository's maintenance/deployment skills.
The [skill definition](../configuration.md#skill-definition) and
[selection/replay reference](../configuration.md#skill-lifecycle) define the on-disk format and command; the [host installation runbook](../../.agents/skills/deploying-with-omp-gateway/references/configuration.md#runtime-本地-skills選用預設停用) owns installation and application.

<a id="selection-and-recovery"></a>

## Selection and recovery

`JobProcessor` resolves only the claim's PENDING selection before responses or
summaries and reloads its committed context. ACCEPTED selections reuse stored
bytes. Resolution shares the ordinary append uncertainty tracker: an ambiguous
write stops the attempt without resubmission or a replacement failure batch.

<a id="receipts-and-durable-notifications"></a>

## Receipts and durable notifications

`SkillLoadObserver` and `SkillReadReceipt` belong to the neutral tool port.
Filesystem results remain in the skill adapter; storage finalizes attempts in the
same transaction as their SKILL/TOOL history. Slack maps resource kinds into its own
presentation type, avoiding a skill/tool module cycle or Slack receipt dependency.
Pending outcomes are ordered ahead of generic progress and acknowledged by exact
presentation/revision, not merely by job. A reference success is not a definition
reload, failed/interrupted reads never become loaded, and an acknowledged outcome
disappears on subsequent ordinary progress. WAITING_USER is terminal for that job's
status, not a delivered report. None of these operational records become history.
Loaded notices certify committed content only. Runtime neither validates compliance
with prompt skills nor guarantees business-report provenance, completeness or field
accuracy; model-output quality requires separate evidence review.
