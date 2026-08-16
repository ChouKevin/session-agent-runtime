package com.java.system.sessionagent.model;

import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.util.Assert;

import java.util.List;

public final class SpringAiToolCallbackFactory {

    public List<ToolCallback> create(ToolSnapshot toolSnapshot) {
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
        return toolSnapshot.definitions().stream().<ToolCallback>map(SnapshotToolCallback::from).toList();
    }

    private record SnapshotToolCallback(org.springframework.ai.tool.definition.ToolDefinition definition)
            implements ToolCallback {

        private static SnapshotToolCallback from(ToolDefinition definition) {
            return new SnapshotToolCallback(new DefaultToolDefinition(
                    definition.name().value(), definition.description(), definition.inputSchema()));
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("Tool callbacks must be executed by the conversation runtime");
        }
    }
}
