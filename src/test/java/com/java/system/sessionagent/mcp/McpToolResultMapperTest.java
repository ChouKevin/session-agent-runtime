package com.java.system.sessionagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.port.ToolOutput;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolResultMapperTest {

    private final McpToolResultMapper mapper = new McpToolResultMapper(new ObjectMapper());

    @Test
    void preserves_structured_content_and_provider_error_without_sdk_values() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ignored")), true,
                Map.of("items", List.of(Map.of("name", "payments"))), Map.of());

        ToolOutput output = mapper.map(result);

        assertThat(output.isError()).isTrue();
        assertThat(output.result()).isEqualTo(Map.of("items", List.of(Map.of("name", "payments"))));
        assertThat(containsSdkValue(output.result())).isFalse();
    }

    @Test
    void normalizes_standard_content_when_structured_content_is_absent() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("source text")), false, null, Map.of());

        ToolOutput output = mapper.map(result);

        assertThat(output.isError()).isFalse();
        assertThat(output.result()).isInstanceOf(List.class);
        assertThat(containsSdkValue(output.result())).isFalse();
    }

    @Test
    void preserves_a_valid_null_result_root_in_the_outer_output_wrapper() {
        ToolOutput output = mapper.mapStructuredValue(null, false);

        assertThat(output.asStructuredValue()).containsEntry("result", null);
    }

    @Test
    void creates_safe_timeout_failure_without_exception_details() {
        ToolOutput output = mapper.runtimeFailure(new java.net.http.HttpTimeoutException(
                "https://mcp.example/custom/mcp Authorization: Bearer secret-token"));

        assertThat(output.asStructuredValue()).isEqualTo(Map.of(
                "isError", true,
                "result", Map.of("code", "TOOL_TIMEOUT", "message", "The tool request timed out.")));
    }

    private static boolean containsSdkValue(Object value) {
        if (java.util.Objects.isNull(value)) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(McpToolResultMapperTest::containsSdkValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(McpToolResultMapperTest::containsSdkValue);
        }
        return value.getClass().getPackageName().startsWith("io.modelcontextprotocol")
                || value.getClass().getPackageName().startsWith("org.springframework.ai.mcp");
    }
}
