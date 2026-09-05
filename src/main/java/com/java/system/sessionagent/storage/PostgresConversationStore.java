package com.java.system.sessionagent.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
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
import java.sql.SQLRecoverableException;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

public final class PostgresConversationStore implements ConversationStore {

    private static final String SOURCE_TYPE = "http";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate historyTransactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PostgresConversationStore(DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.historyTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historyTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.historyTransactionTemplate.setReadOnly(true);
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper must not be null");
    }

    @Override
    public MessageReceipt receive(IncomingMessage incomingMessage) {
        IncomingMessage requiredIncomingMessage = Objects.requireNonNull(incomingMessage, "Incoming message must not be null");
        try {
            MessageReceipt receipt = transactionTemplate.execute(status -> receiveInNewTransaction(requiredIncomingMessage));
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
        Optional<StoredSourceMessage> existing = findSourceMessage(incomingMessage.sourceMessageId());
        if (existing.isPresent()) {
            StoredSourceMessage stored = existing.orElseThrow();
            if (stored.contentHash().equals(contentHash)) {
                return new MessageReceipt(new SessionId(stored.sessionId().toString()), new MessageJobId(stored.messageJobId().toString()));
            }
            throw new MessageConflictException();
        }
        StoredSession session = lockOrCreateSession(incomingMessage.sessionKey());
        long sequence = allocateSequence(session.sessionId());
        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        insertSourceMessage(incomingMessage.sourceMessageId(), session.sessionId(), sequence, contentHash, now);
        insertUserSessionMessage(session.sessionId(), sequence, now);
        insertUserMessage(incomingMessage, session.sessionId(), sequence);
        insertMessageJob(jobId, session.sessionId(), sequence, now);
        return new MessageReceipt(new SessionId(session.sessionId().toString()), new MessageJobId(jobId.toString()));
    }

    private void lockSourceIdentity(String sourceMessageId) {
        ByteBuffer buffer = ByteBuffer.wrap(sha256(SOURCE_TYPE, sourceMessageId));
        int firstLockKey = buffer.getInt();
        int secondLockKey = buffer.getInt();
        jdbcTemplate.execute("select pg_advisory_xact_lock(?, ?)", (PreparedStatementCallback<Void>) statement -> {
            statement.setInt(1, firstLockKey);
            statement.setInt(2, secondLockKey);
            statement.execute();
            return null;
        });
    }

    private StoredSession lockOrCreateSession(String sessionKey) {
        UUID generatedSessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into conversation_session(session_id, source_type, session_key, created_at)
                values (?, ?, ?, ?)
                on conflict (source_type, session_key) do nothing
                """, generatedSessionId, SOURCE_TYPE, sessionKey, timestamp(clock.instant()));
        return jdbcTemplate.query("""
                select session_id from conversation_session
                where source_type = ? and session_key = ? for update
                """, (resultSet, rowNumber) -> new StoredSession(resultSet.getObject("session_id", UUID.class)), SOURCE_TYPE, sessionKey)
                .stream().findFirst().orElseThrow(() -> ConversationStoreFailure.contract(
                        new IllegalStateException("Conversation session was not created")));
    }

    private Optional<StoredSourceMessage> findSourceMessage(String sourceMessageId) {
        return jdbcTemplate.query("""
                select source.session_id, source.content_hash, job.message_job_id
                from source_message source
                join message_job job on job.session_id = source.session_id
                    and job.user_message_sequence = source.user_message_sequence
                where source.source_type = ? and source.source_message_id = ?
                """, (resultSet, rowNumber) -> new StoredSourceMessage(
                resultSet.getObject("session_id", UUID.class), resultSet.getString("content_hash"),
                resultSet.getObject("message_job_id", UUID.class)), SOURCE_TYPE, sourceMessageId).stream().findFirst();
    }

    private long allocateSequence(UUID sessionId) {
        Long sequence = jdbcTemplate.queryForObject("""
                update conversation_session set next_sequence = next_sequence + 1
                where session_id = ? returning next_sequence - 1
                """, Long.class, sessionId);
        return Objects.requireNonNull(sequence, "Session sequence must not be null");
    }

    private void insertSourceMessage(String sourceMessageId, UUID sessionId, long sequence, String contentHash, Instant createdAt) {
        jdbcTemplate.update("""
                insert into source_message(source_type, source_message_id, session_id, user_message_sequence, content_hash, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, SOURCE_TYPE, sourceMessageId, sessionId, sequence, contentHash, timestamp(createdAt));
    }

    private void insertUserSessionMessage(UUID sessionId, long sequence, Instant createdAt) {
        jdbcTemplate.update("""
                insert into session_message(session_id, sequence, message_job_id, role, created_at)
                values (?, ?, null, 'USER', ?)
                """, sessionId, sequence, timestamp(createdAt));
    }

    private void insertUserMessage(IncomingMessage message, UUID sessionId, long sequence) {
        jdbcTemplate.update("""
                insert into user_message(session_id, sequence, participant_id, source_type, source_message_id, message)
                values (?, ?, ?, ?, ?, ?)
                """, sessionId, sequence, message.participantId(), SOURCE_TYPE, message.sourceMessageId(), message.message());
    }

    private void insertMessageJob(UUID jobId, UUID sessionId, long sequence, Instant createdAt) {
        jdbcTemplate.update("""
                insert into message_job(message_job_id, session_id, user_message_sequence, status, available_at, created_at)
                values (?, ?, ?, 'PENDING', clock_timestamp(), ?)
                """, jobId, sessionId, sequence, timestamp(createdAt));
    }

    @Override
    public Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration) {
        Assert.hasText(workerId, "Worker ID must not be blank");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Lease duration must be positive");
        try {
            Optional<MessageWorkClaim> claim = transactionTemplate.execute(status -> jdbcTemplate.query("""
                    with candidate as (
                        select candidate.message_job_id, candidate.session_id
                        from message_job candidate
                        where ((candidate.status in ('PENDING', 'RETRY') and candidate.available_at <= clock_timestamp())
                                or (candidate.status = 'WORKING' and candidate.locked_until <= clock_timestamp()))
                          and not exists (
                              select 1 from message_job earlier
                              where earlier.session_id = candidate.session_id
                                and earlier.message_job_id <> candidate.message_job_id
                                and earlier.user_message_sequence < candidate.user_message_sequence
                                and earlier.status in ('PENDING', 'RETRY', 'WORKING'))
                          and not exists (
                              select 1 from message_job working
                              where working.session_id = candidate.session_id
                                and working.message_job_id <> candidate.message_job_id and working.status = 'WORKING')
                        order by candidate.available_at, candidate.created_at, candidate.message_job_id
                        for update skip locked limit 1)
                    update message_job job set status = 'WORKING', worker_id = ?,
                        locked_until = clock_timestamp() + (? * interval '1 millisecond'), claim_number = job.claim_number + 1
                    from candidate where job.message_job_id = candidate.message_job_id and job.session_id = candidate.session_id
                    returning job.message_job_id, job.session_id, job.worker_id, job.claim_number, job.locked_until, clock_timestamp() as claimed_at
                    """, (resultSet, rowNumber) -> new MessageWorkClaim(
                    new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString()),
                    new SessionId(resultSet.getObject("session_id", UUID.class).toString()), resultSet.getString("worker_id"),
                    resultSet.getLong("claim_number"), resultSet.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("locked_until", OffsetDateTime.class).toInstant()), workerId,
                    positiveLeaseMilliseconds(requiredLeaseDuration)).stream().findFirst());
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
                    update message_job set locked_until = clock_timestamp() + (? * interval '1 millisecond')
                    where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                      and claim_number = ? and locked_until > clock_timestamp()
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
            List<SessionMessage> history = historyTransactionTemplate.execute(status -> loadHistoryInSingleSnapshot(requiredSessionId));
            return Objects.requireNonNull(history, "Conversation history must not be null");
        } catch (InvalidStoredNativeHistoryException exception) {
            throw ConversationStoreFailure.invalidHistory(exception);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void bindModelRoute(MessageWorkClaim claim, ModelRouteId modelRouteId) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        ModelRouteId requiredRouteId = Objects.requireNonNull(modelRouteId, "Model route ID must not be null");
        inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            List<Optional<String>> routes = jdbcTemplate.query("""
                    select model_route_id from message_job
                    where message_job_id = ? and session_id = ? for update
                    """, (resultSet, rowNumber) -> Optional.ofNullable(resultSet.getString("model_route_id")),
                    messageJobId(requiredClaim), sessionId(requiredClaim));
            Optional<String> storedRoute = routes.stream().findFirst().orElseThrow(StaleWorkClaimException::new);
            if (storedRoute.isEmpty()) {
                jdbcTemplate.update("""
                        update message_job set model_route_id = ?
                        where message_job_id = ? and session_id = ?
                        """, requiredRouteId.value(), messageJobId(requiredClaim), sessionId(requiredClaim));
            } else if (!storedRoute.orElseThrow().equals(requiredRouteId.value())) {
                throw new ModelRouteMismatchException();
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public Map<SessionSequence, ModelContinuation> loadContinuations(MessageWorkClaim claim) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        try {
            Map<SessionSequence, ModelContinuation> continuations = jdbcTemplate.query("""
                    select continuation.assistant_sequence, continuation.model_route_id, continuation.format, continuation.payload
                    from model_continuation continuation
                    join message_job job on job.message_job_id = continuation.message_job_id
                        and job.session_id = continuation.session_id
                    where continuation.message_job_id = ? and continuation.session_id = ?
                      and job.status = 'WORKING' and job.worker_id = ? and job.claim_number = ?
                      and job.locked_until > clock_timestamp()
                    order by continuation.assistant_sequence
                    """, (resultSet, rowNumber) -> Map.entry(new SessionSequence(resultSet.getLong("assistant_sequence")),
                    new ModelContinuation(new ModelRouteId(resultSet.getString("model_route_id")), resultSet.getString("format"),
                            resultSet.getBytes("payload"))), messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber()).stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> entry.getKey(), entry -> entry.getValue()));
            return Map.copyOf(continuations);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<ContextUsageCheckpoint> loadUsageCheckpoint(
            SessionId sessionId, ModelDescriptor model, String requestShapeFingerprint, long compactGeneration) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        ModelDescriptor requiredModel = Objects.requireNonNull(model, "Model descriptor must not be null");
        Assert.hasText(requestShapeFingerprint, "Request shape fingerprint must not be blank");
        Assert.isTrue(compactGeneration >= 0, "Compact generation must not be negative");
        try {
            return jdbcTemplate.query("""
                    select response_sequence, model_call_ordinal, prompt_tokens, completion_tokens, total_tokens, created_at
                    from context_usage_checkpoint where session_id = ? and model_route_id = ? and model_id = ?
                      and request_shape_fingerprint = ? and compact_generation = ? order by response_sequence desc limit 1
                    """, (resultSet, rowNumber) -> new ContextUsageCheckpoint(requiredModel,
                    resultSet.getInt("model_call_ordinal"), new SessionSequence(resultSet.getLong("response_sequence")),
                    resultSet.getLong("prompt_tokens"), resultSet.getLong("completion_tokens"), resultSet.getLong("total_tokens"),
                    requestShapeFingerprint, compactGeneration, resultSet.getObject("created_at", OffsetDateTime.class).toInstant()),
                    UUID.fromString(requiredSessionId.value()), requiredModel.routeId().value(), requiredModel.modelId(),
                    requestShapeFingerprint, compactGeneration).stream().findFirst();
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private List<SessionMessage> loadHistoryInSingleSnapshot(SessionId sessionId) {
        UUID parsedSessionId = UUID.fromString(sessionId.value());
        List<SessionMessage> messages = new ArrayList<>();
        messages.addAll(loadUserMessages(parsedSessionId));
        messages.addAll(loadAssistantToolCallsMessages(parsedSessionId));
        messages.addAll(loadToolObservations(parsedSessionId));
        messages.addAll(loadAssistantMessages(parsedSessionId));
        messages.addAll(loadRuntimeMessages(parsedSessionId));
        messages.sort(Comparator.comparingLong(message -> message.sequence().value()));
        return List.copyOf(messages);
    }

    @Override
    public OptionalInt reserveModelCall(MessageWorkClaim claim, int maxModelCalls, Instant now) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Assert.isTrue(maxModelCalls > 0, "Maximum model calls must be positive");
        Objects.requireNonNull(now, "Current time must not be null");
        try {
            return jdbcTemplate.query("""
                    update message_job set model_calls = model_calls + 1
                    where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                      and claim_number = ? and locked_until > clock_timestamp() and model_calls < ?
                    returning model_calls
                    """, (resultSet, rowNumber) -> resultSet.getInt("model_calls"), messageJobId(requiredClaim),
                    sessionId(requiredClaim), requiredClaim.workerId(), requiredClaim.claimNumber(), maxModelCalls)
                    .stream().findFirst().map(modelCalls -> OptionalInt.of(modelCalls))
                    .orElseGet(OptionalInt::empty);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        MessageBatch requiredBatch = Objects.requireNonNull(batch, "Message batch must not be null");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Message creation time must not be null");
        inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            UUID sessionId = sessionId(requiredClaim);
            Optional<SessionSequence> continuationSequence = Optional.empty();
            Optional<SessionSequence> responseSequence = Optional.empty();
            for (MessageData message : requiredBatch.messages()) {
                long sequence = allocateSequence(sessionId);
                requireLiveClaim(requiredClaim);
                if (message instanceof AssistantData assistant) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "ASSISTANT", requiredCreatedAt);
                    jdbcTemplate.update("insert into assistant_message(session_id, sequence, message) values (?, ?, ?)",
                            sessionId, sequence, assistant.message());
                    responseSequence = Optional.of(new SessionSequence(sequence));
                } else if (message instanceof AssistantToolCallsData assistantToolCalls) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "ASSISTANT_TOOL_CALLS", requiredCreatedAt);
                    jdbcTemplate.update("insert into assistant_tool_calls(session_id, sequence, message, calls) values (?, ?, ?, ?::jsonb)",
                            sessionId, sequence, assistantToolCalls.message().orElse(null), json(storedToolCalls(assistantToolCalls.calls()))); // cs-allow nullable database column represents optional assistant text
                    continuationSequence = Optional.of(new SessionSequence(sequence));
                    responseSequence = Optional.of(new SessionSequence(sequence));
                } else if (message instanceof ToolObservationData observation) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "TOOL", requiredCreatedAt);
                    jdbcTemplate.update("""
                            insert into tool_observation(session_id, sequence, tool_call_id, tool_name, output)
                            values (?, ?, ?, ?, ?::jsonb)
                            """, sessionId, sequence, observation.toolCallId().value(), observation.toolName(),
                            json(observation.output()));
                } else if (message instanceof RuntimeData runtime) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "RUNTIME", requiredCreatedAt);
                    jdbcTemplate.update("insert into runtime_message(session_id, sequence, code, message) values (?, ?, ?, ?)",
                            sessionId, sequence, runtime.code(), runtime.message());
                }
            }
            if (requiredBatch.usageCheckpoint().isPresent()) {
                ConversationStore.UsageCheckpointData checkpoint = requiredBatch.usageCheckpoint().orElseThrow();
                SessionSequence boundary = responseSequence.orElseThrow();
                jdbcTemplate.update("""
                        insert into context_usage_checkpoint(session_id, response_sequence, model_route_id, model_id, model_call_ordinal,
                            prompt_tokens, completion_tokens, total_tokens, request_shape_fingerprint, compact_generation, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, sessionId, boundary.value(), checkpoint.model().routeId().value(), checkpoint.model().modelId(),
                        checkpoint.modelCallOrdinal(), checkpoint.promptTokens(), checkpoint.completionTokens(), checkpoint.totalTokens(),
                        checkpoint.requestShapeFingerprint(), checkpoint.compactGeneration(), timestamp(requiredCreatedAt));
            }
            if (requiredBatch.continuation().isPresent()) {
                SessionSequence assistantSequence = continuationSequence.orElseThrow();
                ModelContinuation continuation = requiredBatch.continuation().orElseThrow();
                jdbcTemplate.update("""
                        insert into model_continuation(message_job_id, session_id, assistant_sequence, model_route_id, format, payload)
                        values (?, ?, ?, ?, ?, ?)
                        """, messageJobId(requiredClaim), sessionId, assistantSequence.value(), continuation.modelRouteId().value(),
                        continuation.format(), continuation.payload());
            }
            if (requiredBatch.jobUpdate() == JobUpdate.COMPLETE) {
                jdbcTemplate.update("delete from model_continuation where message_job_id = ? and session_id = ?",
                        messageJobId(requiredClaim), sessionId);
                completeJob(requiredClaim, clock.instant());
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Duration requiredDelay = Objects.requireNonNull(retryDelay, "Retry delay must not be null");
        Assert.isTrue(!requiredDelay.isNegative(), "Retry delay must not be negative");
        try {
            return jdbcTemplate.update("""
                    update message_job set status = 'RETRY', retry_count = retry_count + 1,
                        available_at = clock_timestamp() + (? * interval '1 millisecond'), worker_id = null, locked_until = null
                    where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                      and claim_number = ? and locked_until > clock_timestamp()
                    """, requiredDelay.toMillis(), messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            T result = transactionTemplate.execute(status -> work.get());
            return Objects.requireNonNull(result, "Transaction result must not be null");
        } catch (ModelRouteMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private void requireLiveClaim(MessageWorkClaim claim) {
        int rows = jdbcTemplate.update("""
                update message_job set locked_until = locked_until
                where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                  and claim_number = ? and locked_until > clock_timestamp()
                """, messageJobId(claim), sessionId(claim), claim.workerId(), claim.claimNumber());
        if (rows != 1) {
            throw new StaleWorkClaimException();
        }
    }

    private void insertSessionMessage(UUID sessionId, long sequence, UUID jobId, String role, Instant createdAt) {
        jdbcTemplate.update("""
                insert into session_message(session_id, sequence, message_job_id, role, created_at)
                values (?, ?, ?, ?, ?)
                """, sessionId, sequence, jobId, role, timestamp(createdAt));
    }

    private void completeJob(MessageWorkClaim claim, Instant completedAt) {
        int rows = jdbcTemplate.update("""
                update message_job set status = 'DONE', completed_at = ?, worker_id = null, locked_until = null
                where message_job_id = ? and session_id = ? and status = 'WORKING' and worker_id = ?
                  and claim_number = ? and locked_until > clock_timestamp()
                """, timestamp(completedAt), messageJobId(claim), sessionId(claim), claim.workerId(), claim.claimNumber());
        if (rows != 1) {
            throw new StaleWorkClaimException();
        }
    }

    private List<SessionMessage> loadUserMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.created_at, detail.participant_id, detail.message, job.message_job_id
                from session_message message join user_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                join message_job job on job.session_id = message.session_id and job.user_message_sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new UserMessage(new SessionId(sessionId.toString()),
                new SessionSequence(resultSet.getLong("sequence")), Optional.of(new MessageJobId(
                resultSet.getObject("message_job_id", UUID.class).toString())), resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                MessageRole.USER, resultSet.getString("participant_id"), resultSet.getString("message")), sessionId);
    }

    private List<SessionMessage> loadToolObservations(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.tool_call_id, detail.tool_name, detail.output
                from session_message message join tool_observation detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> storedToolObservation(sessionId, resultSet), sessionId);
    }

    private List<SessionMessage> loadAssistantToolCallsMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.message, detail.calls
                from session_message message join assistant_tool_calls detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> storedAssistantToolCalls(sessionId, resultSet), sessionId);
    }

    private ToolObservation storedToolObservation(UUID sessionId, ResultSet resultSet) throws java.sql.SQLException {
        try {
            return new ToolObservation(new SessionId(sessionId.toString()),
                    new SessionSequence(resultSet.getLong("sequence")), Optional.of(new MessageJobId(
                    resultSet.getObject("message_job_id", UUID.class).toString())), resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    MessageRole.TOOL, new ToolCallId(resultSet.getString("tool_call_id")),
                    resultSet.getString("tool_name"), structuredValue(resultSet.getString("output")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private AssistantToolCallsMessage storedAssistantToolCalls(UUID sessionId, ResultSet resultSet) throws java.sql.SQLException {
        try {
            return new AssistantToolCallsMessage(new SessionId(sessionId.toString()),
                    new SessionSequence(resultSet.getLong("sequence")), Optional.of(new MessageJobId(
                    resultSet.getObject("message_job_id", UUID.class).toString())), resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    MessageRole.ASSISTANT_TOOL_CALLS, Optional.ofNullable(resultSet.getString("message")), toolRequests(resultSet.getString("calls")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private List<SessionMessage> loadAssistantMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.message
                from session_message message join assistant_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new AssistantMessage(new SessionId(sessionId.toString()),
                new SessionSequence(resultSet.getLong("sequence")), Optional.of(new MessageJobId(
                resultSet.getObject("message_job_id", UUID.class).toString())), resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                MessageRole.ASSISTANT, resultSet.getString("message")), sessionId);
    }

    private List<SessionMessage> loadRuntimeMessages(UUID sessionId) {
        return jdbcTemplate.query("""
                select message.sequence, message.message_job_id, message.created_at, detail.code, detail.message
                from session_message message join runtime_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
                where message.session_id = ?
                """, (resultSet, rowNumber) -> new RuntimeMessage(new SessionId(sessionId.toString()),
                new SessionSequence(resultSet.getLong("sequence")), Optional.of(new MessageJobId(
                resultSet.getObject("message_job_id", UUID.class).toString())), resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                MessageRole.RUNTIME, resultSet.getString("code"), resultSet.getString("message")), sessionId);
    }

    @Override
    public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
        MessageJobId requiredMessageJobId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        try {
            return jdbcTemplate.query("""
                    select message_job_id, session_id, status, retry_count, model_calls from message_job where message_job_id = ?
                    """, (resultSet, rowNumber) -> new MessageJobProjection(
                    new MessageJobId(resultSet.getObject("message_job_id", UUID.class).toString()),
                    new SessionId(resultSet.getObject("session_id", UUID.class).toString()), JobStatus.valueOf(resultSet.getString("status")),
                    resultSet.getInt("retry_count"), resultSet.getInt("model_calls")), UUID.fromString(requiredMessageJobId.value()))
                    .stream().findFirst();
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private UUID messageJobId(MessageWorkClaim claim) {
        return UUID.fromString(claim.messageJobId().value());
    }

    private UUID sessionId(MessageWorkClaim claim) {
        return UUID.fromString(claim.sessionId().value());
    }

    private static long positiveLeaseMilliseconds(Duration leaseDuration) {
        long milliseconds = leaseDuration.toMillis();
        return milliseconds > 0 ? milliseconds : 1;
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

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Conversation data cannot be serialized", exception);
        }
    }

    private Object structuredValue(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored conversation output is invalid", exception);
        }
    }

    private List<ToolRequest> toolRequests(String value) {
        try {
            List<StoredToolCall> calls = objectMapper.readValue(value, new TypeReference<List<StoredToolCall>>() { });
            return calls.stream().map(call -> new ToolRequest(new ToolCallId(call.toolCallId()), new ToolName(call.toolName()), call.arguments())).toList();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private static List<StoredToolCall> storedToolCalls(List<ToolCallData> calls) {
        return calls.stream()
                .map(call -> new StoredToolCall(call.toolCallId().value(), call.toolName(), call.arguments()))
                .toList();
    }

    private static RuntimeException translate(RuntimeException exception) {
        if (exception instanceof ConversationStoreFailure || exception instanceof StaleWorkClaimException
                || exception instanceof MessageConflictException || exception instanceof ModelRouteMismatchException) {
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
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException || current instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record StoredSession(UUID sessionId) {
    }

    private record StoredSourceMessage(UUID sessionId, String contentHash, UUID messageJobId) {
    }

    private record StoredToolCall(String toolCallId, String toolName, java.util.Map<String, Object> arguments) {
    }

    private static final class InvalidStoredNativeHistoryException extends RuntimeException {

        private InvalidStoredNativeHistoryException(Throwable cause) {
            super("Stored native conversation history is invalid", cause);
        }
    }
}
