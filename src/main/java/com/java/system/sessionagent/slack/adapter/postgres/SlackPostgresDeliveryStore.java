package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.slack.domain.SlackCacheReportPresentation;
import com.java.system.sessionagent.slack.domain.SlackAnswerPresentation;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryKind;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackFileUpload;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackPresentation;
import com.java.system.sessionagent.slack.domain.SlackUploadState;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

public final class SlackPostgresDeliveryStore implements SlackDeliveryStore {
    private final SlackDatabaseMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ConversationQueryPort conversations;

    public SlackPostgresDeliveryStore(DataSource dataSource, ConversationQueryPort conversations) {
        this(SlackMybatisMapperFactory.create(dataSource), dataSource, conversations);
    }

    public SlackPostgresDeliveryStore(SlackDatabaseMapper mapper, DataSource dataSource, ConversationQueryPort conversations) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.mapper = Objects.requireNonNull(mapper, "Slack database mapper must not be null");
        this.conversations = Objects.requireNonNull(conversations, "Conversation query port must not be null");
        this.jdbc = new JdbcTemplate(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(requiredDataSource));
    }

    @Override
    public void discover() {
        transactionTemplate.executeWithoutResult(status -> {
            recoverFinalization();
            for (SlackDatabaseMapper.NewDeliveryRow delivery : mapper.discoverDeliveries()) {
                mapper.insertDelivery(UUID.randomUUID(), delivery.sessionId(), delivery.sequence(), delivery.messageJobId(),
                        delivery.teamId(), delivery.channelId(), delivery.rootThreadTs(), delivery.messageTs(), delivery.terminalText());
            }
        });
    }

    @Override
    public Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts) {
        Assert.hasText(workerId, "Slack delivery worker ID must not be blank");
        long leaseMillis = leaseMilliseconds(leaseDuration);
        Assert.isTrue(maximumAttempts > 0, "Slack delivery maximum attempts must be positive");
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            recoverFinalization();
            mapper.terminalizeExpiredExhaustedDeliveries(maximumAttempts);
            jdbc.update("""
                    update slack_delivery_upload u set state='FAILED' from slack_delivery d
                    where d.delivery_id=u.delivery_id and d.status='FAILED'
                        and u.state not in ('SENT','FAILED','UNCONFIRMED')
                    """);
            return mapper.claimNext(maximumAttempts, workerId, leaseMillis).stream()
                    .map(row -> new SlackDeliveryClaim(row.deliveryId(), SlackDeliveryKind.valueOf(row.kind()),
                            Optional.ofNullable(row.sessionId()), Optional.ofNullable(row.messageJobId()), row.claimNumber(),
                            row.attemptCount(), row.workerId(), row.lockedUntil().toInstant(),
                            new SlackPostRequest(row.channelId(), row.rootThreadTs(), row.terminalText())))
                    .findFirst().orElse(null); // cs-allow TransactionTemplate nullable no-claim result
        }));
    }

    @Override
    public Optional<SlackDeliveryPart> nextPart(SlackDeliveryClaim claim, Duration leaseDuration) {
        Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        long leaseMillis = leaseMilliseconds(leaseDuration);
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            if (mapper.renewLease(claim.deliveryId(), claim.workerId(), claim.claimNumber(), leaseMillis) != 1) {
                return null; // cs-allow TransactionTemplate nullable no-ownership result
            }
            Boolean prepared = jdbc.queryForObject("select items_prepared from slack_delivery where delivery_id=?",
                    Boolean.class, claim.deliveryId());
            if (!Boolean.TRUE.equals(prepared)) {
                prepareItems(claim);
                if (jdbc.update("""
                        update slack_delivery set items_prepared=true where delivery_id=? and status='WORKING'
                            and worker_id=? and claim_number=? and locked_until>clock_timestamp()
                        """, claim.deliveryId(), claim.workerId(), claim.claimNumber()) != 1) {
                    throw new IllegalStateException("Slack item preparation lost ownership");
                }
            }
            Optional<SlackDeliveryPart> next = jdbc.query("""
                    select p.part_index,p.part_text,p.presentation,u.identity,u.filename,u.title,u.media_type,
                        octet_length(u.content) as byte_length,
                        case when u.state='ALLOCATED' then u.content else null end as upload_content,
                        u.state,u.file_id,u.upload_url
                    from slack_delivery_part p join slack_delivery d on d.delivery_id=p.delivery_id
                    left join slack_delivery_upload u on u.delivery_id=p.delivery_id and u.part_index=p.part_index
                    where p.delivery_id=? and p.status='PENDING' and d.status='WORKING' and d.worker_id=?
                        and d.claim_number=? and d.locked_until>clock_timestamp()
                    order by p.part_index limit 1
                    """, (rs, row) -> {
                        SlackPresentation presentation = SlackPresentation.valueOf(rs.getString("presentation"));
                        Optional<SlackFileUpload> file = Optional.empty();
                        if (presentation == SlackPresentation.FILE) {
                            Optional<SlackUploadTicket> ticket = Optional.ofNullable(rs.getString("file_id"))
                                    .map(id -> {
                                        try {
                                            return new SlackUploadTicket(id, rs.getString("upload_url"));
                                        } catch (java.sql.SQLException exception) {
                                            throw new IllegalStateException("Cannot read Slack upload capability");
                                        }
                                    });
                            file = Optional.of(new SlackFileUpload(rs.getObject("identity", UUID.class), rs.getString("filename"),
                                    rs.getString("title"), rs.getString("media_type"), rs.getInt("byte_length"),
                                    Optional.ofNullable(rs.getBytes("upload_content")),
                                    SlackUploadState.valueOf(rs.getString("state")), ticket));
                        }
                        return new SlackDeliveryPart(rs.getInt("part_index"), rs.getString("part_text"), presentation, file);
                    }, claim.deliveryId(), claim.workerId(), claim.claimNumber()).stream().findFirst();
            // Preparation and loading bytes may take time; the network operation gets a fresh full lease.
            if (mapper.renewLease(claim.deliveryId(), claim.workerId(), claim.claimNumber(), leaseMillis) != 1) {
                throw new IllegalStateException("Slack item preparation lost ownership");
            }
            return next.orElse(null); // cs-allow TransactionTemplate nullable no-item result
        }));
    }

    private void prepareItems(SlackDeliveryClaim claim) {
        String text = claim.postRequest().text();
        String footer = claim.kind() == SlackDeliveryKind.ANSWER
                ? conversations.findJob(claim.messageJobId().orElseThrow().toString())
                        .flatMap(MessageJobView::cacheReport).map(SlackCacheReportPresentation::format).orElse("")
                : "";
        boolean assistantAnswer = claim.kind() == SlackDeliveryKind.ANSWER && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from slack_delivery d join assistant_message a
                    on a.session_id=d.terminal_session_id and a.sequence=d.terminal_sequence where d.delivery_id=?)
                """, Boolean.class, claim.deliveryId()));
        if (!assistantAnswer) {
            insertText(claim.deliveryId(), 0, footer.isEmpty() ? text : text + "\n\n" + footer, SlackPresentation.PLAIN);
            return;
        }
        SlackAnswerPresentation.Result result = SlackAnswerPresentation.present(text, footer);
        insertText(claim.deliveryId(), 0, result.text(), result.presentation());
        int index = 1;
        if (result.answerFile().isPresent()) {
            UUID identity = UUID.nameUUIDFromBytes(("slack-answer:" + claim.messageJobId().orElseThrow())
                    .getBytes(StandardCharsets.UTF_8));
            insertFile(claim.deliveryId(), index++, identity, "answer.md", "Complete response", "text/markdown; charset=utf-8",
                    result.answerFile().orElseThrow());
        }
        // Snapshot the released set in this same transaction, before items_prepared or any SENT transition.
        List<PreparedArtifact> artifacts = jdbc.query("""
                select artifact_id,filename,title,media_type,content from generated_artifact
                where session_id=? and message_job_id=? and released and observation_sequence is not null
                order by model_call_number,tool_ordinal
                """, (rs, row) -> new PreparedArtifact(rs.getObject("artifact_id", UUID.class), rs.getString("filename"),
                        rs.getString("title"), rs.getString("media_type"), rs.getBytes("content")),
                claim.sessionId().orElseThrow(), claim.messageJobId().orElseThrow());
        for (PreparedArtifact artifact : artifacts) {
            insertFile(claim.deliveryId(), index++, artifact.identity(), artifact.filename(), artifact.title(),
                    artifact.mediaType(), artifact.content());
        }
    }

    private void insertText(UUID deliveryId, int index, String text, SlackPresentation presentation) {
        jdbc.update("insert into slack_delivery_part(delivery_id,part_index,part_text,status,presentation) values(?,?,?,'PENDING',?)",
                deliveryId, index, text, presentation.name());
    }

    private void insertFile(UUID deliveryId, int index, UUID identity, String filename, String title, String mediaType, byte[] content) {
        // Titles may be empty or blank; the immutable runtime filename identifies the FILE item.
        insertText(deliveryId, index, filename, SlackPresentation.FILE);
        jdbc.update("""
                insert into slack_delivery_upload(delivery_id,part_index,identity,filename,title,media_type,content,state)
                values(?,?,?,?,?,?,?,'PREPARED')
                """, deliveryId, index, identity, filename, title, mediaType, content);
    }

    @Override
    public boolean allocated(SlackDeliveryClaim claim, SlackDeliveryPart part, SlackUploadTicket ticket) {
        return transition(claim, part, SlackUploadState.PREPARED, () -> jdbc.update("""
                update slack_delivery_upload set file_id=?,upload_url=?,state='ALLOCATED' where delivery_id=? and part_index=?
                """, ticket.fileId(), ticket.uploadUrl(), claim.deliveryId(), part.index()));
    }

    @Override
    public boolean uploaded(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        return transition(claim, part, SlackUploadState.ALLOCATED, () -> jdbc.update("""
                update slack_delivery_upload set state='UPLOADED' where delivery_id=? and part_index=?
                """, claim.deliveryId(), part.index()));
    }

    @Override
    public boolean beginFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part, Duration leaseDuration) {
        return transition(claim, part, SlackUploadState.UPLOADED, () -> {
            if (mapper.renewLease(claim.deliveryId(), claim.workerId(), claim.claimNumber(), leaseMilliseconds(leaseDuration)) != 1) {
                return 0;
            }
            return jdbc.update("""
                    update slack_delivery_upload set state='FINALIZING',finalization_claim=? where delivery_id=? and part_index=?
                    """, claim.claimNumber(), claim.deliveryId(), part.index());
        });
    }

    @Override
    public boolean rejectFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        return transition(claim, part, SlackUploadState.FINALIZING, () -> jdbc.update("""
                update slack_delivery_upload set state='UPLOADED',finalization_claim=null where delivery_id=? and part_index=?
                """, claim.deliveryId(), part.index()));
    }

    @Override
    public boolean unconfirmed(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        return transition(claim, part, SlackUploadState.FINALIZING, () -> {
            jdbc.update("update slack_delivery_upload set state='UNCONFIRMED' where delivery_id=? and part_index=?",
                    claim.deliveryId(), part.index());
            return mapper.markDeliveryFailed(SlackDeliveryFailureCategory.UNCONFIRMED.name(), claim.deliveryId(),
                    claim.workerId(), claim.claimNumber());
        });
    }

    private boolean transition(SlackDeliveryClaim claim, SlackDeliveryPart part, SlackUploadState expected,
                               IntSupplier update) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!owns(claim)) { return false; }
            List<String> states = jdbc.queryForList("select state from slack_delivery_upload where delivery_id=? and part_index=?",
                    String.class, claim.deliveryId(), part.index());
            if (!states.equals(List.of(expected.name()))) { return false; }
            if (expected == SlackUploadState.FINALIZING && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    select finalization_claim=? from slack_delivery_upload where delivery_id=? and part_index=?
                    """, Boolean.class, claim.claimNumber(), claim.deliveryId(), part.index()))) { return false; }
            if (update.getAsInt() != 1 || !ownedOrTerminal(claim)) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private boolean owns(SlackDeliveryClaim claim) {
        return !mapper.lockOwnedDelivery(claim.deliveryId(), claim.workerId(), claim.claimNumber()).isEmpty();
    }

    private boolean ownedOrTerminal(SlackDeliveryClaim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select claim_number=? and ((status='WORKING' and worker_id=? and locked_until>clock_timestamp())
                    or (status='FAILED' and failure_category='UNCONFIRMED')) from slack_delivery where delivery_id=?
                """, Boolean.class, claim.claimNumber(), claim.workerId(), claim.deliveryId()));
    }

    @Override
    public boolean releaseStage(SlackDeliveryClaim claim) {
        // Successful bounded stages do not consume the failure retry budget of later items.
        return jdbc.update("""
                update slack_delivery set status='RETRY',attempt_count=0,failure_category=null,next_attempt_at=clock_timestamp(),
                    worker_id=null,locked_until=null where delivery_id=? and status='WORKING' and worker_id=?
                    and claim_number=? and locked_until>clock_timestamp()
                    and not exists(select 1 from slack_delivery_upload u where u.delivery_id=slack_delivery.delivery_id and u.state='FINALIZING')
                """, claim.deliveryId(), claim.workerId(), claim.claimNumber()) == 1;
    }

    @Override
    public boolean markPartSent(SlackDeliveryClaim claim, SlackDeliveryPart part, String slackMessageTs) {
        Assert.hasText(slackMessageTs, "Slack acknowledgement ID must not be blank");
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!owns(claim)) { return false; }
            if (part.presentation() == SlackPresentation.FILE) {
                if (jdbc.update("""
                        update slack_delivery_upload set state='SENT' where delivery_id=? and part_index=?
                            and state='FINALIZING' and finalization_claim=?
                        """, claim.deliveryId(), part.index(), claim.claimNumber()) != 1) { return false; }
            }
            if (mapper.markPartSent(slackMessageTs, claim.deliveryId(), part.index(), claim.workerId(), claim.claimNumber()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            if (mapper.countUnsentParts(claim.deliveryId()) == 0) {
                String textTimestamp = jdbc.queryForObject("""
                        select slack_message_ts from slack_delivery_part where delivery_id=? and part_index=0
                        """, String.class, claim.deliveryId());
                if (mapper.markDeliverySent(textTimestamp, claim.deliveryId(), claim.workerId(), claim.claimNumber()) != 1) {
                    status.setRollbackOnly();
                    return false;
                }
            }
            mapper.registerProgress(claim.deliveryId());
            return true;
        }));
    }

    @Override
    public boolean markPartFailed(SlackDeliveryClaim claim, SlackDeliveryPart part, SlackDeliveryFailureCategory category,
                                  Optional<String> slackMessageTs) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!owns(claim)) { return false; }
            if (mapper.markPartFailed(slackMessageTs.orElse(null), claim.deliveryId(), part.index(), claim.workerId(), claim.claimNumber()) != 1
                    || mapper.markDeliveryFailed(category.name(), claim.deliveryId(), claim.workerId(), claim.claimNumber()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("update slack_delivery_upload set state='FAILED' where delivery_id=? and state not in ('SENT','UNCONFIRMED')",
                    claim.deliveryId());
            return true;
        }));
    }

    @Override
    public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
        Assert.isTrue(!delay.isNegative(), "Slack delivery retry delay must not be negative");
        return mapper.scheduleRetry(category.name(), delay.toMillis(), claim.deliveryId(), claim.workerId(), claim.claimNumber()) == 1;
    }

    private void recoverFinalization() {
        List<UUID> ambiguous = jdbc.queryForList("""
                select d.delivery_id from slack_delivery d where d.status='WORKING' and d.locked_until<=clock_timestamp()
                    and exists(select 1 from slack_delivery_upload u where u.delivery_id=d.delivery_id and u.state='FINALIZING')
                for update skip locked
                """, UUID.class);
        for (UUID deliveryId : ambiguous) {
            jdbc.update("update slack_delivery_upload set state='UNCONFIRMED' where delivery_id=? and state='FINALIZING'", deliveryId);
            jdbc.update("""
                    update slack_delivery set status='FAILED',failure_category='UNCONFIRMED',worker_id=null,locked_until=null
                    where delivery_id=?
                    """, deliveryId);
        }
    }

    @Override
    public Optional<SlackDeliveryView> read(UUID deliveryId) {
        return mapper.findDelivery(Objects.requireNonNull(deliveryId)).stream().findFirst().map(this::view);
    }

    @Override
    public List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs) {
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
        return mapper.findDeliveriesBySource(teamId, channelId, messageTs).stream().map(this::view).toList();
    }

    private SlackDeliveryView view(SlackDatabaseMapper.DeliveryViewRow row) {
        return new SlackDeliveryView(row.deliveryId(), SlackDeliveryKind.valueOf(row.kind()), SlackDeliveryStatus.valueOf(row.status()),
                row.attemptCount(), Optional.ofNullable(row.failureCategory()).map(SlackDeliveryFailureCategory::valueOf),
                Optional.ofNullable(row.slackMessageTs()), row.nextAttemptAt().toInstant(), Optional.ofNullable(row.messageJobId()),
                row.totalParts() == 0 ? Optional.empty() : Optional.of(row.totalParts()), row.sentParts());
    }

    private static long leaseMilliseconds(Duration duration) {
        Objects.requireNonNull(duration, "Slack delivery lease must not be null");
        Assert.isTrue(!duration.isNegative() && !duration.isZero(), "Slack delivery lease must be positive");
        return Math.max(1, duration.toMillis());
    }

    private record PreparedArtifact(UUID identity, String filename, String title, String mediaType, byte[] content) {}
}
