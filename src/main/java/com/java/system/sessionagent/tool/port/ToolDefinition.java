package com.java.system.sessionagent.tool.port;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolDefinition(ToolName name, String description, Map<String, Object> inputSchema) {

    public static ToolDefinition fromExposedName(String exposedName, String description, Map<String, Object> inputSchema) {
        return new ToolDefinition(new ToolName(exposedName), description, inputSchema);
    }

    public ToolDefinition {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(description, "Tool description must not be blank");
        Assert.notNull(inputSchema, "Tool input schema must not be null");
        inputSchema = immutableMap(inputSchema);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Assert.notNull(key, "Tool schema map keys must not be null");
            copied.put(key, immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableValue(Object source) {
        if (source instanceof Map<?, ?> sourceMap) {
            LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                Assert.isInstanceOf(String.class, entry.getKey(), "Tool schema map keys must be strings");
                String key = (String) entry.getKey();
                copied.put(key, immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (source instanceof List<?> sourceList) {
            ArrayList<Object> copied = new ArrayList<>();
            for (Object value : sourceList) {
                copied.add(immutableValue(value));
            }
            return Collections.unmodifiableList(copied);
        }
        Assert.isTrue(source instanceof String || source instanceof Number || source instanceof Boolean || java.util.Objects.isNull(source),
                "Tool schema values must be JSON-compatible");
        return source;
    }
}
