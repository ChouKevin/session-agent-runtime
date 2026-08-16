package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.tool.ListRepositoriesInput;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListRepositoriesToolTest {

    @Test
    void wraps_catalog_repositories_inside_the_production_data_envelope() throws Exception {
        ToolExecution execution = new ToolExecution(new ToolName("list_repositories"), "v1", ToolKind.CATALOG, "{}",
                Optional.empty(), Optional.empty(), "{\"repositories\":[{\"repositoryId\":\"payment-service\"}]}", false);
        String resultJson = new com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory().envelope("result-1",
                new com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory().validate(execution));

        assertEquals("payment-service", JsonMapper.builder().build().readTree(resultJson)
                .path("data").path("repositories").get(0).path("repositoryId").asText());
    }

    @Test
    void registers_the_closed_catalog_tool_and_returns_uncited_unscoped_normalized_json() {
        SemanticToolProvider provider = new SemanticToolProvider(() -> List.of(
                new RepositorySummary(new RepositoryId("payment-service"), "Payment Service")));
        List<ToolRegistration<?>> registrations = provider.registrations();
        DirectToolRegistry registry = new DirectToolRegistry(registrations);

        ToolDefinition definition = registrations.getFirst().definition();
        ToolExecution execution = registry.execute(registry.snapshot(false), new ToolName("list_repositories"), "{}");

        assertEquals(1, registrations.size());
        assertEquals(new ToolName("list_repositories"), definition.name());
        assertEquals("v1", definition.version());
        assertEquals(ToolKind.CATALOG, definition.kind());
        assertEquals(ListRepositoriesInput.class, registrations.getFirst().inputType());
        assertTrue(definition.inputSchema().contains("\"additionalProperties\":false"));
        assertFalse(definition.inputSchema().contains("repositoryId"));
        assertEquals(Optional.empty(), execution.repositoryId());
        assertEquals(Optional.empty(), execution.revision());
        assertFalse(execution.citeable());
        assertEquals("{\"repositories\":[{\"displayName\":\"Payment Service\",\"repositoryId\":\"payment-service\"}]}", execution.dataJson());
    }

    @Test
    void rejects_every_nonempty_input_before_listing_repositories() {
        SemanticToolProvider provider = new SemanticToolProvider(List::of);
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());

        ToolExecutionFailure exception = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(false), new ToolName("list_repositories"), "{\"repositoryId\":\"payment-service\"}"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, exception.kind());
    }
}
