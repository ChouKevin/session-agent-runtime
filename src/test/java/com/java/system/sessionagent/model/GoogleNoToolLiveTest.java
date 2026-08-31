package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleNoToolLiveTest {

    @Test
    void keeps_the_provider_boundary_to_the_single_final_respond_operation() {
        assertThat(ConversationModel.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("respond");
    }
}
