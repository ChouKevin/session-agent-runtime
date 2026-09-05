package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolDefinition;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ContextUsageEstimator {

    public String requestShapeFingerprint(ContextUsageProjection projection) {
        Assert.notNull(projection, "Context usage projection must not be null");
        return sha256(serializedRequestShape(projection));
    }

    public ContextEstimate estimate(ContextUsageProjection projection, Optional<ContextUsageCheckpoint> checkpoint) {
        Assert.notNull(projection, "Context usage projection must not be null");
        Assert.notNull(checkpoint, "Context usage checkpoint must not be null");
        String fingerprint = requestShapeFingerprint(projection);
        Optional<ContextUsageCheckpoint> matchingCheckpoint = checkpoint.filter(candidate -> matches(projection, fingerprint, candidate));
        if (matchingCheckpoint.isPresent()) {
            ContextUsageCheckpoint selected = matchingCheckpoint.orElseThrow();
            Optional<List<SessionMessage>> suffix = suffixAfter(projection.history(), selected.responseBoundary());
            if (suffix.isPresent()) {
                return new ContextEstimate(selected.totalTokens() + estimateTokens(serializedHistory(suffix.orElseThrow())),
                        ContextEstimate.Basis.PROVIDER_PLUS_TRAILING_ESTIMATE);
            }
        }
        return new ContextEstimate(estimateTokens(serializedRequestShape(projection) + serializedHistory(projection.history())),
                ContextEstimate.Basis.FULL_ESTIMATE);
    }

    private static boolean matches(ContextUsageProjection projection, String fingerprint, ContextUsageCheckpoint checkpoint) {
        return projection.model().equals(checkpoint.model())
                && projection.compactGeneration() == checkpoint.compactGeneration()
                && fingerprint.equals(checkpoint.requestShapeFingerprint());
    }

    private static Optional<List<SessionMessage>> suffixAfter(List<SessionMessage> history, SessionSequence responseBoundary) {
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).sequence().equals(responseBoundary)) {
                return Optional.of(history.subList(index + 1, history.size()));
            }
        }
        return Optional.empty();
    }

    private static long estimateTokens(String serialized) {
        return Math.max(1, (serialized.length() + 3L) / 4L);
    }

    private static String serializedRequestShape(ContextUsageProjection projection) {
        StringBuilder serialized = new StringBuilder();
        appendString(serialized, "system", projection.systemPrompt());
        projection.toolDefinitions().stream()
                .sorted(Comparator.comparing(definition -> definition.name().value()))
                .forEach(definition -> appendToolDefinition(serialized, definition));
        return serialized.toString();
    }

    private static void appendToolDefinition(StringBuilder serialized, ToolDefinition definition) {
        appendString(serialized, "tool-name", definition.name().value());
        appendString(serialized, "tool-description", definition.description());
        appendValue(serialized, definition.inputSchema());
    }

    private static String serializedHistory(List<SessionMessage> history) {
        StringBuilder serialized = new StringBuilder();
        for (SessionMessage message : history) {
            if (message instanceof UserMessage userMessage) {
                appendString(serialized, "user", userMessage.participantId() + ": " + userMessage.message());
            } else if (message instanceof AssistantMessage assistantMessage) {
                appendString(serialized, "assistant", assistantMessage.message());
            } else if (message instanceof RuntimeMessage runtimeMessage) {
                appendString(serialized, "runtime", "Runtime: " + runtimeMessage.code() + " - " + runtimeMessage.message());
            } else if (message instanceof AssistantToolCallsMessage calls) {
                calls.message().ifPresent(text -> appendString(serialized, "assistant-tool-text", text));
                for (ToolRequest request : calls.requests()) {
                    appendString(serialized, "assistant-tool-id", request.toolCallId().value());
                    appendString(serialized, "assistant-tool-name", request.toolName().value());
                    appendValue(serialized, request.arguments());
                }
            } else if (message instanceof ToolObservation observation) {
                appendString(serialized, "tool-id", observation.toolCallId().value());
                appendString(serialized, "tool-name", observation.toolName());
                appendValue(serialized, observation.output());
            } else {
                throw new IllegalArgumentException("Unsupported model-visible message type");
            }
        }
        return serialized.toString();
    }

    private static void appendValue(StringBuilder serialized, Object value) {
        if (value instanceof Map<?, ?> map) {
            serialized.append('{');
            map.entrySet().stream()
                    .map(entry -> new AbstractMap.SimpleImmutableEntry<>(String.valueOf(entry.getKey()), entry.getValue()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        appendString(serialized, "key", entry.getKey());
                        appendValue(serialized, entry.getValue());
                    });
            serialized.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            serialized.append('[');
            for (Object element : list) {
                appendValue(serialized, element);
            }
            serialized.append(']');
            return;
        }
        if (value instanceof String string) {
            appendString(serialized, "string", string);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            appendString(serialized, "scalar", value.toString());
            return;
        }
        if (Objects.isNull(value)) {
            appendString(serialized, "null", "");
            return;
        }
        throw new IllegalArgumentException("Model-visible structured values must be JSON-compatible");
    }

    private static void appendString(StringBuilder serialized, String label, String value) {
        serialized.append(label).append(':').append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
