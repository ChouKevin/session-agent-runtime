package com.java.system.sessionagent.model.adapter.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiToolCallbackFactoryTest {
    @Test
    void declares_generic_mcp_schema_and_keeps_execution_in_the_runtime() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
        ObjectMapper objectMapper = new ObjectMapper();
        try (ToolSnapshot snapshot = new ToolSnapshot(List.of(new ToolBinding(
                new com.java.system.sessionagent.tool.port.ToolDefinition(new ToolName("portable_lookup"), "Lookup", schema),
                (context, arguments) -> {
                    throw new AssertionError("Spring AI must not execute the runtime tool");
                }, ToolCapability.ORDINARY)))) {
            List<ToolCallback> callbacks = new SpringAiToolCallbackFactory(objectMapper).create(snapshot);
            assertThat(callbacks).hasSize(1);
            ToolCallback callback = callbacks.getFirst();
            ToolDefinition definition = callback.getToolDefinition();

            assertThat(definition.name()).isEqualTo("portable_lookup");
            assertThat(definition.description()).isEqualTo("Lookup");
            assertThat(objectMapper.readTree(definition.inputSchema())).isEqualTo(objectMapper.valueToTree(schema));
            assertThatThrownBy(() -> callback.call("{}"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
