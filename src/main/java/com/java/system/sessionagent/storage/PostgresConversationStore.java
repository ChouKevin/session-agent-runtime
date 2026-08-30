package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

public final class PostgresConversationStore implements ConversationStore {

    private static final String SOURCE_TYPE = "http";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private record JobVisibility(UUID messageJobId, long userMessageSequence, JobStatus status) { }

    public PostgresConversationStore(DataSource dataSource, Clock clock) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public MessageReceipt receive(IncomingMessage incomingMessage) {
        IncomingMessage requiredIncomingMessage = Objects.requireNonNull(incomingMessage, "Incoming message must not be null");
        try {
            MessageReceipt receipt = transactionTemplate.execute(
                    transactionStatus -> receiveInNewTransaction(requiredIncomingMessage));
            return Objects.requireNonNull(receipt, "Message receipt must not be null");
        } catch (ConversationStoreFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private MessageReceipt receiveInNewTransaction(IncomingMessage incomingMessage) {
        lockSourceIdentity(incomingMessage.sourceMessageId());
        String contentHash = contentHash(incomingMessage.participantId(), incomingMessage.message());
        Optional<StoredSourceMessage> existingSourceMessage = findSourceMessage(incomingMessage.sourceMessageId());
        if (existingSourceMessage.isPresent()) {
            StoredSourceMessage storedSourceMessage = existingSourceMessage.orElseThrow();
            if (storedSourceMessage.contentHash().equals(contentHash)) {
                return new MessageReceipt(
                        new SessionId(storedSourceMessage.sessionId().toString()),
                        new MessageJobId(storedSourceMessage.messageJobId().toString()));
            }
            throw new MessageConflictException();
        }

        StoredSession session = lockOrCreateSession(incomingMessage.sessionKey());
        long sequence = allocateSequence(session.sessionId());
        UUID messageJobId = UUID.randomUUID();
        Instant now = clock.instant();
        insertSourceMessage(incomingMessage.sourceMessageId(), session.sessionId(), sequence, contentHash, now);
        insertSessionMessage(session.sessionId(), sequence, now);
        insertUserMessage(incomingMessage, session.sessionId(), sequence);
        insertMessageJob(messageJobId, session.sessionId(), sequence, now);
        return new MessageReceipt(new SessionId(session.sessionId().toString()), new MessageJobId(messageJobId.toString()));
    }

    private void lockSourceIdentity(String sourceMessageId) {
        byte[] sourceIdentityHash = sha256(SOURCE_TYPE, sourceMessageId);
        ByteBuffer byteBuffer = ByteBuffer.wrap(sourceIdentityHash);
        int firstLockKey = byteBuffer.getInt();
        int secondLockKey = byteBuffer.getInt();
        jdbcTemplate.execute("select pg_advisory_xact_lock(?, ?)", (PreparedStatementCallback<Void>) statement -> {
            statement.setInt(1, firstLockKey);
            statement.setInt(2, secondLockKey);
            statement.execute();
            return null;
        });
    }

    private StoredSession lockOrCreateSession(String sessionKey) {
        UUID generatedSessionId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                insert into conversation_session(session_id, source_type, session_key, created_at)
                values (?, ?, ?, ?)
                on conflict (source_type, session_key) do nothing
                """, generatedSessionId, SOURCE_TYPE, sessionKey, timestamp(now));
        List<StoredSession> sessions = jdbcTemplate.query("""
                select session_id
                from conversation_session
                where source_type = ? and session_key = ?
                for update
                """, (resultSet, rowNumber) -> new StoredSession(resultSet.getObject("session_id", UUID.class)),
                SOURCE_TYPE, sessionKey);
        return sessions.stream()
                .findFirst()
                .orElseThrow(() -> ConversationStoreFailure.contract(new IllegalStateException("Conversation session was not created")));
    }

    private Optional<StoredSourceMessage> findSourceMessage(String sourceMessageId) {
        List<StoredSourceMessage> sourceMessages = jdbcTemplate.query("""
                select source.session_id, source.user_message_sequence, source.content_hash, job.message_job_id
                from source_message source
                join message_job job
                  on job.session_id = source.session_id
                 and job.user_message_sequence = source.user_message_sequence
                where source.source_type = ? and source.source_message_id = ?
                """, (resultSet, rowNumber) -> new StoredSourceMessage(
                resultSet.getObject("session_id", UUID.class),
                resultSet.getLong("user_message_sequence"),
                resultSet.getString("content_hash"),
                resultSet.getObject("message_job_id", UUID.class)), SOURCE_TYPE, sourceMessageId);
        return sourceMessages.stream().findFirst();
    }

    private long allocateSequence(UUID sessionId) {
        Long sequence = jdbcTemplate.queryForObject("""
                update conversation_session
                set next_sequence = next_sequence + 1
                where session_id = ?
                returning next_sequence - 1
                """, Long.class, sessionId);
        return Objects.requireNonNull(sequence, "Session sequence must not be null");
    }

    private void insertSourceMessage(
            String sourceMessageId,
            UUID sessionId,
            long sequence,
            String contentHash,
            Instant createdAt) {
        jdbcTemplate.update("""
                insert into source_message(
                    source_type, source_message_id, session_id, user_message_sequence, content_hash, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, SOURCE_TYPE, sourceMessageId, sessionId, sequence, contentHash, timestamp(createdAt));
    }

    private void insertSessionMessage(UUID sessionId, long sequence, Instant createdAt) {
        jdbcTemplate.update("""
                insert into session_message(session_id, sequence, message_job_id, role, created_at)
                values (?, ?, null, 'USER', ?)
                """, sessionId, sequence, timestamp(createdAt));
    }

    private void insertUserMessage(IncomingMessage incomingMessage, UUID sessionId, long sequence) {
        jdbcTemplate.update("""
                insert into user_message(
                    session_id, sequence, participant_id, source_type, source_message_id, message)
                values (?, ?, ?, ?, ?, ?)
                """, sessionId, sequence, incomingMessage.participantId(), SOURCE_TYPE,
                incomingMessage.sourceMessageId(), incomingMessage.message());
    }

    private void insertMessageJob(UUID messageJobId, UUID sessionId, long sequence, Instant createdAt) {
        jdbcTemplate.update("""
                insert into message_job(message_job_id, session_id, user_message_sequence, status, available_at, created_at)
                values (?, ?, ?, 'PENDING', clock_timestamp(), ?)
                """, messageJobId, sessionId, sequence, timestamp(createdAt));
    }

    private String contentHash(String participantId, String message) {
        return HexFormat.of().formatHex(sha256(participantId, message));
    }

    private byte[] sha256(String firstValue, String secondValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthFramed(digest, firstValue);
            updateLengthFramed(digest, secondValue);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateLengthFramed(MessageDigest digest, String value) {
        byte[] utf8Value = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(utf8Value.length).array());
        digest.update(utf8Value);
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Override
    public Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration) {
        Assert.hasText(workerId, "Worker ID must not be blank");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Lease duration must be positive");
        try {
            Optional<MessageWorkClaim> claim = transactionTemplate.execute(transactionStatus -> jdbcTemplate.query("""
                    with candidate as (
                        select candidate.message_job_id, candidate.session_id
                        from message_job candidate
                        where (
                                (candidate.status in ('PENDING', 'RETRY') and candidate.available_at <= clock_timestamp())
                                or (candidate.status = 'WORKING' and candidate.locked_until <= clock_timestamp())
                            )
                          and not exists (
                              select 1
                              from message_job earlier
                              where earlier.session_id = candidate.session_id
                                and earlier.message_job_id <> candidate.message_job_id
                                and earlier.user_message_sequence < candidate.user_message_sequence
                                and earlier.status in ('PENDING', 'RETRY', 'WORKING')
                          )
                          and not exists (
                              select 1
                              from message_job working
                              where working.session_id = candidate.session_id
                                and working.message_job_id <> candidate.message_job_id
                                and working.status = 'WORKING'
                          )
                        order by candidate.available_at, candidate.created_at, candidate.message_job_id
                        for update skip locked
                        limit 1
                    )
                    update message_job job
                    set status = 'WORKING',
                        worker_id = ?,
                        locked_until = clock_timestamp() + (? * interval '1 millisecond'),
                        claim_number = job.claim_number + 1
                    from candidate
                    where job.message_job_id = candidate.message_job_id
                      and job.session_id = candidate.session_id
                    returning job.message_job_id, job.session_id, job.worker_id, job.claim_number, job.locked_until, clock_timestamp() as claimed_at
                    """, (resultSet, rowNumber) -> new MessageWorkClaim(
                    new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString()),
                    new SessionId(resultSet.getObject("session_id", UUID.class).toString()),
                    resultSet.getString("worker_id"),
                    resultSet.getLong("claim_number"),
                    resultSet.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("locked_until", OffsetDateTime.class).toInstant()),
                    workerId, positiveLeaseMilliseconds(requiredLeaseDuration))
                    .stream().findFirst());
            return Objects.requireNonNull(claim, "Claim result must not be null");
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public boolean extendClaim(MessageWorkClaim claim, Duration leaseDuration) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Lease duration must be positive");
        try {
            return jdbcTemplate.update("""
                    update message_job
                    set locked_until = clock_timestamp() + (? * interval '1 millisecond')
                    where message_job_id = ?
                      and session_id = ?
                      and status = 'WORKING'
                      and worker_id = ?
                      and claim_number = ?
                      and locked_until > clock_timestamp()
                      and locked_until < clock_timestamp() + (? * interval '1 millisecond')
                    """, positiveLeaseMilliseconds(requiredLeaseDuration), messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber(), positiveLeaseMilliseconds(requiredLeaseDuration)) == 1;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public List<SessionMessage> loadHistory(SessionId sessionId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        try {
            List<SessionMessage> messages = new java.util.ArrayList<>();
            UUID id = UUID.fromString(requiredSessionId.value());
            messages.addAll(loadToolMessages(id));
            messages.addAll(loadAssistantMessages(id));
            messages.addAll(loadFeedbackMessages(id));
            messages.addAll(loadUserMessages(id));
            messages.sort(java.util.Comparator.comparingLong(message -> message.sequence().value()));
            return List.copyOf(messages);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public List<SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        MessageJobId requiredMessageJobId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        try {
            UUID parsedSessionId = UUID.fromString(requiredSessionId.value());
            UUID parsedJobId = UUID.fromString(requiredMessageJobId.value());
            List<JobVisibility> jobs = jdbcTemplate.query("""
                    select message_job_id, user_message_sequence, status
                    from message_job
                    where session_id = ?
                    """, (resultSet, rowNumber) -> new JobVisibility(
                    resultSet.getObject("message_job_id", UUID.class), resultSet.getLong("user_message_sequence"),
                    JobStatus.valueOf(resultSet.getString("status"))), parsedSessionId);
            JobVisibility current = jobs.stream().filter(value -> value.messageJobId().equals(parsedJobId)).findFirst()
                    .orElseThrow(() -> ConversationStoreFailure.contract(new IllegalStateException("Message job is unavailable")));
            java.util.Set<UUID> visibleJobs = jobs.stream()
                    .filter(value -> value.messageJobId().equals(parsedJobId)
                            || (value.userMessageSequence() < current.userMessageSequence() && value.status() == JobStatus.DONE))
                    .map(JobVisibility::messageJobId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            java.util.Set<Long> visibleUserSequences = jobs.stream()
                    .filter(value -> value.messageJobId().equals(parsedJobId)
                            || (value.userMessageSequence() < current.userMessageSequence() && value.status() == JobStatus.DONE))
                    .map(JobVisibility::userMessageSequence).collect(java.util.stream.Collectors.toUnmodifiableSet());
            return loadHistory(requiredSessionId).stream().filter(message -> message.messageJobId()
                    .map(id -> visibleJobs.contains(UUID.fromString(id.value())))
                    .orElseGet(() -> visibleUserSequences.contains(message.sequence().value()))).toList();
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OptionalInt reserveModelCall(MessageWorkClaim claim, Instant now) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Objects.requireNonNull(now, "Current time must not be null");
        try {
            List<Integer> ordinals = jdbcTemplate.query("""
                    update message_job
                    set model_calls = model_calls + 1
                    where message_job_id = ?
                      and session_id = ?
                      and status = 'WORKING'
                      and worker_id = ?
                      and claim_number = ?
                      and locked_until > clock_timestamp()
                      and model_calls < 12
                    returning model_calls
                    """, (resultSet, rowNumber) -> resultSet.getInt("model_calls"), messageJobId(requiredClaim),
                    sessionId(requiredClaim), requiredClaim.workerId(), requiredClaim.claimNumber());
            return ordinals.stream().findFirst().map(OptionalInt::of).orElseGet(OptionalInt::empty);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public ToolMessage appendTool(
            MessageWorkClaim claim,
            ResultId resultId,
            String modelCallId,
            String modelContext,
            ToolData toolData,
            Instant createdAt) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        ResultId requiredResultId = Objects.requireNonNull(resultId, "Result ID must not be null");
        Assert.hasText(modelCallId, "Model call ID must not be blank");
        Assert.hasText(modelContext, "Model context must not be blank");
        ToolData requiredData = Objects.requireNonNull(toolData, "Tool data must not be null");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Message creation time must not be null");
        return inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            UUID sessionId = sessionId(requiredClaim);
            long sequence = allocateSequence(sessionId);
            insertSessionMessage(sessionId, sequence, UUID.fromString(requiredClaim.messageJobId().value()), "TOOL", requiredCreatedAt);
            jdbcTemplate.update("""
                    insert into tool_message(session_id, sequence, result_id, model_call_id, model_context, tool_name, tool_version, tool_kind,
                        arguments_json, repository_id, revision, result_json, citeable)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, sessionId, sequence, UUID.fromString(requiredResultId.value()), modelCallId, modelContext, requiredData.toolName(),
                    requiredData.toolVersion(), requiredData.persistedKind(), requiredData.canonicalArguments(),
                    requiredData.repositoryId().orElse(null), requiredData.revision().orElse(null), requiredData.resultJson(), requiredData.citeable());
            return new ToolMessage(requiredClaim.sessionId(), new SessionSequence(sequence), Optional.of(requiredClaim.messageJobId()),
                    requiredCreatedAt, MessageRole.TOOL, requiredResultId, modelCallId, modelContext, requiredData.toolName(), requiredData.toolVersion(),
                    requiredData.canonicalArguments(), requiredData.repositoryId(), requiredData.revision(), requiredData.resultJson(), requiredData.citeable());
        });
    }

    @Override
    public FeedbackMessage appendFeedback(
            MessageWorkClaim claim,
            String code,
            String message,
            boolean terminal,
            Optional<String> modelCallId,
            Optional<String> toolName,
            Optional<String> rejectedArguments,
            Optional<String> modelContext,
            Instant createdAt) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Assert.hasText(code, "Feedback code must not be blank");
        Assert.hasText(message, "Feedback message must not be blank");
        Optional<String> requiredModelCallId = Objects.requireNonNull(modelCallId, "Model call ID must not be null");
        Optional<String> requiredToolName = Objects.requireNonNull(toolName, "Tool name must not be null");
        Optional<String> requiredArguments = Objects.requireNonNull(rejectedArguments, "Rejected arguments must not be null");
        Optional<String> requiredModelContext = Objects.requireNonNull(modelContext, "Model context must not be null");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Message creation time must not be null");
        return inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            UUID sessionId = sessionId(requiredClaim);
            long sequence = allocateSequence(sessionId);
            insertSessionMessage(sessionId, sequence, UUID.fromString(requiredClaim.messageJobId().value()), "FEEDBACK", requiredCreatedAt);
            jdbcTemplate.update("""
                    insert into feedback_message(session_id, sequence, code, message, terminal, model_call_id, tool_name,
                        rejected_arguments_json, model_context)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, sessionId, sequence, code, message, terminal, requiredModelCallId.orElse(null), requiredToolName.orElse(null),
                    requiredArguments.orElse(null), requiredModelContext.orElse(null));
            if (terminal) { completeJob(requiredClaim, sequence, clock.instant()); }
            return new FeedbackMessage(requiredClaim.sessionId(), new SessionSequence(sequence), Optional.of(requiredClaim.messageJobId()),
                    requiredCreatedAt, MessageRole.FEEDBACK, code, message, terminal, requiredModelCallId, requiredToolName,
                    requiredArguments, requiredModelContext);
        });
    }

    @Override
    public AssistantMessage appendAssistant(MessageWorkClaim claim, String message, Instant createdAt) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Assert.hasText(message, "Assistant message must not be blank");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Message creation time must not be null");
        return inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            UUID sessionId = sessionId(requiredClaim);
            long sequence = allocateSequence(sessionId);
            insertSessionMessage(sessionId, sequence, UUID.fromString(requiredClaim.messageJobId().value()), "ASSISTANT", requiredCreatedAt);
            jdbcTemplate.update("insert into assistant_message(session_id, sequence, message) values (?, ?, ?)", sessionId, sequence, message);
            completeJob(requiredClaim, sequence, clock.instant());
            return new AssistantMessage(requiredClaim.sessionId(), new SessionSequence(sequence), Optional.of(requiredClaim.messageJobId()),
                    requiredCreatedAt, MessageRole.ASSISTANT, message);
        });
    }

    @Override
    public boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Duration requiredRetryDelay = Objects.requireNonNull(retryDelay, "Retry delay must not be null");
        Assert.isTrue(!requiredRetryDelay.isNegative(), "Retry delay must not be negative");
        try {
            return jdbcTemplate.update("""
                    update message_job
                    set status = 'RETRY',
                        retry_count = retry_count + 1,
                        available_at = clock_timestamp() + (? * interval '1 millisecond'),
                        worker_id = null,
                        locked_until = null
                    where message_job_id = ?
                      and session_id = ?
                      and status = 'WORKING'
                      and worker_id = ?
                      and claim_number = ?
                      and locked_until > clock_timestamp()
                    """, requiredRetryDelay.toMillis(), messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private UUID messageJobId(MessageWorkClaim claim) {
        return UUID.fromString(claim.messageJobId().value());
    }

    private long positiveLeaseMilliseconds(Duration leaseDuration) {
        long milliseconds = leaseDuration.toMillis();
        return milliseconds == 0 ? 1 : milliseconds;
    }

    private UUID sessionId(MessageWorkClaim claim) {
        return UUID.fromString(claim.sessionId().value());
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            T result = transactionTemplate.execute(transactionStatus -> work.get());
            return Objects.requireNonNull(result, "Transaction result must not be null");
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private void requireLiveClaim(MessageWorkClaim claim) {
        List<UUID> sessions = jdbcTemplate.query("""
                select session_id from conversation_session where session_id = ? for update
                """, (resultSet, rowNumber) -> resultSet.getObject("session_id", UUID.class), sessionId(claim));
        if (sessions.isEmpty()) {
            throw new StaleWorkClaimException();
        }
        jdbcTemplate.query("""
                select message_job_id from message_job
                where message_job_id = ? and session_id = ?
                for update
                """, (resultSet, rowNumber) -> resultSet.getObject("message_job_id", UUID.class),
                messageJobId(claim), sessionId(claim));
        List<UUID> liveJobs = jdbcTemplate.query("""
                select message_job_id from message_job
                where message_job_id = ? and session_id = ? and status = 'WORKING'
                  and worker_id = ? and claim_number = ? and locked_until > clock_timestamp()
                """, (resultSet, rowNumber) -> resultSet.getObject("message_job_id", UUID.class),
                messageJobId(claim), sessionId(claim), claim.workerId(), claim.claimNumber());
        if (liveJobs.isEmpty()) {
            throw new StaleWorkClaimException();
        }
    }

    private void insertSessionMessage(UUID sessionId, long sequence, UUID messageJobId, String role, Instant createdAt) {
        jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, ?, ?, ?, ?)",
                sessionId, sequence, messageJobId, role, timestamp(createdAt));
    }

    private void completeJob(MessageWorkClaim claim, long replySequence, Instant completedAt) {
        int changed = jdbcTemplate.update("""
                update message_job set status = 'DONE', reply_sequence = ?, completed_at = ?, worker_id = null, locked_until = null
                where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                  and claim_number = ? and locked_until > clock_timestamp()
                """, replySequence, timestamp(completedAt), messageJobId(claim), sessionId(claim), claim.workerId(),
                claim.claimNumber());
        if (changed != 1) { throw new StaleWorkClaimException(); }
    }

    private List<SessionMessage> loadUserMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.created_at, detail.participant_id, detail.message
                from session_message message join user_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new com.java.system.sessionagent.conversation.domain.UserMessage(
                new SessionId(sessionId.toString()), new SessionSequence(resultSet.getLong("sequence")), Optional.empty(),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(), MessageRole.USER,
                resultSet.getString("participant_id"), resultSet.getString("message")), sessionId);
    }

    private List<SessionMessage> loadToolMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.result_id, detail.model_call_id, detail.model_context, detail.tool_name,
                       detail.tool_version, detail.arguments_json, detail.repository_id, detail.revision, detail.result_json, detail.citeable
                from session_message message join tool_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new ToolMessage(new SessionId(sessionId.toString()), new SessionSequence(resultSet.getLong("sequence")),
                Optional.of(new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString())),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(), MessageRole.TOOL,
                new ResultId(resultSet.getObject("result_id", UUID.class).toString()), resultSet.getString("model_call_id"),
                resultSet.getString("model_context"), resultSet.getString("tool_name"),
                resultSet.getString("tool_version"), resultSet.getString("arguments_json"), Optional.ofNullable(resultSet.getString("repository_id")),
                Optional.ofNullable(resultSet.getString("revision")), resultSet.getString("result_json"), resultSet.getBoolean("citeable")), sessionId);
    }

    private List<SessionMessage> loadFeedbackMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.code, detail.message, detail.terminal,
                       detail.model_call_id, detail.tool_name, detail.rejected_arguments_json, detail.model_context
                from session_message message join feedback_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new FeedbackMessage(new SessionId(sessionId.toString()), new SessionSequence(resultSet.getLong("sequence")),
                Optional.of(new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString())),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(), MessageRole.FEEDBACK, resultSet.getString("code"),
                resultSet.getString("message"), resultSet.getBoolean("terminal"), Optional.ofNullable(resultSet.getString("model_call_id")),
                Optional.ofNullable(resultSet.getString("tool_name")), Optional.ofNullable(resultSet.getString("rejected_arguments_json")),
                Optional.ofNullable(resultSet.getString("model_context"))), sessionId);
    }

    private List<SessionMessage> loadAssistantMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.message
                from session_message message join assistant_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new AssistantMessage(new SessionId(sessionId.toString()),
            new SessionSequence(resultSet.getLong("sequence")),
                    Optional.of(new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString())),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(), MessageRole.ASSISTANT,
                    resultSet.getString("message")), sessionId);
    }

    @Override
    public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
        MessageJobId requiredMessageJobId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        try {
            return jdbcTemplate.query("""
                    select message_job_id, session_id, status, retry_count, model_calls, reply_sequence
                    from message_job where message_job_id = ?
                    """, (resultSet, rowNumber) -> new MessageJobProjection(
                    new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString()),
                    new SessionId(resultSet.getObject("session_id", UUID.class).toString()),
                    JobStatus.valueOf(resultSet.getString("status")), resultSet.getInt("retry_count"), resultSet.getInt("model_calls"),
                    Optional.ofNullable(resultSet.getObject("reply_sequence", Long.class)).map(SessionSequence::new)),
                    UUID.fromString(requiredMessageJobId.value())).stream().findFirst();
        } catch (RuntimeException exception) { throw translate(exception); }
    }

    @Override
    public Optional<ResultProjection> readResult(ResultId resultId) {
        ResultId requiredResultId = Objects.requireNonNull(resultId, "Result ID must not be null");
        UUID parsedResultId;
        try {
            parsedResultId = UUID.fromString(requiredResultId.value());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        try {
            return jdbcTemplate.query("""
                    select result_id, session_id, tool_name, tool_version, arguments_json, repository_id, revision, result_json, citeable
                    from tool_message where result_id = ?
                    """, (resultSet, rowNumber) -> new ResultProjection(
                    new ResultId(resultSet.getObject("result_id", UUID.class).toString()),
                    new SessionId(resultSet.getObject("session_id", UUID.class).toString()), resultSet.getString("tool_name"),
                    resultSet.getString("tool_version"), resultSet.getString("arguments_json"),
                    Optional.ofNullable(resultSet.getString("repository_id")), Optional.ofNullable(resultSet.getString("revision")),
                    resultSet.getString("result_json"), resultSet.getBoolean("citeable")), parsedResultId).stream().findFirst();
        } catch (RuntimeException exception) { throw translate(exception); }
    }

    private static RuntimeException translate(RuntimeException exception) {
        if (exception instanceof ConversationStoreFailure || exception instanceof StaleWorkClaimException
                || exception instanceof MessageConflictException) {
            return exception;
        }
        if (isTransient(exception)) {
            return ConversationStoreFailure.transientFailure(exception);
        }
        return ConversationStoreFailure.contract(exception);
    }

    private static boolean isTransient(RuntimeException exception) {
        if (exception instanceof TransientDataAccessException || exception instanceof DataAccessResourceFailureException
                || exception instanceof QueryTimeoutException) {
            return true;
        }
        for (Throwable current = exception; current != null; current = current.getCause()) { // cs-allow traversal ends at null
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException
                    || current instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record StoredSession(UUID sessionId) {
    }

    private record StoredSourceMessage(
            UUID sessionId,
            long userMessageSequence,
            String contentHash,
            UUID messageJobId) {
    }
}
