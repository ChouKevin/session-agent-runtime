package com.java.system.sessionagent.mcp.adapter.sdk;

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
    void preserves_text_evidence_alongside_structured_content_and_provider_error_without_sdk_values() {
        String evidence = "13 | observed value\r\n14 | \r\n";
        Map<String, Object> structured = Map.of("value", "observed value\r\n\r\n", "startLine", 13);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(evidence).build()), true,
                structured, Map.of("clientOnly", "not model evidence"));

        ToolOutput output = mapper.map(result);
        Map<?, ?> body = (Map<?, ?>) output.result();

        assertThat(output.isError()).isTrue();
        assertThat(body.get("structuredContent")).isEqualTo(structured);
        assertThat((List<?>) body.get("content")).singleElement().satisfies(content -> {
            assertThat(((Map<?, ?>) content).get("type")).isEqualTo("text");
            assertThat(((Map<?, ?>) content).get("text")).isEqualTo(evidence);
        });
        assertThat(body.containsKey("_meta")).isFalse();
        assertThat(containsSdkValue(output.result())).isFalse();
    }

    @Test
    void normalizes_standard_content_when_structured_content_is_absent() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("source text").build()), false, null, Map.of());

        ToolOutput output = mapper.map(result);

        assertThat(output.isError()).isFalse();
        Map<?, ?> body = (Map<?, ?>) output.result();
        assertThat(body.containsKey("structuredContent")).isFalse();
        assertThat((List<?>) body.get("content")).singleElement().satisfies(content -> {
            assertThat(content).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) content).get("type")).isEqualTo("text");
            assertThat(((Map<?, ?>) content).get("text")).isEqualTo("source text");
        });
        assertThat(containsSdkValue(output.result())).isFalse();
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
