package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.Objects;

public record ModelContinuation(ModelRouteId modelRouteId, String format, byte[] payload) {

    public ModelContinuation {
        Assert.notNull(modelRouteId, "Model route ID must not be null");
        Assert.hasText(format, "Model continuation format must not be blank");
        Assert.notNull(payload, "Model continuation payload must not be null");
        Assert.isTrue(payload.length > 0, "Model continuation payload must not be empty");
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) { // cs-allow identity fast path
            return true;
        }
        return other instanceof ModelContinuation that
                && modelRouteId.equals(that.modelRouteId)
                && format.equals(that.format)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelRouteId, format, Arrays.hashCode(payload));
    }
}
