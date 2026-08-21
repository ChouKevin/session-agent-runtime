package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.schema.JsonSchemaConverter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
        ToolRegistration<?> sourceSegment = registrations.stream()
                .filter(registration -> registration.definition().name().value().equals("codebase_get_source_segment"))
                .findFirst()
                .orElseThrow();
        assertTrue(sourceSegment.definition().description().contains("exact location returned by a prior source result"));
    }

    @Test
    void source_tool_descriptions_explain_when_each_semantic_operation_applies() {
        SemanticToolProvider provider = sourceEnabledProvider();
        List<String> descriptions = provider.registrations().stream()
                .filter(registration -> registration.definition().kind() == ToolKind.SOURCE)
                .map(registration -> registration.definition().description())
                .toList();

        assertEquals(15, descriptions.size());
        assertTrue(descriptions.stream().allMatch(description -> !description.isBlank()));
        assertEquals(descriptions.size(), descriptions.stream().distinct().count());
        assertTrue(descriptionFor(provider, "codebase_discover_type_members").contains("Java type target"));
        assertTrue(descriptionFor(provider, "codebase_discover_type_members")
                .contains("when METHOD members are requested or returned"));
        assertTrue(descriptionFor(provider, "codebase_discover_type_members").contains("complete method targets"));
        assertTrue(descriptionFor(provider, "codebase_get_method_source").contains("complete method target"));
        assertTrue(descriptionFor(provider, "codebase_get_method_source").contains("type identity alone is invalid"));
        assertTrue(descriptionFor(provider, "codebase_discover_method_implementations").contains("source-defined implementations"));
        assertTrue(descriptionFor(provider, "codebase_discover_method_implementations").contains("resolution status"));
        assertTrue(descriptionFor(provider, "codebase_discover_event_listeners").contains("fully qualified Java event type"));
        assertTrue(descriptionFor(provider, "codebase_discover_concepts").contains("one to four conjunctive terms"));
        assertTrue(descriptionFor(provider, "codebase_discover_concepts")
                .contains("every term must match the same concept"));
        assertTrue(descriptionFor(provider, "codebase_discover_concepts")
                .contains("Synonyms or alternatives require separate searches"));
        assertTrue(descriptionFor(provider, "codebase_discover_concepts").contains("method bodies are not searched"));
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
    void source_tool_schemas_expose_unambiguous_semantic_targets_and_concept_search_intent() {
        RestClient restClient = RestClient.create();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));

        String eventListeners = schemaFor(provider, "codebase_discover_event_listeners");
        String methodSource = schemaFor(provider, "codebase_get_method_source");
        String typeMembers = schemaFor(provider, "codebase_discover_type_members");
        String concepts = schemaFor(provider, "codebase_discover_concepts");

        assertTrue(eventListeners.contains("Fully qualified Java event type"));
        assertTrue(eventListeners.contains("com.example.order.OrderCancelledEvent"));
        assertTrue(eventListeners.contains("\"pattern\":\"^(?:[^.\\\\s\\\\[\\\\]]+\\\\.)+[^.\\\\s\\\\[\\\\]]+(?:\\\\[\\\\])*$\""));
        assertTrue(methodSource.contains("Complete method target; a type identity alone is invalid"));
        assertTrue(methodSource.contains("Exact method name copied from a prior Semantic method target"));
        assertTrue(methodSource.contains("Ordered parameter type strings copied unchanged from Semantic; fully-qualified "
                + "reference types, primitives, arrays, and type variables are allowed; use an empty list for a no-argument method"));
        assertTrue(methodSource.contains("\"required\":[\"methodName\",\"parameterTypes\",\"sourceType\"]"));
        assertTrue(typeMembers.contains("Java type target, not a method target; copy it from a prior Semantic result"));
        assertTrue(concepts.contains("One to four conjunctive terms; every term must match the same concept. "
                + "Synonyms or alternatives require separate searches; method bodies are not searched"));
        assertTrue(concepts.contains("\"minItems\":1"));
        assertTrue(concepts.contains("\"maxItems\":4"));
    }

    @Test
    void rejects_unqualified_event_types_and_incomplete_method_targets_before_semantic_http() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());
        server.expect(never(), requestTo("https://semantic.test/v1/repositories/payment-service"));

        ToolExecutionFailure unqualifiedEvent = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_discover_event_listeners"),
                "{\"repositoryId\":\"payment-service\",\"eventType\":\"OrderCancelledEvent\"}"));
        ToolExecutionFailure incompleteTarget = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_get_method_source"), """
                        {"repositoryId":"payment-service","target":{"sourceType":{"javaType":
                        {"packageName":"com.example","className":"Payments"},"sourceFile":"src/Payments.java"}}}
                        """));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, unqualifiedEvent.kind());
        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, incompleteTarget.kind());
        server.verify();
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

    @Test
    void translates_a_missing_source_segment_to_correctable_invalid_input() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SemanticToolProvider provider = new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));
        DirectToolRegistry registry = new DirectToolRegistry(provider.registrations());
        server.expect(once(), requestTo("https://semantic.test/v1/repositories/payment-service"))
                .andRespond(withSuccess("""
                        {"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service",
                        "currentBranch":"main","currentRevision":"revision-42","cloned":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://semantic.test/v1/discovery/source-segment"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"errorCode":"SOURCE_SEGMENT_NOT_FOUND","message":"source segment was not found",
                        "repoId":null,"expectedRevision":null,"currentRevision":null,"target":null,
                        "candidates":[],"requestId":"request-1"}
                        """));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class, () -> registry.execute(
                registry.snapshot(true), new ToolName("codebase_get_source_segment"), """
                        {"repositoryId":"payment-service","location":{"sourceFile":"src/Payments.java",
                        "range":{"start":{"line":0,"character":0},"end":{"line":20,"character":100}}}}
                        """));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, failure.kind());
        server.verify();
    }

    private static String schemaFor(SemanticToolProvider provider, String toolName) {
        return provider.registrations().stream()
                .filter(registration -> registration.definition().name().value().equals(toolName))
                .findFirst()
                .orElseThrow()
                .definition()
                .inputSchema();
    }

    private static String descriptionFor(SemanticToolProvider provider, String toolName) {
        return provider.registrations().stream()
                .filter(registration -> registration.definition().name().value().equals(toolName))
                .map(registration -> registration.definition().description())
                .findFirst()
                .orElseThrow();
    }

    private static SemanticToolProvider sourceEnabledProvider() {
        RestClient restClient = RestClient.create();
        return new SemanticToolProvider(
                List::of, new SemanticSourceClient(restClient, new SemanticRepositoryClient(restClient)));
    }
}
