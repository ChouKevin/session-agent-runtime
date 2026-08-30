package com.java.system.sessionagent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClasses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class DirectToolRegistryTest {

    @Test
    void sanitizes_an_unexpected_executor_failure_as_invalid_response() {
        ToolDefinition definition = new ToolDefinition(CATALOG_TOOL, "1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        ToolRegistration<SampleInput> registration = new ToolRegistration<>(definition, SampleInput.class,
                input -> { throw new IllegalStateException("provider secret"); });
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), CATALOG_TOOL, "{\"repositoryId\":\"repo-a\"}"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_RESPONSE, failure.kind());
        assertThat(failure.getMessage()).doesNotContain("provider secret");
    }

    @Test
    void sanitizes_an_executor_json_contract_exception_as_invalid_response() {
        ToolDefinition definition = new ToolDefinition(CATALOG_TOOL, "1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        ToolRegistration<SampleInput> registration = new ToolRegistration<>(definition, SampleInput.class,
                input -> { throw new JsonContractException(); });
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), CATALOG_TOOL, "{\"repositoryId\":\"repo-a\"}"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_RESPONSE, failure.kind());
        assertThat(failure.getMessage()).doesNotContain("JSON contract");
    }

    @Test
    void rejects_a_catalog_executor_result_with_source_shape() {
        ToolDefinition definition = new ToolDefinition(CATALOG_TOOL, "1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        ToolRegistration<SampleInput> registration = new ToolRegistration<>(definition, SampleInput.class,
                input -> new ToolResult(Optional.of("private-repository"), Optional.of("private-revision"),
                        "{\"secret\":\"executor-private\"}"));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), CATALOG_TOOL, "{\"repositoryId\":\"repo-a\"}"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_RESPONSE, failure.kind());
        assertThat(failure.getMessage()).doesNotContain("private-repository", "private-revision", "executor-private");
    }

    @Test
    void rejects_a_source_executor_result_with_catalog_shape() {
        ToolDefinition definition = new ToolDefinition(SOURCE_TOOL, "1", "source", "{\"type\":\"object\"}", ToolKind.SOURCE);
        ToolRegistration<SampleInput> registration = new ToolRegistration<>(definition, SampleInput.class,
                input -> new ToolResult(Optional.empty(), Optional.empty(), "{\"secret\":\"executor-private\"}"));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), SOURCE_TOOL, "{\"repositoryId\":\"repo-a\"}"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_RESPONSE, failure.kind());
        assertThat(failure.getMessage()).doesNotContain("executor-private");
    }

    private static final ToolName CATALOG_TOOL = new ToolName("catalog.search");
    private static final ToolName SOURCE_TOOL = new ToolName("source.read");

    @Test
    void exposes_every_registered_tool_in_its_immutable_snapshot_from_the_first_model_call() {
        AtomicInteger invocations = new AtomicInteger();
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration(CATALOG_TOOL, ToolKind.CATALOG, invocations),
                registration(SOURCE_TOOL, ToolKind.SOURCE, invocations)));

        ToolSnapshot fullSnapshot = registry.snapshot();

        assertEquals(List.of(CATALOG_TOOL, SOURCE_TOOL), fullSnapshot.definitions().stream().map(ToolDefinition::name).toList());
        assertThrows(UnsupportedOperationException.class, () -> fullSnapshot.definitions().add(fullSnapshot.definitions().getFirst()));
    }

    @Test
    void decodes_once_executes_once_and_builds_the_canonical_execution_record() {
        AtomicInteger invocations = new AtomicInteger();
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration(CATALOG_TOOL, ToolKind.CATALOG, invocations)));

        ToolExecution execution = registry.execute(
                registry.snapshot(), CATALOG_TOOL, "{\"repositoryId\":\"repo-a\",\"limit\":2}");

        assertEquals(1, invocations.get());
        assertEquals(CATALOG_TOOL, execution.name());
        assertEquals("1", execution.version());
        assertEquals(ToolKind.CATALOG, execution.kind());
        assertEquals("{\"limit\":2,\"repositoryId\":\"repo-a\"}", execution.canonicalArguments());
        assertEquals(Optional.empty(), execution.repositoryId());
        assertEquals(Optional.empty(), execution.revision());
        assertEquals("{\"items\":[\"repo-a:2\"]}", execution.dataJson());
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejects_invalid_input_without_executing_the_tool(String rawArguments) {
        AtomicInteger invocations = new AtomicInteger();
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration(CATALOG_TOOL, ToolKind.CATALOG, invocations)));

        ToolExecutionFailure exception = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), CATALOG_TOOL, rawArguments));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, exception.kind());
        assertEquals(0, invocations.get());
    }

    @Test
    void applies_the_exact_utf8_boundary_without_executing_the_rejected_tool() {
        AtomicInteger acceptedInvocations = new AtomicInteger();
        DirectToolRegistry acceptingRegistry = new DirectToolRegistry(List.of(
                registration(CATALOG_TOOL, ToolKind.CATALOG, acceptedInvocations)));
        String acceptedArguments = validInputWithUtf8Length(65_536);

        acceptingRegistry.execute(acceptingRegistry.snapshot(), CATALOG_TOOL, acceptedArguments);

        assertEquals(1, acceptedInvocations.get());

        AtomicInteger rejectedInvocations = new AtomicInteger();
        DirectToolRegistry rejectingRegistry = new DirectToolRegistry(List.of(
                registration(CATALOG_TOOL, ToolKind.CATALOG, rejectedInvocations)));
        String rejectedArguments = validInputWithUtf8Length(65_537);

        ToolExecutionFailure exception = assertThrows(ToolExecutionFailure.class,
                () -> rejectingRegistry.execute(rejectingRegistry.snapshot(), CATALOG_TOOL, rejectedArguments));

        assertEquals(ToolExecutionFailure.Kind.INPUT_TOO_LARGE, exception.kind());
        assertEquals(0, rejectedInvocations.get());
    }

    @Test
    void production_tool_code_never_uses_string_get_bytes_for_input_limits() {
        JavaClasses toolProductionClasses = new ClassFileImporter().importPackages(
                "com.java.system.sessionagent.tool.application",
                "com.java.system.sessionagent.tool.domain",
                "com.java.system.sessionagent.tool.json");

        noClasses().should().callMethod(String.class, "getBytes", Charset.class).check(toolProductionClasses);
    }

    @Test
    void rejects_input_larger_than_the_utf8_boundary_without_executing_the_tool() {
        AtomicInteger invocations = new AtomicInteger();
        DirectToolRegistry registry = new DirectToolRegistry(List.of(registration(CATALOG_TOOL, ToolKind.CATALOG, invocations)));
        String rawArguments = "{\"repositoryId\":\"" + "界".repeat(22_000) + "\"}";

        ToolExecutionFailure exception = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), CATALOG_TOOL, rawArguments));

        assertEquals(ToolExecutionFailure.Kind.INPUT_TOO_LARGE, exception.kind());
        assertEquals(0, invocations.get());
    }

    @Test
    void rejects_a_tool_that_was_not_issued_in_the_supplied_snapshot_before_decoding() {
        AtomicInteger invocations = new AtomicInteger();
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration(CATALOG_TOOL, ToolKind.CATALOG, invocations),
                registration(SOURCE_TOOL, ToolKind.SOURCE, invocations)));

        ToolExecutionFailure failure = assertThrows(ToolExecutionFailure.class,
                () -> registry.execute(registry.snapshot(), SOURCE_TOOL, "not json"));

        assertEquals(ToolExecutionFailure.Kind.INVALID_INPUT, failure.kind());
        assertEquals(0, invocations.get());
    }

    @Test
    void rejects_duplicate_tool_names_when_the_registry_is_created() {
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> new DirectToolRegistry(List.of(
                registration(CATALOG_TOOL, ToolKind.CATALOG, invocations),
                registration(CATALOG_TOOL, ToolKind.SOURCE, invocations))));
    }

    private static Stream<String> invalidArguments() {
        return Stream.of(
                " ",
                "{\"repositoryId\":\"repo-a\",\"unknown\":true}",
                "{\"repositoryId\":\"repo-a\",\"repositoryId\":\"repo-b\"}",
                "{\"repositoryId\":null}",
                "{\"repositoryId\":\"\uD800\"}",
                "{\"repositoryId\":\"repo-a\",\"limit\":\"2\"}",
                "{\"repositoryId\":\"repo-a\"} {}",
                "{\"repositoryId\":\"repo-a\",\"limit\":21}");
    }

    private static ToolRegistration<SampleInput> registration(ToolName name, ToolKind kind, AtomicInteger invocations) {
        ToolSchemaFactory schemaFactory = new ToolSchemaFactory();
        ToolDefinition definition = new ToolDefinition(name, "1", "Search repository data", schemaFactory.schemaFor(SampleInput.class), kind);
        return new ToolRegistration<>(definition, SampleInput.class, input -> {
            invocations.incrementAndGet();
            if (kind == ToolKind.CATALOG) {
                return new ToolResult(Optional.empty(), Optional.empty(),
                        "{\"items\":[\"%s:%d\"]}".formatted(input.repositoryId(), input.limit()));
            }
            return new ToolResult(Optional.of(input.repositoryId()), Optional.of("main"),
                    "{\"items\":[\"%s:%d\"]}".formatted(input.repositoryId(), input.limit()));
        });
    }

    private static String validInputWithUtf8Length(int utf8ByteLength) {
        String prefix = "{\"repositoryId\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(utf8ByteLength - prefix.length() - suffix.length()) + suffix;
    }

    record SampleInput(
            @JsonProperty(required = true) @NotBlank String repositoryId,
            @JsonProperty(required = false) @Min(1) @Max(20) Integer limit) {

        SampleInput {
            limit = java.util.Objects.requireNonNullElse(limit, 10);
        }
    }
}
