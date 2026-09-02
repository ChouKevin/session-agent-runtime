package com.java.system.sessionagent.tool;

import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericToolSnapshotTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    @Test
    void snapshot_keeps_the_exact_binding_and_forwards_arguments_unchanged() {
        Map<String, Object> arguments = Map.of("repositoryId", "payments", "limit", 20);
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolBinding binding = new ToolBinding(
                new ToolDefinition(new ToolName("semantic_search_code"), "Search indexed code", SCHEMA),
                supplied -> {
                    received.set(supplied);
                    return new ToolOutput(false, Map.of("items", List.of()));
                });
        ToolSnapshot snapshot = new ToolSnapshot(List.of(binding));

        ToolOutput output = snapshot.invoke(new ToolName("semantic_search_code"), arguments);

        assertFalse(output.isError());
        assertSame(arguments, received.get());
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.invoke(new ToolName("semantic_search_code"), null));
    }

    @Test
    void snapshot_retains_ordered_bindings_when_the_source_list_is_mutated() {
        ArrayList<ToolBinding> source = new ArrayList<>();
        ToolBinding first = binding("first");
        ToolBinding second = binding("second");
        source.add(first);
        source.add(second);

        ToolSnapshot snapshot = new ToolSnapshot(source);
        source.clear();
        source.add(binding("replacement"));

        assertEquals(List.of(first.definition(), second.definition()), snapshot.definitions());
    }

    @Test
    void rejects_duplicate_exposed_names() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSnapshot(List.of(binding("duplicate"), binding("duplicate"))));
    }

    @Test
    void returns_not_available_output_for_unknown_names() {
        ToolOutput output = new ToolSnapshot(List.of()).invoke(new ToolName("unknown"), Map.of());

        assertTrue(output.isError());
        assertTrue(output.asStructuredValue().get("result") instanceof Map<?, ?>);
        Map<?, ?> failure = (Map<?, ?>) output.asStructuredValue().get("result");
        assertEquals("TOOL_NOT_AVAILABLE", failure.get("code"));
    }

    @Test
    void copies_the_non_null_input_schema() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        ToolDefinition definition = new ToolDefinition(new ToolName("lookup"), "Lookup", schema);
        schema.put("additionalProperties", false);

        assertEquals(Map.of("type", "object"), definition.inputSchema());
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition(new ToolName("lookup"), "Lookup", null));
    }

    @Test
    void recursively_detaches_and_freezes_the_input_schema_tree() {
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("minimum", 1);
        ArrayList<Object> properties = new ArrayList<>();
        properties.add(nested);
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("properties", properties);

        ToolDefinition definition = new ToolDefinition(new ToolName("lookup"), "Lookup", schema);
        nested.put("maximum", 2);
        properties.add(Map.of("additionalProperties", false));

        Map<?, ?> frozenNested = (Map<?, ?>) ((List<?>) definition.inputSchema().get("properties")).getFirst();
        assertEquals(Map.of("minimum", 1), frozenNested);
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) frozenNested).put("maximum", 2));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) definition.inputSchema().get("properties")).add(Map.of()));
    }

    @Test
    void preserves_a_null_result_key_in_structured_output() {
        ToolOutput output = new ToolOutput(false, null);

        assertEquals(false, output.asStructuredValue().get("isError"));
        assertTrue(output.asStructuredValue().containsKey("result"));
        assertEquals(null, output.asStructuredValue().get("result"));
    }

    @ParameterizedTest
    @MethodSource("runtimeFailureCodes")
    void creates_safe_runtime_failures_for_the_supported_codes(String code) {
        ToolOutput output = ToolOutput.runtimeFailure(code, "safe message");

        assertEquals(Map.of("code", code, "message", "safe message"), output.asStructuredValue().get("result"));
    }

    @ParameterizedTest
    @MethodSource("portableNames")
    void accepts_portable_tool_names(String name) {
        assertDoesNotThrow(() -> new ToolName(name));
    }

    @ParameterizedTest
    @MethodSource("nonPortableNames")
    void rejects_non_portable_tool_names(String name) {
        assertThrows(IllegalArgumentException.class, () -> new ToolName(name));
    }

    private static ToolBinding binding(String name) {
        return new ToolBinding(
                new ToolDefinition(new ToolName(name), name, SCHEMA),
                arguments -> new ToolOutput(false, arguments));
    }

    private static Stream<String> runtimeFailureCodes() {
        return Stream.of(
                "TOOL_TIMEOUT",
                "TOOL_CONNECTION_FAILED",
                "TOOL_NOT_AVAILABLE",
                "TOOL_PROTOCOL_ERROR");
    }

    private static Stream<String> portableNames() {
        return Stream.of("a", "A", "tool_name-2", "a" + "x".repeat(63));
    }

    private static Stream<String> nonPortableNames() {
        return Stream.of("", "1tool", "tool.name", "a" + "x".repeat(64));
    }
}
