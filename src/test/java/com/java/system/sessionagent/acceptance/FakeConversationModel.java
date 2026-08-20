package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
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
    private final List<ModelRequest> requests = new ArrayList<>();

    List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ModelDecision decide(ModelRequest request, Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
        requests.add(request);
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
            return tool("invalid-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"missing-service\"}");
        }
        if (text.contains("invalid repository") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .filter(message -> message.toolName().equals("list_repositories")).count() < 2) {
            return tool("refresh-catalog-" + question.sequence().value(), LIST_REPOSITORIES, "{}");
        }
        if (text.contains("bnpl") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(ToolMessage::citeable)) {
            return tool("bnpl-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\"}");
        }
        if (text.contains("bnpl") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.toolName().equals(LOOKUP_API_ROUTE.value()))) {
            return tool("bnpl-route-" + question.sequence().value(), LOOKUP_API_ROUTE,
                    "{\"repositoryId\":\"payment-service\",\"apiPath\":\"/bnpl\",\"httpMethod\":\"GET\"}");
        }
        if (text.contains("cancellation") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().filter("order-service"::equals).isPresent())) {
            return tool("order-cancellation-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"order-service\"}");
        }
        if (text.contains("cancellation") && jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(message -> message.repositoryId().filter("payment-service"::equals).isPresent())) {
            return tool("payment-refund-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\"}");
        }
        if (jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .noneMatch(ToolMessage::citeable)) {
            return tool("payment-source-" + question.sequence().value(), LIST_ENTRY_POINTS, "{\"repositoryId\":\"payment-service\"}");
        }
        ResultId citation = jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                .filter(ToolMessage::citeable).max(Comparator.comparingLong(message -> message.sequence().value())).orElseThrow().resultId();
        List<ResultId> citations = text.contains("cancellation")
                ? jobHistory.stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                        .filter(ToolMessage::citeable)
                        .filter(message -> message.repositoryId().filter(repositoryId -> repositoryId.equals("order-service") || repositoryId.equals("payment-service")).isPresent())
                        .map(ToolMessage::resultId).toList()
                : List.of(citation);
        String answer = text.contains("fee")
                ? "The source shows a JSON-configured formula, but the current runtime value is unavailable."
                : text.contains("bnpl") ? "No BNPL behavior was found in the typed repository queries."
                : text.contains("cancellation") ? "Cancellation and refund evidence was inspected across both repositories."
                : "The source-backed repository information is available.";
        return new ModelDecision.Reply(new AssistantReply(answer, citations));
    }

    private static ModelDecision.UseTool tool(String callId, ToolName toolName, String arguments) {
        return new ModelDecision.UseTool(callId, toolName, arguments, "dGVzdA==");
    }
}
