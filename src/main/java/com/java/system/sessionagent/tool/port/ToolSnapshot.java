package com.java.system.sessionagent.tool.port;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ToolSnapshot {

    private static final String TOOL_NOT_AVAILABLE = "TOOL_NOT_AVAILABLE";
    private static final String TOOL_NOT_AVAILABLE_MESSAGE = "The requested tool is not available.";

    private final Map<ToolName, ToolBinding> bindings;
    private final List<ToolDefinition> definitions;

    public ToolSnapshot(List<ToolBinding> sourceBindings) {
        Assert.notNull(sourceBindings, "Tool bindings must not be null");
        LinkedHashMap<ToolName, ToolBinding> copiedBindings = new LinkedHashMap<>();
        for (ToolBinding binding : sourceBindings) {
            ToolBinding retainedBinding = Objects.requireNonNull(binding, "Tool binding must not be null");
            ToolName name = retainedBinding.definition().name();
            if (copiedBindings.containsKey(name)) {
                throw new IllegalArgumentException("Tool names must be distinct");
            }
            copiedBindings.put(name, retainedBinding);
        }
        bindings = Collections.unmodifiableMap(copiedBindings);
        definitions = bindings.values().stream().map(ToolBinding::definition).toList();
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public ToolOutput invoke(ToolName name, Map<String, Object> arguments) {
        Assert.notNull(name, "Tool name must not be null");
        Assert.notNull(arguments, "Tool arguments must not be null");
        Optional<ToolBinding> binding = Optional.ofNullable(bindings.get(name));
        if (binding.isEmpty()) {
            return ToolOutput.runtimeFailure(TOOL_NOT_AVAILABLE, TOOL_NOT_AVAILABLE_MESSAGE);
        }
        ToolOutput output = binding.orElseThrow().executor().execute(arguments);
        return Objects.requireNonNull(output, "Tool output must not be null");
    }
}
