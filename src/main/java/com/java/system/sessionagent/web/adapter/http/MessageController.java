package com.java.system.sessionagent.web.adapter.http;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobCancellationPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.SessionModelPort;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public final class MessageController {

    private final MessageIntakePort messageIntakePort;
    private final ConversationQueryPort conversationQueryPort;
    private final MessageJobCancellationPort messageJobCancellationPort;
    private final ExternalSessionReferenceQueryPort externalSessionReferenceQueryPort;
    private final SessionModelPort sessionModelPort;
    private final SlackDeliveryStore slackDeliveryStore;

    public MessageController(
            MessageIntakePort messageIntakePort,
            ConversationQueryPort conversationQueryPort,
            MessageJobCancellationPort messageJobCancellationPort,
            ExternalSessionReferenceQueryPort externalSessionReferenceQueryPort,
            SessionModelPort sessionModelPort,
            SlackDeliveryStore slackDeliveryStore) {
        Assert.notNull(messageIntakePort, "Message intake port must not be null");
        Assert.notNull(conversationQueryPort, "Conversation query port must not be null");
        Assert.notNull(messageJobCancellationPort, "Message job cancellation port must not be null");
        Assert.notNull(externalSessionReferenceQueryPort, "External session reference query port must not be null");
        Assert.notNull(sessionModelPort, "Session model port must not be null");
        Assert.notNull(slackDeliveryStore, "Slack delivery store must not be null");
        this.messageIntakePort = messageIntakePort;
        this.conversationQueryPort = conversationQueryPort;
        this.messageJobCancellationPort = messageJobCancellationPort;
        this.externalSessionReferenceQueryPort = externalSessionReferenceQueryPort;
        this.sessionModelPort = sessionModelPort;
        this.slackDeliveryStore = slackDeliveryStore;
    }

    @PutMapping("/sessions/{sessionId}/model")
    public MessageResponses.SessionModelResponse selectModel(
            @PathVariable String sessionId,
            @Valid @RequestBody MessageRequests.SessionModelRequest request) {
        requireUuid(sessionId);
        try {
            return sessionModelPort.select(new SessionId(sessionId), request.model())
                    .map(view -> new MessageResponses.SessionModelResponse(view.sessionId().value(), view.model(), "NEW_MESSAGES"))
                    .orElseThrow(WebErrorHandler.NotFoundException::new);
        } catch (IllegalArgumentException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponses.SendMessageResponse> receive(
            @Valid @RequestBody MessageRequests.SendMessageRequest request) {
        MessageReceipt receipt = messageIntakePort.receive(new IncomingMessage(request.sessionKey(), request.participantId(),
                request.sourceMessageId(), request.message()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MessageResponses.SendMessageResponse(receipt.sessionId().value(), receipt.messageJobId().value()));
    }

    @GetMapping("/slack-deliveries")
    public List<MessageResponses.SlackDeliveryResponse> slackDeliveries(
            @RequestParam MultiValueMap<String, String> queryParameters) {
        String teamId = singleQueryValue(queryParameters, "teamId").orElseThrow(WebErrorHandler.BadRequestException::new);
        String channelId = singleQueryValue(queryParameters, "channelId").orElseThrow(WebErrorHandler.BadRequestException::new);
        String messageTs = singleQueryValue(queryParameters, "messageTs").orElseThrow(WebErrorHandler.BadRequestException::new);
        return slackDeliveryStore.findBySource(teamId, channelId, messageTs).stream()
                .map(MessageResponses.SlackDeliveryResponse::from).toList();
    }

    @GetMapping("/message-jobs/{messageJobId}")
    public MessageResponses.MessageJobResponse job(@PathVariable String messageJobId) {
        requireUuid(messageJobId);
        return conversationQueryPort.findJob(messageJobId).map(MessageResponses.MessageJobResponse::from)
                .orElseThrow(WebErrorHandler.NotFoundException::new);
    }

    @PostMapping("/message-jobs/{messageJobId}/cancel")
    public MessageResponses.MessageJobResponse cancelJob(@PathVariable String messageJobId) {
        requireUuid(messageJobId);
        return messageJobCancellationPort.cancel(messageJobId).map(MessageResponses.MessageJobResponse::from)
                .orElseThrow(WebErrorHandler.NotFoundException::new);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<MessageResponses.SessionMessageResponse> messages(
            @PathVariable String sessionId,
            @RequestParam MultiValueMap<String, String> queryParameters) {
        requireUuid(sessionId);
        HistoryPagination pagination = historyPagination(queryParameters);
        if (pagination.afterSequence().isEmpty() && pagination.limit().isEmpty()) {
            return conversationQueryPort.messages(sessionId).orElseThrow(WebErrorHandler.NotFoundException::new).stream()
                    .map(MessageResponses.SessionMessageResponse::from).toList();
        }
        long startingSequence = pagination.afterSequence().orElse(0L);
        int maximumResults = pagination.limit().orElse(Integer.MAX_VALUE);
        return conversationQueryPort.messages(sessionId, startingSequence, maximumResults)
                .orElseThrow(WebErrorHandler.NotFoundException::new).stream()
                .map(MessageResponses.SessionMessageResponse::from).toList();
    }

    @PostMapping("/session-lookups")
    public MessageResponses.SessionLookupResponse sessionLookup(@Valid @RequestBody MessageRequests.SessionLookupRequest request) {
        try {
            String sessionId = externalSessionReferenceQueryPort.findSessionId(request.permalink())
                    .orElseThrow(WebErrorHandler.NotFoundException::new).value();
            return new MessageResponses.SessionLookupResponse(sessionId, "/internal/sessions/" + sessionId);
        } catch (IllegalArgumentException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }

    @GetMapping("/sessions/{sessionId}")
    public MessageResponses.SessionDetailResponse session(@PathVariable String sessionId) {
        requireUuid(sessionId);
        return conversationQueryPort.session(sessionId).map(view -> MessageResponses.SessionDetailResponse.from(view,
                externalSessionReferenceQueryPort.findBinding(new com.java.system.sessionagent.conversation.domain.SessionId(sessionId))
                        .filter(binding -> "SLACK".equals(binding.source()))
                        .map(binding -> new MessageResponses.SlackBindingResponse(binding.workspaceId(), binding.conversationId(),
                                binding.rootMessageId(), binding.createdAt())))).orElseThrow(WebErrorHandler.NotFoundException::new);
    }

    private static HistoryPagination historyPagination(MultiValueMap<String, String> queryParameters) {
        Optional<String> rawAfterSequence = singleQueryValue(queryParameters, "afterSequence");
        Optional<String> rawLimit = singleQueryValue(queryParameters, "limit");
        return new HistoryPagination(rawAfterSequence.map(MessageController::parseAfterSequence), rawLimit.map(MessageController::parseLimit));
    }

    private static Optional<String> singleQueryValue(MultiValueMap<String, String> queryParameters, String name) {
        if (!queryParameters.containsKey(name)) {
            return Optional.empty();
        }
        List<String> values = Objects.requireNonNull(queryParameters.get(name), "Query parameter values must not be null");
        if (values.size() != 1 || !StringUtils.hasText(values.getFirst())) {
            throw new WebErrorHandler.BadRequestException();
        }
        return Optional.of(values.getFirst());
    }

    private static long parseAfterSequence(String value) {
        try {
            long afterSequence = Long.parseLong(value);
            if (afterSequence < 0) {
                throw new WebErrorHandler.BadRequestException();
            }
            return afterSequence;
        } catch (NumberFormatException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }

    private static int parseLimit(String value) {
        try {
            int limit = Integer.parseInt(value);
            if (limit <= 0) {
                throw new WebErrorHandler.BadRequestException();
            }
            return limit;
        } catch (NumberFormatException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }

    private record HistoryPagination(Optional<Long> afterSequence, Optional<Integer> limit) {
    }
}
