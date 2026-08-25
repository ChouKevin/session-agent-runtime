package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.springframework.util.Assert;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DirectToolRegistry {

    private static final int MAX_INPUT_UTF8_BYTES = 65_536;

    private final List<ToolRegistration<?>> registrations;
    private final StrictJsonCodec jsonCodec;

    public DirectToolRegistry(List<ToolRegistration<?>> registrations) {
        this(registrations, new StrictJsonCodec());
    }

    DirectToolRegistry(List<ToolRegistration<?>> registrations, StrictJsonCodec jsonCodec) {
        Assert.notNull(registrations, "Tool registrations must not be null");
        Assert.notNull(jsonCodec, "JSON codec must not be null");
        this.registrations = List.copyOf(registrations);
        this.jsonCodec = jsonCodec;
        ensureDistinctToolNames(this.registrations);
    }

    public ToolSnapshot snapshot() {
        return new ToolSnapshot(registrations);
    }

    public ToolExecution execute(ToolSnapshot snapshot, ToolName name, String rawArguments) {
        Assert.notNull(snapshot, "Tool snapshot must not be null");
        Assert.notNull(name, "Tool name must not be null");
        Assert.notNull(rawArguments, "Tool arguments must not be null");

        ToolRegistration<?> registration = snapshot.registration(name)
                .orElseThrow(ToolExecutionFailure::invalidInput);
        return executeRegistration(registration, rawArguments);
    }

    private <T> ToolExecution executeTyped(ToolRegistration<T> registration, String rawArguments) {
        T input;
        String canonicalArguments;
        try {
            input = jsonCodec.decodeBounded(rawArguments, registration.inputType(), MAX_INPUT_UTF8_BYTES);
            canonicalArguments = jsonCodec.canonicalize(input);
        } catch (JsonContractException exception) {
            if (exception.isInputTooLarge()) {
                throw ToolExecutionFailure.inputTooLarge();
            }
            throw ToolExecutionFailure.invalidInput();
        } catch (RuntimeException exception) {
            throw ToolExecutionFailure.invalidResponse();
        }
        try {
            ToolResult result = Objects.requireNonNull(registration.executor().execute(input), "Tool result must not be null");
            if (!matchesKind(registration.definition().kind(), result)) {
                throw ToolExecutionFailure.invalidResponse();
            }
            return new ToolExecution(
                    registration.definition().name(),
                    registration.definition().version(),
                    registration.definition().kind(),
                    canonicalArguments,
                    result.repositoryId(),
                    result.revision(),
                    result.dataJson(),
                    result.citeable());
        } catch (ToolExecutionFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ToolExecutionFailure.invalidResponse();
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecution executeRegistration(ToolRegistration<?> registration, String rawArguments) {
        return executeTyped((ToolRegistration<Object>) registration, rawArguments);
    }

    private static void ensureDistinctToolNames(List<ToolRegistration<?>> registrations) {
        Set<ToolName> names = new HashSet<>();
        for (ToolRegistration<?> registration : registrations) {
            Objects.requireNonNull(registration, "Tool registration must not be null");
            if (!names.add(registration.definition().name())) {
                throw new IllegalArgumentException("Tool names must be distinct");
            }
        }
    }

    private static boolean matchesKind(ToolKind kind, ToolResult result) {
        return switch (kind) {
            case CATALOG -> !result.citeable() && result.repositoryId().isEmpty() && result.revision().isEmpty();
            case SOURCE -> result.citeable() && result.repositoryId().isPresent() && result.revision().isPresent();
        };
    }
}
