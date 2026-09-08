package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredModelsTest {

    @Test
    void closes_each_owned_provider_resource_once() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ConversationModel model = Mockito.mock(ConversationModel.class);
        ConfiguredModels configuredModels = new ConfiguredModels(Map.of("google-primary",
                new ConfiguredModelBinding("google-primary", "v1:fingerprint", model)), "google-primary",
                List.of(closes::incrementAndGet));

        configuredModels.close();

        assertThat(closes).hasValue(1);
    }
}
