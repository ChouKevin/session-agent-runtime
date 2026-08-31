package com.java.system.sessionagent.live;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAgentLiveIT {

    @Test
    void exposes_only_final_durable_message_variants_to_live_runtime_consumers() {
        assertThat(SessionMessage.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
                UserMessage.class, AssistantMessage.class, ToolObservation.class, RuntimeMessage.class);
    }
}
