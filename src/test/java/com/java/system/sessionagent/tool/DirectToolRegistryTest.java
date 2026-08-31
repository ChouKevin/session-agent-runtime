package com.java.system.sessionagent.tool;

import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectToolRegistryTest {
    @Test void decodes_registered_typed_input_and_returns_executor_output_unchanged() {
        ToolName name = new ToolName("lookup");
        ToolRegistration<Input> registration = new ToolRegistration<>(new ToolDefinition(name, "lookup", new ToolSchemaFactory().schemaFor(Input.class)), Input.class, input -> "plain output");
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));
        assertThat(registry.invoke(registry.snapshot(), name, "{\"value\":\"x\"}")).isEqualTo("plain output");
    }

    @Test
    void sanitizes_an_unexpected_executor_failure() {
        ToolName name = new ToolName("lookup");
        ToolRegistration<Input> registration = new ToolRegistration<>(
                new ToolDefinition(name, "lookup", new ToolSchemaFactory().schemaFor(Input.class)), Input.class,
                input -> {
                    throw new IllegalStateException("provider secret");
                });
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));

        assertThatThrownBy(() -> registry.invoke(registry.snapshot(), name, "{\"value\":\"x\"}"))
                .isInstanceOfSatisfying(ToolExecutionFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TOOL_RESPONSE_INVALID");
                    assertThat(failure.getMessage()).doesNotContain("provider secret");
                });
    }
    record Input(String value) { }
}
