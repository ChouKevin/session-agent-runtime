package com.java.system.sessionagent.web;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public final class MessageController {

    private final MessageIntakePort messageIntakePort;
    private final ConversationQueryPort conversationQueryPort;
    private final ExternalSessionReferenceQueryPort externalSessionReferenceQueryPort;

    public MessageController(
            MessageIntakePort messageIntakePort,
            ConversationQueryPort conversationQueryPort,
            ExternalSessionReferenceQueryPort externalSessionReferenceQueryPort) {
        Assert.notNull(messageIntakePort, "Message intake port must not be null");
        Assert.notNull(conversationQueryPort, "Conversation query port must not be null");
        Assert.notNull(externalSessionReferenceQueryPort, "External session reference query port must not be null");
        this.messageIntakePort = messageIntakePort;
        this.conversationQueryPort = conversationQueryPort;
        this.externalSessionReferenceQueryPort = externalSessionReferenceQueryPort;
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponses.SendMessageResponse> receive(
            @Valid @RequestBody MessageRequests.SendMessageRequest request) {
        MessageReceipt receipt = messageIntakePort.receive(new IncomingMessage(request.sessionKey(), request.participantId(),
                request.sourceMessageId(), request.message()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MessageResponses.SendMessageResponse(receipt.sessionId().value(), receipt.messageJobId().value()));
    }

    @GetMapping("/message-jobs/{messageJobId}")
    public MessageResponses.MessageJobResponse job(@PathVariable String messageJobId) {
        requireUuid(messageJobId);
        return conversationQueryPort.findJob(messageJobId).map(MessageResponses.MessageJobResponse::from)
                .orElseThrow(WebErrorHandler.NotFoundException::new);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<MessageResponses.SessionMessageResponse> messages(
            @PathVariable String sessionId,
            @RequestParam Optional<Long> afterSequence,
            @RequestParam Optional<Integer> limit) {
        requireUuid(sessionId);
        validateHistoryPagination(afterSequence, limit);
        if (afterSequence.isEmpty() && limit.isEmpty()) {
            return conversationQueryPort.messages(sessionId).orElseThrow(WebErrorHandler.NotFoundException::new).stream()
                    .map(MessageResponses.SessionMessageResponse::from).toList();
        }
        long startingSequence = afterSequence.orElse(0L);
        int maximumResults = limit.orElse(Integer.MAX_VALUE);
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

    private static void validateHistoryPagination(Optional<Long> afterSequence, Optional<Integer> limit) {
        if (afterSequence.filter(value -> value < 0).isPresent() || limit.filter(value -> value <= 0).isPresent()) {
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
}
