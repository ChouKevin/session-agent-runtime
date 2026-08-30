package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.tool.domain.ToolName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

final class FakeConversationModel implements ConversationModel {

    private static final ToolName LIST_REPOSITORIES = new ToolName("list_repositories");
    private static final ToolName LIST_ENTRY_POINTS = new ToolName("codebase_list_entry_points");
    private static final ToolName LOOKUP_API_ROUTE = new ToolName("codebase_lookup_api_route");
    private final List<ModelRequest> planRequests = new ArrayList<>();
    private final List<ReplyRequest> replyRequests = new ArrayList<>();

    List<ModelRequest> requests() {
        return planRequests();
    }

    List<ModelRequest> planRequests() {
        return List.copyOf(planRequests);
    }

    List<ReplyRequest> replyRequests() {
        return List.copyOf(replyRequests);
    }

    @Override
    public ModelDecision plan(ModelRequest request, Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
        planRequests.add(request);
        UserMessage question = request.history().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .max(Comparator.comparingLong(message -> message.sequence().value())).orElseThrow();
        List<SessionMessage> jobHistory = request.history().stream()
                .filter(message -> message.messageJobId().equals(question.messageJobId())).toList();
        if (jobHistory.stream().noneMatch(message -> message instanceof ToolMessage tool && tool.toolName().equals("list_repositories"))) {
            return tool("catalog-" + question.sequence().value(), LIST_REPOSITORIES, "{}");
        }
        String text = question.message().toLowerCase(java.util.Locale.ROOT);
        boolean invalidRepositoryFeedback = jobHistory.stream().filter(FeedbackMessage.class::isInstance).map(FeedbackMessage.class::cast)
                .anyMatch(message -> message.code().equals("UNKNOWN_REPOSITORY"));
        if (text.contains("invalid repository") && !invalidRepositoryFeedback) {
            return tool("invalid-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"missing-service\",\"revision\":\"missing-revision\"}");
        }
        if (text.contains("invalid repository") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .filter(message -> message.toolName().equals("list_repositories")).count() < 2) {
            return tool("refresh-catalog-" + question.sequence().value(), LIST_REPOSITORIES, "{}");
        }
        if (text.contains("bnpl") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().isPresent())) {
            return tool("bnpl-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\"}");
        }
        if (text.contains("bnpl") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.toolName().equals(LOOKUP_API_ROUTE.value()))) {
            return tool("bnpl-route-" + question.sequence().value(), LOOKUP_API_ROUTE,
                    "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\",\"path\":\"/bnpl\",\"httpMethod\":\"GET\"}");
        }
        if (text.contains("cancellation") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().filter("order-service"::equals).isPresent())) {
            return tool("order-cancellation-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"order-service\",\"revision\":\"order-revision-1\"}");
        }
        if (text.contains("cancellation") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().filter("payment-service"::equals).isPresent())) {
            return tool("payment-refund-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\"}");
        }
        if (jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().isPresent())) {
            return tool("payment-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\"}");
        }
        return new ModelDecision.AnswerReady();
    }

    @Override
    public String reply(ReplyRequest request, Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
        replyRequests.add(request);
        UserMessage question = request.history().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .max(Comparator.comparingLong(message -> message.sequence().value())).orElseThrow();
        String text = question.message().toLowerCase(java.util.Locale.ROOT);
        return text.contains("fee")
                ? "The source shows a JSON-configured formula, but the current runtime value is unavailable."
                : text.contains("bnpl") ? "No BNPL behavior was found in the inspected codebase."
                : text.contains("cancellation") ? "Cancellation and refund information was inspected across both repositories."
                : "The repository information is available.";
    }

    private static ModelDecision.UseTool tool(String callId, ToolName toolName, String arguments) {
        return new ModelDecision.UseTool(callId, toolName, arguments, "dGVzdA==");
    }
}
