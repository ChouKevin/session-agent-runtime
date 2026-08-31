package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SemanticToolContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void encloses_source_results_with_repository_and_revision_inside_semantic_output() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/code-facts/search"))
                .andRespond(withSuccess("""
                        {"repositoryId":"payment-service","revision":"1111111111111111111111111111111111111111","result":{"fact":"ok"}}
                        """, MediaType.APPLICATION_JSON));
        DirectToolRegistry registry = new DirectToolRegistry(new SemanticToolProvider(List::of,
                new SemanticSourceClient(builder.build())).registrations());

        String output = registry.invoke(registry.snapshot(), new ToolName("codebase_search_code_facts"), """
                {"repositoryId":"payment-service","revision":"1111111111111111111111111111111111111111","query":"payment"}
                """);
        JsonNode observation = JSON.readTree(output);

        assertThat(observation.required("repositoryId").textValue()).isEqualTo("payment-service");
        assertThat(observation.required("revision").textValue()).isEqualTo("1111111111111111111111111111111111111111");
        assertThat(observation.required("data").required("fact").textValue()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void exposes_the_exact_query_tool_set_without_removed_concept_tools() {
        ToolSnapshot snapshot = snapshot();

        assertThat(snapshot.definitions()).extracting(definition -> definition.name().value())
                .containsExactlyInAnyOrder(
                        "list_repositories",
                        "codebase_list_entry_points",
                        "codebase_lookup_api_route",
                        "codebase_suggest_api_route",
                        "codebase_outgoing_call_graph",
                        "codebase_incoming_call_graph",
                        "codebase_search_code_facts",
                        "codebase_get_code_fact",
                        "codebase_discover_event_listeners",
                        "codebase_discover_method_implementations",
                        "codebase_discover_type_members",
                        "codebase_find_internal_references",
                        "codebase_get_evidence_source",
                        "codebase_get_method_source",
                        "codebase_get_source_segment",
                        "codebase_resolve_source_symbol");
    }

    @Test
    void descriptions_do_not_advertise_removed_nested_or_optional_inputs() {
        assertThat(snapshot().definitions())
                .extracting(ToolDefinition::description)
                .allSatisfy(description -> assertThat(description)
                        .doesNotContain("optionally filtered by entry-point type")
                        .doesNotContain("optionally constrained by HTTP method")
                        .doesNotContain("method target")
                        .doesNotContain("typed identity")
                        .doesNotContain("evidence identity")
                        .doesNotContain("sourceFile/range"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryToolSchemas")
    void mirrors_each_flat_query_schema_and_describes_every_parameter(
            String toolName,
            Set<String> properties,
            Set<String> required) throws Exception {
        ToolDefinition definition = snapshot().definitions().stream()
                .filter(candidate -> candidate.name().equals(new ToolName(toolName)))
                .findFirst()
                .orElseThrow();
        JsonNode schema = JSON.readTree(definition.inputSchema());

        assertThat(fieldNames(schema.required("properties"))).isEqualTo(properties);
        assertThat(textValues(schema.required("required"))).isEqualTo(required);
        for (String property : properties) {
            JsonNode propertySchema = schema.required("properties").required(property);
            JsonNode description = propertySchema.path("description");
            assertThat(description.isTextual() && StringUtils.hasText(description.textValue()))
                    .as("%s.%s description", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("pattern").isMissingNode()).as("%s.%s pattern", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("minimum").isMissingNode()).as("%s.%s minimum", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("maximum").isMissingNode()).as("%s.%s maximum", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("maxLength").isMissingNode()).as("%s.%s maxLength", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("minItems").isMissingNode()).as("%s.%s minItems", toolName, property)
                    .isTrue();
            assertThat(propertySchema.path("maxItems").isMissingNode()).as("%s.%s maxItems", toolName, property)
                    .isTrue();
            if (propertySchema.path("minLength").isNumber()) {
                assertThat(propertySchema.path("minLength").intValue()).as("%s.%s minLength", toolName, property)
                        .isLessThanOrEqualTo(1);
            }
        }
    }

    private static Stream<Arguments> queryToolSchemas() {
        Set<String> identity = fields("repositoryId", "revision");
        Set<String> route = fields("repositoryId", "revision", "httpMethod", "path");
        Set<String> method = fields("repositoryId", "revision", "packageName", "className", "sourceFile",
                "methodName", "parameterTypes");
        Set<String> pagedMethod = append(method, "offset", "limit");
        Set<String> callGraph = append(method, "depth", "depthTwoNodeBudget");
        return Stream.of(
                Arguments.of("codebase_list_entry_points", identity, identity),
                Arguments.of("codebase_lookup_api_route", route, route),
                Arguments.of("codebase_suggest_api_route", route, route),
                Arguments.of("codebase_outgoing_call_graph", callGraph, method),
                Arguments.of("codebase_incoming_call_graph", callGraph, method),
                Arguments.of("codebase_search_code_facts",
                        fields("repositoryId", "revision", "query", "kinds", "packagePrefix", "offset", "limit"),
                        fields("repositoryId", "revision", "query")),
                Arguments.of("codebase_get_code_fact", fields("repositoryId", "revision", "factId"),
                        fields("repositoryId", "revision", "factId")),
                Arguments.of("codebase_discover_event_listeners",
                        fields("repositoryId", "revision", "eventType", "offset", "limit"),
                        fields("repositoryId", "revision", "eventType")),
                Arguments.of("codebase_discover_method_implementations", pagedMethod, method),
                Arguments.of("codebase_discover_type_members",
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "kinds",
                                "offset", "limit"),
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "kinds")),
                Arguments.of("codebase_find_internal_references", pagedMethod, method),
                Arguments.of("codebase_get_evidence_source", method, method),
                Arguments.of("codebase_get_method_source", method, method),
                Arguments.of("codebase_get_source_segment",
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "startLine",
                                "startCharacter", "endLine", "endCharacter"),
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "startLine",
                                "startCharacter", "endLine", "endCharacter")),
                Arguments.of("codebase_resolve_source_symbol",
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "symbol",
                                "line", "character"),
                        fields("repositoryId", "revision", "packageName", "className", "sourceFile", "symbol")));
    }

    private static Set<String> append(Set<String> fields, String... additions) {
        LinkedHashSet<String> result = new LinkedHashSet<>(fields);
        result.addAll(List.of(additions));
        return Set.copyOf(result);
    }

    private static Set<String> fields(String... values) {
        return Set.of(values);
    }

    private static Set<String> fieldNames(JsonNode object) {
        return Set.copyOf(object.propertyNames());
    }

    private static Set<String> textValues(JsonNode array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode value : array) {
            values.add(value.textValue());
        }
        return Set.copyOf(values);
    }

    private static ToolSnapshot snapshot() {
        SemanticToolProvider provider = new SemanticToolProvider(() -> List.of(), new SemanticSourceClient(
                RestClient.builder().baseUrl("https://semantic.test").build()));
        return new DirectToolRegistry(provider.registrations()).snapshot();
    }
}
