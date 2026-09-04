package com.java.system.sessionagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GoogleGenAiThoughtSignatureHandler implements SpringAiContinuationHandler {

    private static final String FORMAT = "spring-ai-google-genai-thought-signatures-v1";
    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";

    private final ModelRouteId routeId;
    private final ObjectMapper objectMapper;

    public GoogleGenAiThoughtSignatureHandler(ModelRouteId routeId, ObjectMapper objectMapper) {
        Assert.notNull(routeId, "Model route ID must not be null");
        Assert.notNull(objectMapper, "Object mapper must not be null");
        this.routeId = routeId;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelRouteId routeId() {
        return routeId;
    }

    @Override
    public Optional<ModelContinuation> capture(AssistantMessage message) {
        Assert.notNull(message, "Provider assistant message must not be null");
        Map<String, Object> metadata = message.getMetadata();
        if (!metadata.containsKey(THOUGHT_SIGNATURES)) {
            return Optional.empty();
        }
        List<byte[]> signatures = signatures(metadata.get(THOUGHT_SIGNATURES));
        try {
            return Optional.of(new ModelContinuation(routeId, FORMAT, objectMapper.writeValueAsBytes(signatures)));
        } catch (JsonProcessingException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    @Override
    public Map<String, Object> restore(ModelContinuation continuation) {
        Assert.notNull(continuation, "Model continuation must not be null");
        if (!routeId.equals(continuation.modelRouteId()) || !FORMAT.equals(continuation.format())) {
            throw ModelCallFailure.terminal();
        }
        try {
            List<byte[]> signatures = signatures(objectMapper.readValue(continuation.payload(), new TypeReference<List<byte[]>>() { }));
            return Map.of(THOUGHT_SIGNATURES, signatures);
        } catch (ModelCallFailure exception) {
            throw ModelCallFailure.terminal();
        } catch (IOException | IllegalArgumentException exception) {
            throw ModelCallFailure.terminal();
        }
    }

    private static List<byte[]> signatures(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw ModelCallFailure.correctable();
        }
        List<byte[]> copied = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof byte[] bytes)) {
                throw ModelCallFailure.correctable();
            }
            copied.add(Arrays.copyOf(bytes, bytes.length));
        }
        return List.copyOf(copied);
    }
}
