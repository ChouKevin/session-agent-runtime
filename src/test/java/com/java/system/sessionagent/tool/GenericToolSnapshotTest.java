package com.java.system.sessionagent.tool;

import static com.java.system.sessionagent.tool.TestToolInvocation.invocation;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
                (context, supplied) -> {
                    received.set(supplied);
                    return new ToolOutput(false, Map.of("items", List.of()));
                }, ToolCapability.ORDINARY);
        try (ToolSnapshot snapshot = new ToolSnapshot(List.of(binding))) {
            ToolOutput output = snapshot.invoke(invocation(), new ToolName("semantic_search_code"), arguments);

            assertFalse(output.isError());
            assertSame(arguments, received.get());
            assertThrows(IllegalArgumentException.class,
                    () -> snapshot.invoke(invocation(), new ToolName("semantic_search_code"), null));
        }
    }

    @Test
    void snapshot_retains_ordered_bindings_when_the_source_list_is_mutated() {
        ArrayList<ToolBinding> source = new ArrayList<>();
        ToolBinding first = binding("first");
        ToolBinding second = binding("second");
        source.add(first);
        source.add(second);

        try (ToolSnapshot snapshot = new ToolSnapshot(source)) {
            source.clear();
            source.add(binding("replacement"));

            assertEquals(List.of(first.definition(), second.definition()), snapshot.definitions());
        }
    }

    @Test
    void closes_its_lifetime_action_once_without_changing_unmanaged_snapshot_behavior() {
        AtomicInteger releases = new AtomicInteger();
        ToolSnapshot snapshot = new ToolSnapshot(List.of(binding("first")), releases::incrementAndGet);

        snapshot.close();
        snapshot.close();

        assertEquals(1, releases.get());
        assertFalse(snapshot.invoke(invocation(), new ToolName("first"), Map.of()).isError());
    }

    @Test
    void rejects_duplicate_exposed_names() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSnapshot(List.of(binding("duplicate"), binding("duplicate"))));
    }

    @Test
    void returns_not_available_output_for_unknown_names() {
        try (ToolSnapshot snapshot = new ToolSnapshot(List.of())) {
            ToolOutput output = snapshot.invoke(invocation(), new ToolName("unknown"), Map.of());

            assertTrue(output.isError());
            assertTrue(output.asStructuredValue().get("result") instanceof Map<?, ?>);
            Map<?, ?> failure = (Map<?, ?>) output.asStructuredValue().get("result");
            assertEquals("TOOL_NOT_AVAILABLE", failure.get("code"));
        }
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

        List<?> frozenProperties = (List<?>) definition.inputSchema().get("properties");
        Map<?, ?> frozenNested = (Map<?, ?>) frozenProperties.getFirst();
        assertEquals(Map.of("minimum", 1), frozenNested);
        assertThrows(UnsupportedOperationException.class, frozenNested::clear);
        assertThrows(UnsupportedOperationException.class, frozenProperties::clear);
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

        assertTrue(output.isError());
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

    @Test
    void restricted_snapshot_hides_and_rejects_excluded_tools_and_owns_the_lifetime_once() {
        AtomicInteger ordinaryCalls = new AtomicInteger();
        AtomicInteger reportCalls = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        ToolBinding ordinary = new ToolBinding(new ToolDefinition(new ToolName("opaqueLookup"), "Lookup", SCHEMA),
                (context, arguments) -> {
                    ordinaryCalls.incrementAndGet();
                    return new ToolOutput(false, arguments);
                }, ToolCapability.ORDINARY);
        ToolBinding report = new ToolBinding(new ToolDefinition(new ToolName("opaqueReport"), "Report", SCHEMA),
                (context, arguments) -> {
                    reportCalls.incrementAndGet();
                    return new ToolOutput(false, arguments);
                }, ToolCapability.REPORT_GENERATION);
        ToolSnapshot owner = new ToolSnapshot(List.of(ordinary, report), releases::incrementAndGet)
                .restrictedTo(Set.of(ToolCapability.REPORT_GENERATION));

        assertEquals(List.of(report.definition()), owner.definitions());
        assertTrue(owner.invoke(invocation(), new ToolName("opaqueLookup"), Map.of()).isError());
        assertFalse(owner.invoke(invocation(), new ToolName("opaqueReport"), Map.of()).isError());
        assertEquals(0, ordinaryCalls.get());
        assertEquals(1, reportCalls.get());
        assertEquals(0, releases.get());
        owner.close();
        owner.close();
        assertEquals(1, releases.get());
    }

    private static ToolBinding binding(String name) {
        return new ToolBinding(
                new ToolDefinition(new ToolName(name), name, SCHEMA),
                (context, arguments) -> new ToolOutput(false, arguments), ToolCapability.ORDINARY);
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
