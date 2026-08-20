package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

public sealed interface ModelDecision {

    record UseTool(String callId, ToolName toolName, String arguments, String modelContext) implements ModelDecision {

        public UseTool {
            Assert.hasText(callId, "Model call ID must not be blank");
            Assert.notNull(toolName, "Tool name must not be null");
            Assert.hasText(arguments, "Tool arguments must not be blank");
            Assert.hasText(modelContext, "Model context must not be blank");
        }
    }

    record Reply(AssistantReply reply) implements ModelDecision {

        public Reply {
            Assert.notNull(reply, "Assistant reply must not be null");
        }
    }
}
