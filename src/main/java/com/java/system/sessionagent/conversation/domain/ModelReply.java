package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public sealed interface ModelReply permits ModelReply.Text, ModelReply.UseTools {

    record Text(String message) implements ModelReply {

        public Text {
            Assert.hasText(message, "Model reply text must not be blank");
        }
    }

    record UseTools(Optional<String> message, List<ToolRequest> requests) implements ModelReply {

        public UseTools {
            Assert.notNull(message, "Model reply message must not be null");
            Assert.notNull(requests, "Model tool requests must not be null");
            message.ifPresent(value -> Assert.hasText(value, "Model reply message must not be blank"));
            requests = List.copyOf(requests);
            Assert.notEmpty(requests, "Model reply must contain tool requests");
            Set<ToolCallId> toolCallIds = requests.stream().map(request -> request.toolCallId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Assert.isTrue(toolCallIds.size() == requests.size(), "Model tool call IDs must be distinct");
        }
    }
}
