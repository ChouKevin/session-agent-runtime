package com.java.system.sessionagent.model;

import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiToolCallbackFactoryTest {

    @Test
    void exposes_repository_selection_guidance_through_the_catalog_tool() {
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(RestClient.create()));
        ToolSnapshot snapshot = new DirectToolRegistry(provider.registrations()).snapshot();

        org.springframework.ai.tool.definition.ToolDefinition callbackDefinition =
                new SpringAiToolCallbackFactory().create(snapshot).stream()
                        .filter(callback -> callback.getToolDefinition().name().equals("list_repositories"))
                        .findFirst()
                        .orElseThrow()
                        .getToolDefinition();

        assertThat(callbackDefinition.description())
                .contains("repository-specific query")
                .contains("reliable repositoryId/revision pair");
    }

    @Test
    void forwards_the_semantic_definition_without_reencoding_its_schema() {
        RestClient restClient = RestClient.create();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient));
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());
        ToolSnapshot snapshot = registry.snapshot();
        ToolDefinition sourceDefinition = snapshot.definitions().stream()
                .filter(definition -> definition.name().value().equals("codebase_get_method_source"))
                .findFirst()
                .orElseThrow();

        List<ToolCallback> callbacks = new SpringAiToolCallbackFactory().create(snapshot);
        org.springframework.ai.tool.definition.ToolDefinition callbackDefinition = callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals("codebase_get_method_source"))
                .findFirst()
                .orElseThrow()
                .getToolDefinition();

        assertThat(callbackDefinition.name()).isEqualTo(sourceDefinition.name().value());
        assertThat(callbackDefinition.description()).isEqualTo(sourceDefinition.description());
        assertThat(callbackDefinition.inputSchema()).isSameAs(sourceDefinition.inputSchema());
    }
}
