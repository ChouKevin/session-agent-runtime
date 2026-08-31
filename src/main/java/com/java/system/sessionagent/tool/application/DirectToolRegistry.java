package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.springframework.util.Assert;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DirectToolRegistry {

    private static final int MAX_INPUT_UTF8_BYTES = 65_536;
    private static final String INVALID_INPUT = "TOOL_INPUT_INVALID";
    private static final String INVALID_RESPONSE = "TOOL_RESPONSE_INVALID";

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

    public String invoke(ToolSnapshot snapshot, ToolName name, String input) {
        Assert.notNull(snapshot, "Tool snapshot must not be null");
        Assert.notNull(name, "Tool name must not be null");
        Assert.notNull(input, "Tool input must not be null");
        ToolRegistration<?> registration = snapshot.registration(name)
                .orElseThrow(() -> new ToolExecutionFailure(INVALID_INPUT, "The tool input is invalid."));
        return invokeRegistration(registration, input);
    }

    private <T> String invokeTyped(ToolRegistration<T> registration, String rawInput) {
        T input;
        try {
            input = jsonCodec.decodeBounded(rawInput, registration.inputType(), MAX_INPUT_UTF8_BYTES);
        } catch (JsonContractException exception) {
            throw new ToolExecutionFailure(INVALID_INPUT, "The tool input is invalid.");
        } catch (RuntimeException exception) {
            throw new ToolExecutionFailure(INVALID_RESPONSE, "The tool response is invalid.");
        }
        try {
            return Objects.requireNonNull(registration.executor().execute(input), "Tool output must not be null");
        } catch (ToolExecutionFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ToolExecutionFailure(INVALID_RESPONSE, "The tool response is invalid.");
        }
    }

    @SuppressWarnings("unchecked")
    private String invokeRegistration(ToolRegistration<?> registration, String rawInput) {
        return invokeTyped((ToolRegistration<Object>) registration, rawInput);
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
}
