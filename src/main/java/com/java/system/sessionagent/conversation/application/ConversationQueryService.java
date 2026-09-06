package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ConversationQueryService implements ConversationQueryPort {

    private final ConversationStore conversationStore;
    private final ConversationModel conversationModel;
    private final ToolCatalog toolCatalog;
    private final ContextUsageEstimator contextUsageEstimator;

    public ConversationQueryService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog,
            ContextUsageEstimator contextUsageEstimator) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.conversationModel = Objects.requireNonNull(conversationModel, "Conversation model must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "Tool catalog must not be null");
        this.contextUsageEstimator = Objects.requireNonNull(contextUsageEstimator, "Context usage estimator must not be null");
    }

    @Override
    public Optional<MessageJobView> findJob(String messageJobId) {
        return conversationStore.readJob(new MessageJobId(messageJobId)).map(projection -> new MessageJobView(
                projection.messageJobId().value(), projection.sessionId().value(), projection.status(), projection.retryCount(),
                projection.modelCallCount()));
    }

    @Override
    public Optional<List<SessionMessage>> messages(String sessionId) {
        List<SessionMessage> messages = conversationStore.loadHistory(new SessionId(sessionId));
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.stream()
                .sorted(Comparator.comparingLong(message -> message.sequence().value())).toList());
    }

    @Override
    public Optional<SessionDetailView> session(String sessionId) {
        SessionId requiredSessionId = new SessionId(sessionId);
        return conversationStore.readSession(requiredSessionId).map(projection -> {
            ContextUsageProjection contextProjection;
            Optional<ContextCompaction> compaction;
            try (ToolSnapshot snapshot = toolCatalog.snapshot()) {
                List<SessionMessage> history = conversationStore.loadHistory(requiredSessionId).stream()
                        .sorted(Comparator.comparingLong(message -> message.sequence().value())).toList();
                compaction = conversationStore.loadCompaction(requiredSessionId);
                long generation = compaction.map(ContextCompaction::generation).orElse(0L);
                List<SessionMessage> visibleHistory = compaction.map(value -> history.stream()
                        .filter(message -> message.sequence().value() > value.coveredThrough().value()).toList()).orElse(history);
                contextProjection = new ContextUsageProjection(conversationModel.descriptor(), conversationModel.systemPrompt(),
                        snapshot.definitions(), visibleHistory, generation, compaction.map(ContextCompaction::summary));
            }
            ContextEstimate estimate = contextUsageEstimator.estimate(contextProjection,
                    conversationStore.loadUsageCheckpoint(requiredSessionId, conversationModel.descriptor(),
                            contextUsageEstimator.requestShapeFingerprint(contextProjection), contextProjection.compactGeneration()));
            return new SessionDetailView(projection.sessionId().value(), projection.createdAt(), projection.currentJob().map(this::toJobView),
                    compactionBoundary(compaction), new SessionDetailView.ContextUsageView(
                            conversationModel.descriptor().modelId(), conversationModel.descriptor().contextWindowTokens(), estimate.tokens(),
                            (double) estimate.tokens() / conversationModel.descriptor().contextWindowTokens(), estimate.basis()));
        });
    }

    private MessageJobView toJobView(ConversationStore.MessageJobProjection projection) {
        return new MessageJobView(projection.messageJobId().value(), projection.sessionId().value(), projection.status(), projection.retryCount(),
                projection.modelCallCount());
    }

    private static Optional<SessionDetailView.CompactionBoundaryView> compactionBoundary(Optional<ContextCompaction> compaction) {
        return compaction.map(value -> new SessionDetailView.CompactionBoundaryView(value.generation(), value.coveredThrough().value(),
                value.reason().name(), value.createdAt()));
    }
}
