---
name: inventory-java-mq
description: Use when a request concerns Java MQ/message-queue usage, RabbitMQ, Kafka, SQS or JMS consumers, consumer settings, or a cross-repository CSV inventory.
---

# Java MQ inventory

## Overview

Build a source-evidenced consumer inventory. Repository coverage comes before configuration depth; static source describes configuration, not deployed behavior.

## Recipe

1. **Classify first.** Discover authorized repositories through all pages. Pin each investigated repository independently to its full source revision; retain returned context metadata. Classify every candidate using descriptors, inventory and targeted searches as Java/MQ candidate, non-Java/excluded, or unresolved before deep consumer/config reads. Filenames/dependencies are leads, not active-consumer proof. Keep producer-only evidence distinct.
2. **Set scope.** Default to common/static configuration, one row per consumer/destination. If the user explicitly requests environment comparison or a named environment's settings, inspect that scope and distinguish overrides. Otherwise profiles remain outside scope; unknown external deployment values do not block complete static inventory. Add version, retry or DLQ detail only when requested.
3. **Fill fields.** Locate listener declarations and configuration keys for the columns below. Read precise annotation, method and configuration-block ranges; follow referenced constants, properties and factories needed for those fields. Follow tool descriptions for range/pagination support. Batch independent reads; resolve dependent references afterward. Finish truncated deciding bodies. Cite read source, not snippets.
4. **Resolve coverage.** Empty case-sensitive literal search means no match for that spelling/scope. Check relevant API/annotation spellings and configuration/module candidates; retain unresolved coverage rather than asserting absence. Preserve consumer/factory identities; connect aliases through read mapping/delegation evidence. Distinguish explicit values, source defaults, unresolved placeholders and external injection; framework defaults need evidence.
5. **Deliver deliberately.** First resolve uninspected requested fields through available targeted in-scope reads. `COMPLETE` requires fields to be evidence-backed, evidenced N/A, or genuinely unavailable after inspection. Keep unavailable external values explicit. Unresolved/uninspected repository, consumer or field coverage calls for progress/gaps and a continue-or-deliver question; produce a partial report/attachment only after user approval.

## CSV contract / quick reference

```csv
repository,module,source_revision,mq_technology,queue_or_topic,consumer_entry,binding_or_routing,container_factory,acknowledgement,concurrency,prefetch,environment_scope,evidence_status,source_locator
```

- **Identity:** Copy returned repository IDs/full revisions and source paths/ranges verbatim. `repository` and `source_revision` are the canonical pin; `source_locator` holds literal `path:Lstart-Lend` entries under that pin. Together they preserve full provenance without repeating SHA strings in locators.
- **Rows:** Emit each evidenced consumer/destination separately; deduplicate by pin, module, qualified consumer entry and destination before counting. Put excluded, commented-out and unclassified candidates in the coverage summary with pins/scopes/gaps, outside consumer rows.
- **Fields:** `container_factory` is an evidenced factory identity; exchange/type/routing and queue properties belong in `binding_or_routing`. A queues-only annotation does not establish that binding or factory is inapplicable.
- **Export preflight:** Construct every row in declared column order with exactly the header's cell count. If export rejects the input, correct row shape while retaining all already-supported rows; split by repository when needed.
  State a capacity limit only when supported by observed evidence.
- **Status:** `evidence_status` is `CONFIRMED_STATIC` or `HAS_UNKNOWNS`. Cells use `UNKNOWN(reason)` for unavailable values after inspection, evidenced `N/A(reason)`, or unread `UNCOVERED(reason)` only in `PARTIAL_USER_APPROVED` reports. Report status is `COMPLETE` or `PARTIAL_USER_APPROVED`.

## Focused example

Hypothetical approved partial: returned context is `orders` at `0123456789abcdef0123456789abcdef01234567`. Read `src/Inbox.java:L31-L39` shows `Inbox#login` consuming `login.q`, with topic/durable/quorum binding; factory/settings remain unread:

```csv
orders,main,0123456789abcdef0123456789abcdef01234567,RabbitMQ,login.q,Inbox#login,exchange=events;type=topic;durable=true;quorum,UNCOVERED(factory unread),UNCOVERED(ack unread),UNCOVERED(concurrency unread),UNCOVERED(prefetch unread),common/static,HAS_UNKNOWNS,src/Inbox.java:L31-L39
```

A second consumer/destination gets its own row. A commented listener goes in coverage, not the consumer count. Unread profiles stay outside common/static scope.

## Common mistakes

- Deep-reading every candidate first → finish Java/MQ classification first.
- Reading whole consumers or every profile → select ranges by missing requested fields.
- Treating no match, a filename, or static wiring as deployment proof → retain evidence scope and uncertainty.
