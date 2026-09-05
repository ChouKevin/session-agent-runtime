package com.java.system.sessionagent.tool.port;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolSnapshot implements AutoCloseable {

    private static final String TOOL_NOT_AVAILABLE = "TOOL_NOT_AVAILABLE";
    private static final String TOOL_NOT_AVAILABLE_MESSAGE = "The requested tool is not available.";

    private final Map<ToolName, ToolBinding> bindings;
    private final List<ToolDefinition> definitions;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ToolSnapshot(List<ToolBinding> sourceBindings) {
        this(sourceBindings, () -> { });
    }

    public ToolSnapshot(List<ToolBinding> sourceBindings, Runnable closeAction) {
        Assert.notNull(sourceBindings, "Tool bindings must not be null");
        Assert.notNull(closeAction, "Tool snapshot close action must not be null");
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
        definitions = bindings.values().stream().map(binding -> binding.definition()).toList();
        this.closeAction = closeAction;
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAction.run();
        }
    }
}
