package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiToolCallbackFactoryTest {
    @Test
    void declares_generic_mcp_schema_without_executing_the_tool() {
        ToolSnapshot snapshot = new ToolSnapshot(List.of(new ToolBinding(
                new ToolDefinition(new ToolName("portable_lookup"), "Lookup", Map.of("type", "object")),
                arguments -> new ToolOutput(false, Map.of()))));

        org.springframework.ai.tool.definition.ToolDefinition definition = new SpringAiToolCallbackFactory(new ObjectMapper())
                .create(snapshot).getFirst().getToolDefinition();

        assertThat(definition.name()).isEqualTo("portable_lookup");
        assertThat(definition.inputSchema()).isEqualTo("{\"type\":\"object\"}");
    }
}
