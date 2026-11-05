# Durable Markdown and CSV artifacts

[Question index](../README.md) | [Architecture overview](../package-architecture.md) | [繁體中文](artifacts.zh-TW.md)

<a id="purpose-and-scope"></a>

## Purpose and non-purpose

Owns artifact route eligibility, encoding, bounds, identity, association and release. It does not upload files or own Slack delivery, public download endpoints, or test execution.

<a id="contents"></a>

## Contents

- [Route and creation result](#route-and-creation-result)
- [Encoding and download boundaries](#encoding-and-download-boundaries)
- [Limits, identity and release](#limits-identity-and-release)
- [Trusted invocation and association](#trusted-invocation-and-association)

<a id="route-and-creation-result"></a>

## Route and creation result

On normal jobs with a configured supported attachment route, `createMarkdownReport(title, content)`
and `createCsv(title, columns, rows)` save durable PostgreSQL artifacts. They do not upload files or
claim delivery; their result is `SAVED_PENDING_DELIVERY`. The first route adapter recognizes
accepted Slack jobs with an original persisted thread binding while Slack is configured.
HTTP jobs without that route do not expose the creation tools. Compaction, intent classification, and boundary wrap-up requests have no tools.

<a id="encoding-and-download-boundaries"></a>

## Encoding and download boundaries

Markdown bytes are exact UTF-8, without parsing. CSV accepts string headers and rectangular string
rows, writes a UTF-8 BOM, CRLF, quotes every field, and doubles embedded quotes. Cells whose first
non-whitespace character is `=`, `+`, `-`, or `@`, or which begin with tab/CR/LF, receive an apostrophe
prefix. Results disclose this spreadsheet-safe transformation; it is not a universal guarantee for
all import behaviors. Original input remains in the tool-call audit.
Runtime storage and the bytes passed to the Slack upload adapter are distinct from
Slack-hosted downloads: Slack may omit a CSV's UTF-8 BOM when serving a download.
Record raw hashes and lengths at each boundary; equivalent parsed CSV is not proof
of byte-identical download preservation.

<a id="limits-identity-and-release"></a>

## Limits, identity and release

Limits are 1 MiB serialized bytes per generated file, 10 files / 5 MiB per job, 100 CSV columns,
10,000 CSV data rows, and 120 Unicode code points per title. Filenames are immutable UUIDs with
`.md` or `.csv`; titles never select paths. Stable invocation replay returns the same artifact;
changed input for that identity is rejected. Only artifacts tied to their exact successful
tool observation in the atomic assistant/tool batch can be released. Release is triggered by
committed successful assistant completion with an explicit `ANSWER` disposition, before
Slack confirms delivery: it means eligible for delivery, not transport success.
Waiting questions and clarifications never release artifacts.
Paused-job artifacts stay private; a later report-only job creates its own artifacts from retained
evidence and can release only those owned by that job. Staged orphans and cancelled/failed jobs remain unpublishable.
Artifacts inherit conversation retention; this feature adds neither public UUID downloads nor
automatic deletion. Attachment upload/delivery is a separate adapter responsibility.

<a id="trusted-invocation-and-association"></a>

## Trusted invocation and association

Tool executors receive a trusted `ToolInvocation`: the four-field stable
`ToolExecutionContext` plus separate worker/claim-number lease authority. Only Runtime
constructs production invocations, after counting the actual model request. Catalog
snapshots may receive job/session identifiers for route eligibility without depending
on conversation types. MCP ignores invocation metadata; first-party skill reads use
its fenced claim and counted request identity to start durable operational attempts.
Artifact and skill receipts are internal tool-port metadata, not model-visible JSON.
PostgreSQL compares the successful artifact output, original arguments, invocation
ordinal, tool identity and job ownership while associating the actual TOOL event.
Release requires an ANSWER disposition and belongs only to that completed job;
WAITING/clarification never release artifacts or transfer prior-job ownership.
Encoding, file bounds, and release semantics are specified in the
[artifact contract](artifacts.md#limits-identity-and-release).
Creation has no Slack side effect and no model-supplied channel/path/capability.

For attachment transport, see [Slack delivery](slack-delivery.md#prepared-deliveries-and-upload-transport).
