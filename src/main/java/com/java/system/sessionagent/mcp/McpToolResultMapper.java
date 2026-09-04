package com.java.system.sessionagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.port.ToolOutput;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import org.springframework.util.Assert;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

final class McpToolResultMapper {

    private static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    private static final String TOOL_CONNECTION_FAILED = "TOOL_CONNECTION_FAILED";
    private static final String TOOL_PROTOCOL_ERROR = "TOOL_PROTOCOL_ERROR";
    private static final String TIMEOUT_MESSAGE = "The tool request timed out.";
    private static final String CONNECTION_MESSAGE = "The tool connection is unavailable.";
    private static final String PROTOCOL_MESSAGE = "The tool returned an invalid response.";

    private final ObjectMapper objectMapper;

    McpToolResultMapper(ObjectMapper objectMapper) {
        Assert.notNull(objectMapper, "Object mapper must not be null");
        this.objectMapper = objectMapper;
    }

    ToolOutput map(McpSchema.CallToolResult callResult) {
        Assert.notNull(callResult, "MCP tool result must not be null");
        Object source = Optional.ofNullable(callResult.structuredContent()).orElse(callResult.content());
        return mapStructuredValue(source, Boolean.TRUE.equals(callResult.isError()));
    }

    ToolOutput mapStructuredValue(Object source, boolean isError) {
        try {
            JsonNode tree = objectMapper.valueToTree(source);
            Object result = objectMapper.treeToValue(tree, Object.class);
            return new ToolOutput(isError, result);
        } catch (RuntimeException exception) {
            return protocolFailure();
        } catch (Exception exception) {
            return protocolFailure();
        }
    }

    Optional<ToolOutput> mapRuntimeFailure(Throwable failure) {
        Assert.notNull(failure, "MCP failure must not be null");
        Throwable current = failure;
        while (true) {
            if (current instanceof TimeoutException || current instanceof HttpTimeoutException) {
                return Optional.of(ToolOutput.runtimeFailure(TOOL_TIMEOUT, TIMEOUT_MESSAGE));
            }
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof McpTransportException || current instanceof McpTransportSessionClosedException) {
                return Optional.of(ToolOutput.runtimeFailure(TOOL_CONNECTION_FAILED, CONNECTION_MESSAGE));
            }
            if (current instanceof McpError) {
                return Optional.of(protocolFailure());
            }
            Throwable cause = current.getCause();
            if (java.util.Objects.isNull(cause) || cause == current) { // cs-allow JDK throwable traversal requires identity comparison
                return Optional.empty();
            }
            current = cause;
        }
    }

    ToolOutput runtimeFailure(Throwable failure) {
        return mapRuntimeFailure(failure).orElseThrow(() -> new IllegalArgumentException("Unsupported MCP runtime failure"));
    }

    ToolOutput protocolFailure() {
        return ToolOutput.runtimeFailure(TOOL_PROTOCOL_ERROR, PROTOCOL_MESSAGE);
    }

    ToolOutput connectionFailure() {
        return ToolOutput.runtimeFailure(TOOL_CONNECTION_FAILED, CONNECTION_MESSAGE);
    }
}
