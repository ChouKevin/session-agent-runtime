package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.schema.JsonSchemaConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticToolContractTest {

    @Test
    void registers_the_catalog_and_exactly_fifteen_closed_source_tools() {
        RestClient restClient = RestClient.create();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));

        List<ToolRegistration<?>> registrations = provider.registrations();
        List<String> names = registrations.stream().map(registration -> registration.definition().name().value()).toList();

        assertEquals(List.of(
                "list_repositories",
                "codebase_list_entry_points", "codebase_lookup_api_route", "codebase_suggest_api_route",
                "codebase_outgoing_call_graph", "codebase_incoming_call_graph", "codebase_discover_concepts",
                "codebase_resolve_concept", "codebase_discover_event_listeners",
                "codebase_discover_method_implementations", "codebase_discover_type_members",
                "codebase_find_internal_references", "codebase_get_evidence_source", "codebase_get_method_source",
                "codebase_get_source_segment", "codebase_resolve_source_symbol"), names);
        assertEquals(names.size(), names.stream().distinct().count());
        registrations.stream().skip(1).forEach(registration -> {
            assertTrue(registration.definition().inputSchema().contains("repositoryId"));
            assertTrue(registration.definition().inputSchema().contains("\"required\""));
            assertFalse(registration.definition().inputSchema().contains("expectedRevision"));
            assertFalse(registration.definition().inputSchema().contains("availableFollowUps"));
            assertFalse(registration.definition().inputSchema().contains("candidates"));
            assertFalse(registration.definition().inputSchema().contains("handles"));
        });
        assertEquals(new ToolName("codebase_list_entry_points"), registrations.get(1).definition().name());
    }

    @Test
    void every_tool_schema_is_convertible_by_the_google_model_adapter() {
        RestClient restClient = RestClient.create();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));

        provider.registrations().forEach(registration -> {
            assertFalse(registration.definition().inputSchema().contains("\"type\":["),
                    () -> registration.definition().name().value() + " must not expose nullable type arrays");
            JsonSchemaConverter.convertToOpenApiSchema(
                    JsonSchemaConverter.fromJson(registration.definition().inputSchema()));
        });
    }

    @Test
    void concept_search_schema_uses_only_semantic_provider_match_modes() {
        RestClient restClient = RestClient.create();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));

        String schema = provider.registrations().stream()
                .filter(registration -> registration.definition().name().value().equals("codebase_discover_concepts"))
                .findFirst()
                .orElseThrow()
                .definition()
                .inputSchema();

        assertTrue(schema.contains("TOKEN_EXACT"));
        assertTrue(schema.contains("TOKEN_PREFIX"));
        assertFalse(schema.contains("CONTAINS"));
    }

    @Test
    void type_member_schema_exposes_every_semantic_provider_member_kind() {
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(RestClient.create("https://semantic.test"),
                new SemanticRepositoryClient(RestClient.create("https://semantic.test"))));

        String schema = provider.registrations().stream()
                .filter(registration -> registration.definition().name().value()
                        .equals("codebase_discover_type_members"))
                .findFirst()
                .orElseThrow()
                .definition()
                .inputSchema();

        assertTrue(schema.contains("METHOD"));
        assertTrue(schema.contains("FIELD"));
        assertTrue(schema.contains("ENUM_CONSTANT"));
        assertTrue(schema.contains("RECORD_COMPONENT"));
    }

    @Test
    void rejects_windows_and_backslash_source_paths_before_any_source_http_request() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());

        ToolExecutionFailure windows = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_get_source_segment"), """
                        {"repositoryId":"payment-service","location":{"sourceFile":"C:/Payments.java",
                        "range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}
                        """));
        ToolExecutionFailure backslash = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_get_source_segment"), """
                        {"repositoryId":"payment-service","location":{"sourceFile":"src\\Payments.java",
                        "range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}
                        """));
        ToolExecutionFailure unknownDiscriminator = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_resolve_concept"), """
                        {"repositoryId":"payment-service","identity":{"kind":"UNKNOWN"}}
                        """));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, windows.kind());
        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, backslash.kind());
        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, unknownDiscriminator.kind());
        server.verify();
    }

    @Test
    void translates_semantic_failures_at_the_tool_executor_boundary() {
        SemanticToolProvider provider = new SemanticToolProvider(() -> {
            throw SemanticFailure.transientFailure(java.util.Optional.empty());
        });
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(false), new ToolName("list_repositories"), "{}"));

        assertEquals(ToolExecutionFailure.Kind.TRANSIENT, failure.kind());
    }
}
