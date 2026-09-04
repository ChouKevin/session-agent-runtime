package com.java.system.sessionagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.util.Assert;

import java.util.List;

public final class SpringAiToolCallbackFactory {

    private final ObjectMapper objectMapper;

    public SpringAiToolCallbackFactory(ObjectMapper objectMapper) {
        Assert.notNull(objectMapper, "Object mapper must not be null");
        this.objectMapper = objectMapper;
    }

    public List<ToolCallback> create(ToolSnapshot toolSnapshot) {
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
        return toolSnapshot.definitions().stream().<ToolCallback>map(this::callback).toList();
    }

    private SnapshotToolCallback callback(ToolDefinition definition) {
        try {
            return new SnapshotToolCallback(new DefaultToolDefinition(
                    definition.name().value(), definition.description(), objectMapper.writeValueAsString(definition.inputSchema())));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Tool schema cannot be serialized", exception);
        }
    }

    private record SnapshotToolCallback(org.springframework.ai.tool.definition.ToolDefinition definition)
            implements ToolCallback {

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
