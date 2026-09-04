package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolRequest(ToolCallId toolCallId, ToolName toolName, Map<String, Object> arguments) {

    public ToolRequest {
        Assert.notNull(toolCallId, "Tool call ID must not be null");
        Assert.notNull(toolName, "Tool name must not be null");
        Assert.notNull(arguments, "Tool arguments must not be null");
        arguments = freezeArguments(arguments);
    }

    public static Map<String, Object> freezeArguments(Map<String, Object> arguments) {
        Assert.notNull(arguments, "Tool arguments must not be null");
        return freezeMap(arguments);
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Assert.isInstanceOf(String.class, key, "Tool argument keys must be strings");
            frozen.put((String) key, freezeValue(value));
        });
        return Collections.unmodifiableMap(frozen);
    }

    private static List<Object> freezeList(List<?> source) {
        List<Object> frozen = new ArrayList<>();
        source.forEach(value -> frozen.add(freezeValue(value)));
        return Collections.unmodifiableList(frozen);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof List<?> list) {
            return freezeList(list);
        }
        return value;
    }
}
