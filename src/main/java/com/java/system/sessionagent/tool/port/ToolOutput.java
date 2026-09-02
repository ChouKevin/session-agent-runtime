package com.java.system.sessionagent.tool.port;

import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ToolOutput(boolean isError, Object result) {

    private static final Set<String> RUNTIME_FAILURE_CODES = Set.of(
            "TOOL_TIMEOUT",
            "TOOL_CONNECTION_FAILED",
            "TOOL_NOT_AVAILABLE",
            "TOOL_PROTOCOL_ERROR");

    public static ToolOutput runtimeFailure(String code, String safeMessage) {
        Assert.isTrue(RUNTIME_FAILURE_CODES.contains(code), "Unsupported runtime tool failure code");
        Assert.hasText(safeMessage, "Runtime tool failure message must not be blank");
        return new ToolOutput(true, Map.of("code", code, "message", safeMessage));
    }

    public Map<String, Object> asStructuredValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("isError", isError);
        value.put("result", result);
        return Collections.unmodifiableMap(value);
    }
}
