package com.java.system.sessionagent.model.adapter.springai;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;

public final class SpringAiMessagePartsHandler implements SpringAiContinuationHandler {

    private static final String FORMAT = "spring-ai-message-parts-v1";
    private static final TypeReference<List<MessagePart>> PARTS = new TypeReference<>() { };
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .addMixIn(MessagePart.class, StoredPart.class).build();

    private final ModelRouteId routeId;

    public SpringAiMessagePartsHandler(ModelRouteId routeId) {
        Assert.notNull(routeId, "Model route ID must not be null");
        this.routeId = routeId;
    }

    @Override
    public ModelRouteId routeId() {
        return routeId;
    }

    @Override
    public Optional<ModelContinuation> capture(AssistantMessage message) {
        Assert.notNull(message, "Provider assistant message must not be null");
        try {
            return Optional.of(new ModelContinuation(routeId, FORMAT,
                    OBJECT_MAPPER.writerFor(PARTS).writeValueAsBytes(message.getParts())));
        } catch (JacksonException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    @Override
    public List<MessagePart> restore(ModelContinuation continuation) {
        Assert.notNull(continuation, "Model continuation must not be null");
        if (!routeId.equals(continuation.modelRouteId()) || !FORMAT.equals(continuation.format())) {
            throw ModelCallFailure.terminal();
        }
        try {
            List<MessagePart> parts = OBJECT_MAPPER.readValue(continuation.payload(), PARTS);
            Assert.notEmpty(parts, "Stored provider parts must not be empty");
            Assert.noNullElements(parts, "Stored provider parts must not contain null");
            return List.copyOf(parts);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw ModelCallFailure.terminal();
        }
    }

    // RC1's visible type discriminator makes UnknownPart's tree deserializer consume
    // a separate type-only object in a list. Stored parts use known subtype names;
    // keep the discriminator for dispatch, but do not feed it to the part body.
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownPart.class)
    private interface StoredPart {
    }
}
