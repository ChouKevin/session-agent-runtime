package com.java.system.sessionagent.web;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public final class MessageController {

    private final MessageIntakePort messageIntakePort;
    private final ConversationQueryPort conversationQueryPort;

    public MessageController(MessageIntakePort messageIntakePort, ConversationQueryPort conversationQueryPort) {
        Assert.notNull(messageIntakePort, "Message intake port must not be null");
        Assert.notNull(conversationQueryPort, "Conversation query port must not be null");
        this.messageIntakePort = messageIntakePort;
        this.conversationQueryPort = conversationQueryPort;
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
    public List<MessageResponses.SessionMessageResponse> messages(@PathVariable String sessionId) {
        requireUuid(sessionId);
        return conversationQueryPort.messages(sessionId).orElseThrow(WebErrorHandler.NotFoundException::new).stream()
                .map(MessageResponses.SessionMessageResponse::from).toList();
    }

    @GetMapping("/results/{resultId}")
    public MessageResponses.ResultResponse result(@PathVariable String resultId) {
        requireUuid(resultId);
        return conversationQueryPort.findResult(resultId).map(MessageResponses.ResultResponse::from)
                .orElseThrow(WebErrorHandler.NotFoundException::new);
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new WebErrorHandler.BadRequestException();
        }
    }
}
