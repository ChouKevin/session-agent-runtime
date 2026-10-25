package com.java.system.sessionagent.storage.adapter.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import com.java.system.sessionagent.conversation.domain.JobCacheReport;
import com.java.system.sessionagent.conversation.domain.SkillCommand;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.in.QueueFullException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.ModelSelectionLookup;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class PostgresConversationStore implements ConversationStore {

    private final PostgresConversationMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate historyTransactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final PostgresIntakeCapacity intakeCapacity;
    private final PostgresArtifactStore artifactStore;

    public PostgresConversationStore(DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
        this(dataSource, clock, objectMapper, new PostgresIntakeCapacity(dataSource));
    }

    public PostgresConversationStore(
            DataSource dataSource, Clock clock, ObjectMapper objectMapper, PostgresIntakeCapacity intakeCapacity) {
        this(PostgresMybatisMapperFactory.create(dataSource), dataSource, clock, objectMapper, intakeCapacity);
    }

    public PostgresConversationStore(
            PostgresConversationMapper mapper,
            DataSource dataSource,
            Clock clock,
            ObjectMapper objectMapper,
            PostgresIntakeCapacity intakeCapacity) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.mapper = Objects.requireNonNull(mapper, "Conversation mapper must not be null");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.historyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.historyTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historyTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.historyTransactionTemplate.setReadOnly(true);
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper must not be null");
        this.intakeCapacity = Objects.requireNonNull(intakeCapacity, "Intake capacity must not be null");
        this.artifactStore = new PostgresArtifactStore(requiredDataSource, objectMapper);
    }

    @Override
    public MessageReceipt receive(
            IncomingMessage incomingMessage,
            ModelSelection defaultSelection,
            ModelSelectionLookup selectionLookup) {
        IncomingMessage requiredIncomingMessage = Objects.requireNonNull(incomingMessage, "Incoming message must not be null");
        ModelSelection requiredDefaultSelection = Objects.requireNonNull(defaultSelection, "Default model selection must not be null");
        ModelSelectionLookup requiredSelectionLookup = Objects.requireNonNull(selectionLookup, "Model selection lookup must not be null");
        try {
            MessageReceipt receipt = transactionTemplate.execute(status -> receiveInTransaction(requiredIncomingMessage,
                    requiredDefaultSelection, requiredSelectionLookup));
            return Objects.requireNonNull(receipt, "Message receipt must not be null");
        } catch (ConversationStoreFailure | QueueFullException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private MessageReceipt receiveInTransaction(
            IncomingMessage incomingMessage,
            ModelSelection defaultSelection,
            ModelSelectionLookup selectionLookup) {
        String sourceType = incomingMessage.source().storageValue();
        intakeCapacity.lock();
        lockSourceIdentity(sourceType, incomingMessage.sourceMessageId());
        String contentHash = contentHash(incomingMessage.participantId(), incomingMessage.message());
        Optional<StoredSourceMessage> existing = findSourceMessage(sourceType, incomingMessage.sourceMessageId());
        if (existing.isPresent()) {
            StoredSourceMessage stored = existing.orElseThrow();
            if (stored.contentHash().equals(contentHash)) {
                return new MessageReceipt(new SessionId(stored.sessionId().toString()), new MessageJobId(stored.messageJobId().toString()));
            }
            throw new MessageConflictException();
        }
        if (!intakeCapacity.hasCapacity()) {
            throw new QueueFullException();
        }
        StoredSession session = lockOrCreateSession(sourceType, incomingMessage.sessionKey(), defaultSelection);
        ModelSelection selection = selectionLookup.findSelection(session.selectedModel())
                .orElseThrow(() -> ConversationStoreFailure.contract(
                        new IllegalStateException("Selected configured model is unavailable")));
        long sequence = allocateSequence(session.sessionId());
        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        insertSourceMessage(sourceType, incomingMessage.sourceMessageId(), session.sessionId(), sequence, contentHash, now);
        insertUserSessionMessage(session.sessionId(), sequence, now);
        insertUserMessage(incomingMessage, session.sessionId(), sequence);
        insertMessageJob(jobId, session.sessionId(), sequence, selection, now);
        SkillCommand.parse(incomingMessage.message()).ifPresent(command ->
                mapper.insertSkillCommand(jobId, session.sessionId(), command.requestedName(), command.question()));
        return new MessageReceipt(new SessionId(session.sessionId().toString()), new MessageJobId(jobId.toString()));
    }

    private void lockSourceIdentity(String sourceType, String sourceMessageId) {
        ByteBuffer buffer = ByteBuffer.wrap(sha256(sourceType, sourceMessageId));
        int firstLockKey = buffer.getInt();
        int secondLockKey = buffer.getInt();
        mapper.lockSourceIdentity(firstLockKey, secondLockKey);
    }

    private StoredSession lockOrCreateSession(String sourceType, String sessionKey, ModelSelection defaultSelection) {
        UUID generatedSessionId = UUID.randomUUID();
        mapper.insertSessionIfAbsent(generatedSessionId, sourceType, sessionKey, defaultSelection.model(), timestamp(clock.instant()));
        return mapper.lockSession(sourceType, sessionKey).stream()
                .map(row -> new StoredSession(row.sessionId(), row.selectedModel()))
                .findFirst().orElseThrow(() -> ConversationStoreFailure.contract(
                        new IllegalStateException("Conversation session was not created")));
    }

    private Optional<StoredSourceMessage> findSourceMessage(String sourceType, String sourceMessageId) {
        return mapper.findSourceMessage(sourceType, sourceMessageId).stream()
                .map(row -> new StoredSourceMessage(row.sessionId(), row.contentHash(), row.messageJobId()))
                .findFirst();
    }

    private long allocateSequence(UUID sessionId) {
        Long sequence = mapper.allocateSequence(sessionId).stream().findFirst().orElse(null); // cs-allow mapper uses nullable empty RETURNING result
        return Objects.requireNonNull(sequence, "Session sequence must not be null");
    }

    private void insertSourceMessage(String sourceType, String sourceMessageId, UUID sessionId, long sequence, String contentHash, Instant createdAt) {
        mapper.insertSourceMessage(sourceType, sourceMessageId, sessionId, sequence, contentHash, timestamp(createdAt));
    }

    private void insertUserSessionMessage(UUID sessionId, long sequence, Instant createdAt) {
        mapper.insertUserSessionMessage(sessionId, sequence, timestamp(createdAt));
    }

    private void insertUserMessage(IncomingMessage message, UUID sessionId, long sequence) {
        mapper.insertUserMessage(sessionId, sequence, message.participantId(), message.source().storageValue(),
                message.sourceMessageId(), message.message());
    }

    private void insertMessageJob(UUID jobId, UUID sessionId, long sequence, ModelSelection selection, Instant createdAt) {
        mapper.insertMessageJob(jobId, sessionId, sequence, selection.model(), selection.fingerprint(), timestamp(createdAt));
    }

    @Override
    public Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration) {
        return claimNext(workerId, leaseDuration, Set.of());
    }

    @Override
    public Optional<MessageWorkClaim> claimNext(
            String workerId,
            Duration leaseDuration,
            Set<SessionId> excludedSessionIds) {
        Assert.hasText(workerId, "Worker ID must not be blank");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        Set<SessionId> requiredExcludedSessionIds = Objects.requireNonNull(
                excludedSessionIds, "Excluded session IDs must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Lease duration must be positive");
        try {
            Optional<MessageWorkClaim> claim = transactionTemplate.execute(status -> mapper.claimNext(
                    workerId, positiveLeaseMilliseconds(requiredLeaseDuration), requiredExcludedSessionIds.stream()
                            .map(SessionId::value)
                            .toArray(String[]::new)).stream()
                    .map(row -> new MessageWorkClaim(
                            new MessageJobId(row.messageJobId().toString()), new SessionId(row.sessionId().toString()),
                            new ModelSelection(row.modelKey(), row.modelFingerprint()), row.workerId(), row.claimNumber(),
                            row.claimedAt().toInstant(), row.lockedUntil().toInstant()))
                    .findFirst());
            return Objects.requireNonNull(claim, "Claim result must not be null");
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<ModelSelection> selectModel(SessionId sessionId, ModelSelection selection) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        ModelSelection requiredSelection = Objects.requireNonNull(selection, "Model selection must not be null");
        try {
            return transactionTemplate.execute(status -> mapper.lockSessionById(UUID.fromString(requiredSessionId.value())).stream()
                    .findFirst().map(session -> {
                        mapper.selectModel(session.sessionId(), requiredSelection.model());
                        return requiredSelection;
                    }));
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
            return mapper.extendClaim(positiveLeaseMilliseconds(requiredLeaseDuration), messageJobId(requiredClaim),
                    sessionId(requiredClaim), requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
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
    public ConversationHistory loadContextHistory(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session ID must not be null");
        try {
            return Objects.requireNonNull(historyTransactionTemplate.execute(status ->
                    new ConversationHistory(loadHistoryInSingleSnapshot(sessionId), loadCommands(sessionId))));
        } catch (InvalidStoredNativeHistoryException exception) {
            throw ConversationStoreFailure.invalidHistory(exception);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private Map<MessageJobId, SkillCommandState> loadCommands(SessionId sessionId) {
        return mapper.loadSkillCommands(UUID.fromString(sessionId.value())).stream().map(PostgresConversationStore::storedCommand)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(SkillCommandState::jobId, state -> state));
    }

    private static SkillCommandState storedCommand(PostgresConversationMapper.SkillCommandRow row) {
        return new SkillCommandState(new MessageJobId(row.messageJobId().toString()),
                new SkillCommand(row.requestedName(), row.question()),
                SkillCommandState.Disposition.valueOf(row.disposition()),
                Optional.ofNullable(row.resolutionSequence()).map(SessionSequence::new));
    }

    @Override
    public UUID startSkillCommand(MessageWorkClaim claim) {
        return inTransaction(() -> {
            requireLiveClaim(claim);
            rejectCancellation(claim);
            PostgresConversationMapper.SkillCommandRow command = mapper.loadSkillCommand(messageJobId(claim), sessionId(claim))
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Job is not an explicit skill command"));
            Assert.isTrue(command.disposition().equals("PENDING"), "Only pending commands start a load");
            return mapper.startSkillLoad(UUID.randomUUID(), messageJobId(claim), sessionId(claim), "EXPLICIT",
                    SkillReadReceipt.resource("skill://" + command.requestedName()), claim.workerId(), claim.claimNumber(),
                    null, null).getFirst();
        });
    }

    @Override
    public JobStatus resolveSkillCommand(MessageWorkClaim claim, SkillReadResult result, Instant createdAt) {
        Objects.requireNonNull(claim, "Claim must not be null");
        Objects.requireNonNull(result, "Read result must not be null");
        Objects.requireNonNull(createdAt, "Creation time must not be null");
        return inTransaction(() -> {
            PostgresConversationMapper.CancellationRow job = mapper.lockCancellationJob(messageJobId(claim))
                    .stream().findFirst().orElseThrow(StaleWorkClaimException::new);
            Assert.isTrue(job.sessionId().equals(sessionId(claim)), "Claim session must match job");
            SkillCommandState command = mapper.loadSkillCommand(messageJobId(claim), sessionId(claim)).stream()
                    .findFirst().map(PostgresConversationStore::storedCommand)
                    .orElseThrow(() -> new IllegalArgumentException("Job is not an explicit skill command"));
            if (command.disposition() != SkillCommandState.Disposition.PENDING
                    && JobStatus.valueOf(job.status()).isTerminal()) {
                return JobStatus.valueOf(job.status());
            }
            requireLiveClaim(claim);
            rejectCancellation(claim);
            if (command.disposition() == SkillCommandState.Disposition.ACCEPTED) {
                return JobStatus.WORKING;
            }
            boolean accepted = result instanceof SkillReadResult.Success;
            UUID attemptId = startSkillCommand(claim);
            String requestedUri = "skill://" + command.command().requestedName();
            SkillReadReceipt receipt = result instanceof SkillReadResult.Success success
                    ? SkillReadReceipt.succeeded(attemptId, requestedUri, success.resource().uri(), success.resource().sha256())
                    : SkillReadReceipt.failed(attemptId, requestedUri, ((SkillReadResult.Failure) result).code());
            MessageData data = accepted ? new SkillData(((SkillReadResult.Success) result).resource())
                    : new RuntimeData(JobFailure.SKILL_UNAVAILABLE.name(), JobFailure.SKILL_UNAVAILABLE.message());
            return append(claim, new MessageBatch(List.of(data),
                    accepted ? JobUpdate.KEEP_WORKING : JobUpdate.COMPLETE, Optional.empty(), Optional.empty(),
                    accepted ? Optional.empty() : Optional.of(JobFailure.SKILL_UNAVAILABLE), ConversationStore.CompletionDisposition.NONE),
                    createdAt, Optional.of(receipt));
        });
    }

    @Override
    public Optional<List<SessionMessage>> loadHistoryPage(SessionId sessionId, long afterSequence, int limit) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        Assert.isTrue(afterSequence >= 0 && limit > 0,
                "History pagination must be nonnegative with a positive limit");
        try {
            List<Optional<SessionMessage>> rows = mapper.loadHistoryPage(UUID.fromString(requiredSessionId.value()), afterSequence, limit)
                    .stream().map(row -> storedPagedHistoryMessage(requiredSessionId, row)).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(rows.stream().flatMap(value -> value.stream()).toList());
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
            PostgresConversationMapper.ModelRouteRow route = mapper.lockModelRoute(
                    messageJobId(requiredClaim), sessionId(requiredClaim)).stream()
                    .findFirst().orElseThrow(StaleWorkClaimException::new);
            Optional<String> storedRoute = Optional.ofNullable(route.modelRouteId());
            if (storedRoute.isEmpty()) {
                mapper.bindModelRoute(requiredRouteId.value(), messageJobId(requiredClaim), sessionId(requiredClaim));
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
            Map<SessionSequence, ModelContinuation> continuations = mapper.loadContinuations(messageJobId(requiredClaim),
                    sessionId(requiredClaim), requiredClaim.workerId(), requiredClaim.claimNumber()).stream()
                    .map(row -> Map.entry(new SessionSequence(row.assistantSequence()),
                            new ModelContinuation(new ModelRouteId(row.modelRouteId()), row.format(), row.payload())))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> entry.getKey(), entry -> entry.getValue()));
            return Map.copyOf(continuations);
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<ContextUsageCheckpoint> loadUsageCheckpoint(
            SessionId sessionId,
            ModelSelection selection,
            ModelDescriptor model,
            String requestShapeFingerprint,
            long compactGeneration) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        ModelSelection requiredSelection = Objects.requireNonNull(selection, "Model selection must not be null");
        ModelDescriptor requiredModel = Objects.requireNonNull(model, "Model descriptor must not be null");
        Assert.hasText(requestShapeFingerprint, "Request shape fingerprint must not be blank");
        Assert.isTrue(compactGeneration >= 0, "Compact generation must not be negative");
        try {
            return mapper.loadUsageCheckpoint(UUID.fromString(requiredSessionId.value()), requiredSelection.model(),
                    requiredSelection.fingerprint(), requiredModel.routeId().value(),
                    requiredModel.modelId(), requestShapeFingerprint, compactGeneration).stream().findFirst()
                    .map(row -> new ContextUsageCheckpoint(requiredModel, row.modelCallOrdinal(),
                            new SessionSequence(row.responseSequence()), row.promptTokens(), row.completionTokens(),
                            row.totalTokens(), requestShapeFingerprint, compactGeneration, row.createdAt().toInstant()));
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<ContextCompaction> loadCompaction(SessionId sessionId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        try {
            return mapper.loadCompaction(UUID.fromString(requiredSessionId.value())).stream().findFirst()
                    .map(row -> new ContextCompaction(row.generation(), new MessageJobId(row.messageJobId().toString()),
                            ContextCompaction.Reason.valueOf(row.reason()), new ContextSummary(row.summary()),
                            new SessionSequence(row.coveredThrough()), new ModelDescriptor(
                            new ModelRouteId(row.modelRouteId()), row.modelId(), row.contextWindowTokens()),
                            row.requestShapeFingerprint(), row.estimateBeforeTokens(), row.estimateAfterTokens(),
                            row.createdAt().toInstant()));
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public boolean hasOverflowCompaction(MessageJobId messageJobId) {
        MessageJobId requiredMessageJobId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        try {
            return Boolean.TRUE.equals(mapper.hasOverflowCompaction(UUID.fromString(requiredMessageJobId.value())));
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void compact(MessageWorkClaim claim, CompactionData compaction, Instant createdAt) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        CompactionData requiredCompaction = Objects.requireNonNull(compaction, "Context compaction data must not be null");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Context compaction creation time must not be null");
        inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            rejectCancellation(requiredClaim);
            UUID sessionId = sessionId(requiredClaim);
            mapper.insertCompaction(sessionId, requiredCompaction.generation(), messageJobId(requiredClaim),
                    requiredCompaction.reason().name(), requiredCompaction.summary(), requiredCompaction.coveredThrough().value(),
                    requiredCompaction.model().routeId().value(), requiredCompaction.model().modelId(),
                    requiredCompaction.model().contextWindowTokens(), requiredCompaction.requestShapeFingerprint(),
                    requiredCompaction.estimateBeforeTokens(), requiredCompaction.estimateAfterTokens(), timestamp(requiredCreatedAt));
            mapper.deleteCoveredContinuations(messageJobId(requiredClaim), sessionId, requiredCompaction.coveredThrough().value());
            updateProgress(requiredClaim, new JobProgress(JobPhase.PREPARING, clock.instant(), Optional.empty()));
            return Boolean.TRUE;
        });
    }

    private List<SessionMessage> loadHistoryInSingleSnapshot(SessionId sessionId) {
        UUID parsedSessionId = UUID.fromString(sessionId.value());
        List<SessionMessage> messages = new ArrayList<>();
        messages.addAll(loadUserMessages(parsedSessionId));
        messages.addAll(loadAssistantToolCallsMessages(parsedSessionId));
        messages.addAll(loadToolObservations(parsedSessionId));
        messages.addAll(loadAssistantMessages(parsedSessionId));
        messages.addAll(loadRuntimeMessages(parsedSessionId));
        messages.addAll(mapper.loadSkillMessages(parsedSessionId).stream().map(row -> (SessionMessage) new SkillMessage(
                sessionId, new SessionSequence(row.sequence()), Optional.of(new MessageJobId(row.messageJobId().toString())),
                row.createdAt().toInstant(), MessageRole.SKILL, new SkillResource(row.uri(), row.sha256(), row.content()))).toList());
        messages.sort(Comparator.comparingLong(message -> message.sequence().value()));
        return List.copyOf(messages);
    }

    private Optional<SessionMessage> storedPagedHistoryMessage(
            SessionId sessionId, PostgresConversationMapper.HistoryPageRow row) {
        Optional<Long> sequence = Optional.ofNullable(row.sequence());
        if (sequence.isEmpty()) {
            return Optional.empty();
        }
        try {
            SessionSequence messageSequence = new SessionSequence(sequence.orElseThrow());
            Instant createdAt = row.createdAt().toInstant();
            MessageRole role = MessageRole.valueOf(row.role());
            return Optional.of(switch (role) {
                case USER -> new UserMessage(sessionId, messageSequence, Optional.of(new MessageJobId(
                        row.userMessageJobId().toString())), createdAt, MessageRole.USER,
                        row.participantId(), row.userMessage());
                case ASSISTANT -> new AssistantMessage(sessionId, messageSequence, storedPageMessageJobId(row), createdAt,
                        MessageRole.ASSISTANT, row.assistantMessage());
                case ASSISTANT_TOOL_CALLS -> new AssistantToolCallsMessage(sessionId, messageSequence, storedPageMessageJobId(row),
                        createdAt, MessageRole.ASSISTANT_TOOL_CALLS,
                        Optional.ofNullable(row.assistantToolCallsMessage()), toolRequests(row.calls()));
                case TOOL -> new ToolObservation(sessionId, messageSequence, storedPageMessageJobId(row), createdAt,
                        MessageRole.TOOL, new ToolCallId(row.toolCallId()), row.toolName(), structuredValue(row.output()));
                case RUNTIME -> new RuntimeMessage(sessionId, messageSequence, storedPageMessageJobId(row), createdAt,
                        MessageRole.RUNTIME, row.code(), row.runtimeMessage());
                case SKILL -> new SkillMessage(sessionId, messageSequence, storedPageMessageJobId(row), createdAt,
                        MessageRole.SKILL, new SkillResource(row.uri(), row.sha256(), row.content()));
            });
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private static Optional<MessageJobId> storedPageMessageJobId(PostgresConversationMapper.HistoryPageRow row) {
        return Optional.of(new MessageJobId(row.eventMessageJobId().toString()));
    }

    @Override
    public JobControl loadJobControl(MessageWorkClaim claim) {
        return inTransaction(() -> {
            requireLiveClaim(claim);
            rejectCancellation(claim);
            return jobControl(claim);
        });
    }

    private JobControl jobControl(MessageWorkClaim claim) {
        PostgresConversationMapper.ControlRow row = mapper.loadJobControl(messageJobId(claim));
        Optional<WaitingChoice> waiting = mapper.loadWaiting(sessionId(claim)).stream().findFirst()
                .map(value -> new WaitingChoice(new MessageJobId(value.originJob().toString()),
                        new SessionSequence(value.responseSequence()), value.generation()));
        JobControl.Stage stage = JobControl.Stage.valueOf(row.controlStage());
        if (stage == JobControl.Stage.INTENT && waiting.isEmpty() && !row.intentAttempted()) {
            stage = JobControl.Stage.EXECUTION;
        }
        requireLiveClaim(claim);
        return new JobControl(JobControl.Mode.valueOf(row.executionMode()), stage,
                new UserMessage(claim.sessionId(), new SessionSequence(row.userMessageSequence()),
                        Optional.of(claim.messageJobId()), row.createdAt().toInstant(), MessageRole.USER,
                        row.participantId(), row.message()), waiting, row.ordinaryCalls(),
                row.intentAttempted(), row.wrapAttempted());
    }

    @Override
    public ModelCallReservationResult reserveModelCall(
            MessageWorkClaim claim, ModelCallPurpose purpose, int maxExecutionCalls, Instant now) {
        Objects.requireNonNull(purpose, "Model call purpose must not be null");
        Assert.isTrue(maxExecutionCalls > 0, "Maximum ordinary calls must be positive");
        Objects.requireNonNull(now, "Current time must not be null");
        return inTransaction(() -> {
            requireLiveClaim(claim);
            rejectCancellation(claim);
            JobControl control = jobControl(claim);
            if ((purpose == ModelCallPurpose.INTENT && control.intentAttempted())
                    || (purpose == ModelCallPurpose.WRAP_UP && control.wrapAttempted())) {
                return new ModelCallReservationResult.AlreadyAttempted();
            }
            if (purpose.ordinary() && (control.ordinaryCallCount() >= maxExecutionCalls
                    || control.stage() == JobControl.Stage.WRAP_UP)) {
                return new ModelCallReservationResult.BudgetExhausted();
            }
            PostgresConversationMapper.ReservationRow reserved = mapper.reserveModelCall(messageJobId(claim),
                    purpose.name(), purpose.ordinary(), timestamp(now)).getFirst();
            requireLiveClaim(claim);
            return new ModelCallReservationResult.Reserved(reserved.totalOrdinal(), reserved.executionOrdinal());
        });
    }

    @Override
    public void recordModelUsage(MessageWorkClaim claim, ModelCallObservation observation) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        ModelCallObservation requiredObservation = Objects.requireNonNull(observation, "Model call observation must not be null");
        Optional<CacheTokenUsage> usage = requiredObservation.cacheUsage();
        long inputTokens = usage.isPresent() ? usage.orElseThrow().inputTokens() : 0;
        long cachedInputTokens = usage.isPresent() ? usage.orElseThrow().cachedInputTokens() : 0;
        inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            mapper.recordModelUsage(messageJobId(requiredClaim), requiredObservation.totalOrdinal(), usage.isPresent(),
                    inputTokens, cachedInputTokens);
            requireLiveClaim(requiredClaim);
            return true;
        });
    }

    @Override
    public JobControl resolveFollowUp(MessageWorkClaim claim, WaitingChoice expected, FollowUpIntent intent, Instant now) {
        Objects.requireNonNull(expected, "Expected waiting choice must not be null");
        Objects.requireNonNull(intent, "Follow-up intent must not be null");
        return inTransaction(() -> {
            requireLiveClaim(claim);
            rejectCancellation(claim);
            JobControl control = jobControl(claim);
            if (control.stage() != JobControl.Stage.INTENT
                    || Objects.nonNull(mapper.loadJobControl(messageJobId(claim)).followUpIntent())) {
                return control;
            }
            if (!control.waiting().filter(expected::equals).isPresent()) {
                throw new StaleWorkClaimException();
            }
            if (intent != FollowUpIntent.CLARIFY) {
                int consumed = mapper.consumeWaiting(sessionId(claim), UUID.fromString(expected.originJob().value()),
                        expected.responseSequence().value(), expected.generation());
                if (consumed != 1) {
                    throw new StaleWorkClaimException();
                }
            }
            mapper.resolveFollowUp(messageJobId(claim), intent == FollowUpIntent.DELIVER ? "REPORT_ONLY" : "NORMAL",
                    intent == FollowUpIntent.CLARIFY ? "INTENT" : "EXECUTION", intent.name());
            return jobControl(claim);
        });
    }

    @Override
    public JobControl enterWrapUp(MessageWorkClaim claim, Instant now) {
        return inTransaction(() -> {
            requireLiveClaim(claim);
            rejectCancellation(claim);
            mapper.enterWrapUp(messageJobId(claim));
            return jobControl(claim);
        });
    }

    @Override
    public void updateProgress(MessageWorkClaim claim, JobProgress progress) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        JobProgress requiredProgress = Objects.requireNonNull(progress, "Job progress must not be null");
        inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            if (requiredProgress.phase() == JobPhase.TOOL_EXECUTION) {
                rejectCancellation(requiredClaim);
            }
            int rows = mapper.updateProgress(messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber(), requiredProgress.phase().name(),
                    timestamp(requiredProgress.startedAt()), requiredProgress.toolName().orElse(null));
            if (rows != 1) {
                throw new StaleWorkClaimException();
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public JobStatus append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt) {
        Assert.isTrue(batch.messages().stream().noneMatch(SkillData.class::isInstance),
                "Skill events require command resolution");
        return append(claim, batch, createdAt, Optional.empty());
    }

    private JobStatus append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt,
            Optional<SkillReadReceipt> commandReceipt) {
        boolean resolvingSkill = commandReceipt.isPresent();
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        MessageBatch requiredBatch = Objects.requireNonNull(batch, "Message batch must not be null");
        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "Message creation time must not be null");
        return inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            boolean cancelled = mapper.isCancellationRequested(messageJobId(requiredClaim));
            if (resolvingSkill) {
                rejectCancellation(requiredClaim);
                Assert.isTrue(requiredBatch.messages().size() == 1 && requiredBatch.continuation().isEmpty()
                        && requiredBatch.usageCheckpoint().isEmpty(), "Skill resolution must be a standalone batch");
            }
            Assert.isTrue(requiredBatch.jobUpdate() != JobUpdate.CANCEL || cancelled,
                    "Cancellation finalization requires a recorded request");
            Assert.isTrue(requiredBatch.jobUpdate() != JobUpdate.CANCEL
                            || (requiredBatch.continuation().isEmpty() && requiredBatch.failure().isEmpty()),
                    "Cancellation finalization must not retain continuation or failure");
            UUID sessionId = sessionId(requiredClaim);
            Optional<SessionSequence> continuationSequence = Optional.empty();
            Optional<SessionSequence> responseSequence = Optional.empty();
            List<ToolCallData> actualCalls = List.of();
            int toolOrdinal = 0;
            for (MessageData message : requiredBatch.messages()) {
                if (cancelled && !(message instanceof AssistantToolCallsData) && !(message instanceof ToolObservationData)) {
                    continue;
                }
                long sequence = allocateSequence(sessionId);
                requireLiveClaim(requiredClaim);
                if (message instanceof AssistantData assistant) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "ASSISTANT", requiredCreatedAt);
                    mapper.insertAssistantMessage(sessionId, sequence, assistant.message());
                    responseSequence = Optional.of(new SessionSequence(sequence));
                } else if (message instanceof AssistantToolCallsData assistantToolCalls) {
                    actualCalls = assistantToolCalls.calls();
                    toolOrdinal = 0;
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "ASSISTANT_TOOL_CALLS", requiredCreatedAt);
                    mapper.insertAssistantToolCalls(sessionId, sequence, assistantToolCalls.message().orElse(null),
                            json(storedToolCalls(assistantToolCalls.calls()))); // cs-allow nullable database column represents optional assistant text
                    continuationSequence = Optional.of(new SessionSequence(sequence));
                    responseSequence = Optional.of(new SessionSequence(sequence));
                } else if (message instanceof ToolObservationData observation) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "TOOL", requiredCreatedAt);
                    mapper.insertToolObservation(sessionId, sequence, observation.toolCallId().value(), observation.toolName(),
                            json(observation.output()));
                    if (observation.artifactReceipt().isPresent()) {
                        Assert.isTrue(toolOrdinal < actualCalls.size(), "Artifact requires matching assistant tool call");
                        artifactStore.associate(requiredClaim, observation, sequence, actualCalls.get(toolOrdinal), toolOrdinal);
                    }
                    if (observation.skillReadReceipt().isPresent()) {
                        Assert.isTrue(toolOrdinal < actualCalls.size()
                                && actualCalls.get(toolOrdinal).toolCallId().equals(observation.toolCallId())
                                && actualCalls.get(toolOrdinal).toolName().equals(observation.toolName()),
                                "Skill receipt requires its matching assistant call");
                        associateSkillLoad(requiredClaim, observation.skillReadReceipt().orElseThrow(), "AUTOMATIC",
                                toolOrdinal, sequence);
                    }
                    toolOrdinal++;
                } else if (message instanceof RuntimeData runtime) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "RUNTIME", requiredCreatedAt);
                    mapper.insertRuntimeMessage(sessionId, sequence, runtime.code(), runtime.message());
                } else if (message instanceof SkillData skill) {
                    insertSessionMessage(sessionId, sequence, messageJobId(requiredClaim), "SKILL", requiredCreatedAt);
                    mapper.insertSkillMessage(sessionId, sequence, skill.resource().uri(), skill.resource().sha256(),
                            skill.resource().content());
                }
                if (resolvingSkill) {
                    Assert.isTrue(mapper.resolveSkillCommand(messageJobId(requiredClaim), sessionId,
                            message instanceof SkillData ? "ACCEPTED" : "REJECTED", sequence) == 1,
                            "Skill command must be pending");
                    associateSkillLoad(requiredClaim, commandReceipt.orElseThrow(), "EXPLICIT", 0, sequence);
                    if (message instanceof SkillData) {
                        mapper.clearWaiting(sessionId);
                        mapper.resolveFollowUp(messageJobId(requiredClaim), "NORMAL", "EXECUTION", "NEW_QUESTION");
                    }
                }
            }
            if (cancelled) {
                insertCancellationMessage(sessionId, messageJobId(requiredClaim), requiredCreatedAt);
            }
            if (requiredBatch.usageCheckpoint().isPresent() && (!cancelled || responseSequence.isPresent())) {
                ConversationStore.UsageCheckpointData checkpoint = requiredBatch.usageCheckpoint().orElseThrow();
                SessionSequence boundary = responseSequence.orElseThrow();
                // Allocation holds the session row lock: no intake can enter between this proof and commit.
                if (boundary.value() == checkpoint.requestBoundary().value() + 1) {
                    mapper.insertUsageCheckpoint(sessionId, boundary.value(), checkpoint.model().routeId().value(),
                            checkpoint.model().modelId(), checkpoint.selection().model(), checkpoint.selection().fingerprint(),
                            checkpoint.modelCallOrdinal(), checkpoint.promptTokens(),
                            checkpoint.completionTokens(), checkpoint.totalTokens(), checkpoint.requestShapeFingerprint(),
                            checkpoint.compactGeneration(), timestamp(requiredCreatedAt));
                }
            }
            if (!cancelled && requiredBatch.continuation().isPresent()) {
                SessionSequence assistantSequence = continuationSequence.orElseThrow();
                ModelContinuation continuation = requiredBatch.continuation().orElseThrow();
                mapper.insertContinuation(messageJobId(requiredClaim), sessionId, assistantSequence.value(),
                        continuation.modelRouteId().value(), continuation.format(), continuation.payload());
            }
            if (cancelled) {
                mapper.deleteContinuations(messageJobId(requiredClaim), sessionId);
                requireLiveClaim(requiredClaim);
                mapper.cancelJob(messageJobId(requiredClaim));
                return JobStatus.CANCELLED;
            } else if (requiredBatch.jobUpdate() == JobUpdate.COMPLETE) {
                mapper.deleteContinuations(messageJobId(requiredClaim), sessionId);
                if (requiredBatch.completionDisposition() == CompletionDisposition.WAITING) {
                    JobControl control = jobControl(requiredClaim);
                    if (control.stage() == JobControl.Stage.WRAP_UP) {
                        mapper.recordWaiting(sessionId, messageJobId(requiredClaim), responseSequence.orElseThrow().value());
                    } else {
                        Assert.isTrue(control.waiting().isPresent(), "Clarification must preserve an existing waiting choice");
                    }
                }
                mapper.setCompletionDisposition(messageJobId(requiredClaim), requiredBatch.completionDisposition().name());
                completeJob(requiredClaim, clock.instant(), requiredBatch.failure());
                boolean successfulAnswer = requiredBatch.completionDisposition() == CompletionDisposition.ANSWER
                        && requiredBatch.failure().isEmpty()
                        && requiredBatch.messages().stream().anyMatch(AssistantData.class::isInstance);
                if (successfulAnswer) {
                    artifactStore.release(messageJobId(requiredClaim));
                }
            } else {
                int rows = mapper.finishWorkingBatch(messageJobId(requiredClaim), sessionId,
                        requiredClaim.workerId(), requiredClaim.claimNumber(), timestamp(clock.instant()),
                        requiredBatch.failure().map(JobFailure::name).orElse(null));
                if (rows != 1) {
                    throw new StaleWorkClaimException();
                }
            }
            return requiredBatch.jobUpdate() == JobUpdate.COMPLETE ? JobStatus.DONE : JobStatus.WORKING;
        });
    }

    @Override
    public JobStatus appendAndScheduleRetry(
            MessageWorkClaim claim, MessageBatch batch, Duration retryDelay, JobFailure failure, Instant createdAt) {
        Objects.requireNonNull(batch, "Message batch must not be null");
        Objects.requireNonNull(retryDelay, "Retry delay must not be null");
        Objects.requireNonNull(failure, "Job failure must not be null");
        Assert.isTrue(batch.jobUpdate() == JobUpdate.KEEP_WORKING
                        && batch.completionDisposition() == CompletionDisposition.NONE,
                "Retry recovery must keep the tool batch working");
        Assert.isTrue(batch.messages().getFirst() instanceof AssistantToolCallsData,
                "Retry recovery requires a complete tool batch");
        Assert.isTrue(!retryDelay.isNegative(), "Retry delay must not be negative");
        return inTransaction(() -> {
            JobStatus status = append(claim, batch, createdAt);
            if (status == JobStatus.CANCELLED) {
                return status;
            }
            if (!scheduleRetry(claim, retryDelay, failure)) {
                // A rejected fence must roll back the history inserted in this same transaction.
                throw new StaleWorkClaimException();
            }
            return JobStatus.RETRY;
        });
    }

    private void associateSkillLoad(MessageWorkClaim claim, SkillReadReceipt receipt, String source,
            int toolOrdinal, long sequence) {
        int associated = mapper.commitSkillLoad(receipt, messageJobId(claim), sessionId(claim), claim.workerId(),
                claim.claimNumber(), source, toolOrdinal, sequence,
                receipt.outcome() == SkillReadReceipt.Outcome.SUCCEEDED ? "COMMITTED_SUCCESS" : "COMMITTED_FAILURE",
                receipt.sha256().orElse(null), receipt.errorCode().orElse(null));
        Assert.isTrue(associated == 1, "Skill receipt must belong to this live attempt and history event");
    }

    @Override
    public boolean scheduleRetry(MessageWorkClaim claim, Duration retryDelay, JobFailure failure) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        Duration requiredDelay = Objects.requireNonNull(retryDelay, "Retry delay must not be null");
        JobFailure requiredFailure = Objects.requireNonNull(failure, "Job failure must not be null");
        Assert.isTrue(!requiredDelay.isNegative(), "Retry delay must not be negative");
        return inTransaction(() -> {
            if (mapper.requireLiveClaim(messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber()) != 1) {
                return false;
            }
            rejectCancellation(requiredClaim);
            return mapper.scheduleRetry(requiredDelay.toMillis(), messageJobId(requiredClaim), sessionId(requiredClaim),
                    requiredClaim.workerId(), requiredClaim.claimNumber(), requiredFailure.name()) == 1;
        });
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
        int rows = mapper.requireLiveClaim(messageJobId(claim), sessionId(claim), claim.workerId(), claim.claimNumber());
        if (rows != 1) {
            throw new StaleWorkClaimException();
        }
    }

    private void rejectCancellation(MessageWorkClaim claim) {
        if (mapper.isCancellationRequested(messageJobId(claim))) {
            throw new JobCancellationRequestedException();
        }
    }

    @Override
    public boolean isCancellationRequested(MessageWorkClaim claim) {
        MessageWorkClaim requiredClaim = Objects.requireNonNull(claim, "Message work claim must not be null");
        return inTransaction(() -> {
            requireLiveClaim(requiredClaim);
            return mapper.isCancellationRequested(messageJobId(requiredClaim));
        });
    }

    @Override
    public Optional<MessageJobProjection> requestCancellation(MessageJobId messageJobId) {
        MessageJobId requiredId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        return inTransaction(() -> {
            UUID jobId = UUID.fromString(requiredId.value());
            Optional<PostgresConversationMapper.CancellationRow> found = mapper.lockCancellationJob(jobId).stream().findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            PostgresConversationMapper.CancellationRow job = found.orElseThrow();
            cancelLockedJob(job);
            return readJob(requiredId);
        });
    }

    @Override
    public int requestSessionCancellation(SessionId sessionId) {
        SessionId requiredId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        return inTransaction(() -> {
            // Intake takes this same lock before creating jobs. Lock every snapshot job before
            // allocating session sequences, matching worker/HTTP cancellation's job -> session order.
            intakeCapacity.lock();
            List<PostgresConversationMapper.CancellationRow> jobs =
                    mapper.lockSessionCancellationJobs(UUID.fromString(requiredId.value()));
            for (PostgresConversationMapper.CancellationRow job : jobs) {
                cancelLockedJob(job);
            }
            return jobs.size();
        });
    }

    private void cancelLockedJob(PostgresConversationMapper.CancellationRow job) {
        JobStatus currentStatus = JobStatus.valueOf(job.status());
        if (!currentStatus.isTerminal()) {
            mapper.requestCancellation(job.messageJobId());
            if (currentStatus != JobStatus.WORKING) {
                insertCancellationMessage(job.sessionId(), job.messageJobId(), clock.instant());
                mapper.deleteContinuations(job.messageJobId(), job.sessionId());
                mapper.cancelJob(job.messageJobId());
            }
        }
    }

    private void insertCancellationMessage(UUID sessionId, UUID jobId, Instant createdAt) {
        long sequence = allocateSequence(sessionId);
        insertSessionMessage(sessionId, sequence, jobId, "RUNTIME", createdAt);
        mapper.insertRuntimeMessage(sessionId, sequence, JobCancellation.CODE, JobCancellation.MESSAGE);
    }

    private void insertSessionMessage(UUID sessionId, long sequence, UUID jobId, String role, Instant createdAt) {
        mapper.insertSessionMessage(sessionId, sequence, jobId, role, timestamp(createdAt));
    }

    private void completeJob(MessageWorkClaim claim, Instant completedAt, Optional<JobFailure> failure) {
        int rows = mapper.completeJob(timestamp(completedAt), messageJobId(claim), sessionId(claim), claim.workerId(),
                claim.claimNumber(), failure.map(JobFailure::name).orElse(null));
        if (rows != 1) {
            throw new StaleWorkClaimException();
        }
    }

    private List<SessionMessage> loadUserMessages(UUID sessionId) {
        return mapper.loadUserMessages(sessionId).stream().map(row -> (SessionMessage) new UserMessage(new SessionId(sessionId.toString()),
                new SessionSequence(row.sequence()), Optional.of(new MessageJobId(row.messageJobId().toString())),
                row.createdAt().toInstant(), MessageRole.USER, row.participantId(), row.message())).toList();
    }

    private List<SessionMessage> loadToolObservations(UUID sessionId) {
        return mapper.loadToolObservations(sessionId).stream().map(row -> (SessionMessage) storedToolObservation(sessionId, row)).toList();
    }

    private List<SessionMessage> loadAssistantToolCallsMessages(UUID sessionId) {
        return mapper.loadAssistantToolCalls(sessionId).stream()
                .map(row -> (SessionMessage) storedAssistantToolCalls(sessionId, row)).toList();
    }

    private ToolObservation storedToolObservation(UUID sessionId, PostgresConversationMapper.ToolObservationRow row) {
        try {
            return new ToolObservation(new SessionId(sessionId.toString()),
                    new SessionSequence(row.sequence()), Optional.of(new MessageJobId(row.messageJobId().toString())),
                    row.createdAt().toInstant(), MessageRole.TOOL, new ToolCallId(row.toolCallId()), row.toolName(),
                    structuredValue(row.output()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private AssistantToolCallsMessage storedAssistantToolCalls(
            UUID sessionId, PostgresConversationMapper.AssistantToolCallsRow row) {
        try {
            return new AssistantToolCallsMessage(new SessionId(sessionId.toString()),
                    new SessionSequence(row.sequence()), Optional.of(new MessageJobId(row.messageJobId().toString())),
                    row.createdAt().toInstant(), MessageRole.ASSISTANT_TOOL_CALLS, Optional.ofNullable(row.message()),
                    toolRequests(row.calls()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStoredNativeHistoryException(exception);
        }
    }

    private List<SessionMessage> loadAssistantMessages(UUID sessionId) {
        return mapper.loadAssistantMessages(sessionId).stream().map(row -> (SessionMessage) new AssistantMessage(
                new SessionId(sessionId.toString()), new SessionSequence(row.sequence()),
                Optional.of(new MessageJobId(row.messageJobId().toString())), row.createdAt().toInstant(), MessageRole.ASSISTANT,
                row.message())).toList();
    }

    private List<SessionMessage> loadRuntimeMessages(UUID sessionId) {
        return mapper.loadRuntimeMessages(sessionId).stream().map(row -> (SessionMessage) new RuntimeMessage(
                new SessionId(sessionId.toString()), new SessionSequence(row.sequence()),
                Optional.of(new MessageJobId(row.messageJobId().toString())), row.createdAt().toInstant(), MessageRole.RUNTIME,
                row.code(), row.message())).toList();
    }

    @Override
    public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
        MessageJobId requiredMessageJobId = Objects.requireNonNull(messageJobId, "Message job ID must not be null");
        try {
            return mapper.readJob(UUID.fromString(requiredMessageJobId.value())).stream().findFirst()
                    .map(row -> {
                        JobStatus status = JobStatus.valueOf(row.status());
                        return new MessageJobProjection(
                                new MessageJobId(row.messageJobId().toString()), new SessionId(row.sessionId().toString()),
                                status, row.retryCount(), row.modelCalls(),
                                storedProgress(row.phase(), row.phaseStartedAt(), row.currentToolName()),
                                Optional.ofNullable(row.failureCode()).map(JobFailure::valueOf),
                                Optional.ofNullable(row.cancellationRequestedAt()).map(OffsetDateTime::toInstant),
                                storedCacheReport(status, row.modelCalls(), row.cacheUsageKnownCalls(),
                                        row.cacheUsageInputTokens(), row.cacheUsageCachedInputTokens()));
                    });
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<SessionProjection> readSession(SessionId sessionId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        try {
            return mapper.readSession(UUID.fromString(requiredSessionId.value())).stream().findFirst().map(row -> {
                Optional<UUID> storedJobId = Optional.ofNullable(row.messageJobId());
                Optional<MessageJobProjection> currentJob = Optional.empty();
                if (storedJobId.isPresent()) {
                    JobStatus status = JobStatus.valueOf(row.status());
                    currentJob = Optional.of(new MessageJobProjection(new MessageJobId(storedJobId.orElseThrow().toString()),
                            new SessionId(row.sessionId().toString()), status, row.retryCount(), row.modelCalls(),
                            storedProgress(row.phase(), row.phaseStartedAt(), row.currentToolName()),
                            Optional.ofNullable(row.failureCode()).map(JobFailure::valueOf),
                            Optional.ofNullable(row.cancellationRequestedAt()).map(OffsetDateTime::toInstant),
                            storedCacheReport(status, row.modelCalls(), row.cacheUsageKnownCalls(),
                                    row.cacheUsageInputTokens(), row.cacheUsageCachedInputTokens())));
                }
                return new SessionProjection(new SessionId(row.sessionId().toString()), row.createdAt().toInstant(), currentJob,
                        row.selectedModel());
            });
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private static Optional<JobCacheReport> storedCacheReport(
            JobStatus status,
            int requestCount,
            Integer knownUsageRequestCount,
            Long knownInputTokens,
            Long knownCachedInputTokens) {
        return Optional.ofNullable(knownUsageRequestCount)
                .map(knownCount -> JobCacheReport.from(status, requestCount, knownCount,
                        Objects.requireNonNull(knownInputTokens, "Stored input-token total must not be null"),
                        Objects.requireNonNull(knownCachedInputTokens, "Stored cached-input-token total must not be null")));
    }

    private static Optional<JobProgress> storedProgress(String phase, OffsetDateTime startedAt, String toolName) {
        return Optional.ofNullable(phase).map(value -> new JobProgress(
                JobPhase.valueOf(value), startedAt.toInstant(), Optional.ofNullable(toolName)));
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
                || exception instanceof MessageConflictException || exception instanceof ModelRouteMismatchException
                || exception instanceof JobCancellationRequestedException) {
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

    private record StoredSession(UUID sessionId, String selectedModel) {
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
