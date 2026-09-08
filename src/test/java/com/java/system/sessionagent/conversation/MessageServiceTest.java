package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelSelectionLookup;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    @Test
    void forwards_the_unchanged_inbound_message_and_receipt() {
        ConversationStore store = mock(ConversationStore.class);
        IncomingMessage incoming = new IncomingMessage("thread", "alice", "source", "hello");
        MessageReceipt receipt = new MessageReceipt(new SessionId("session"), new MessageJobId("job"));
        when(store.receive(eq(incoming), any(ModelSelection.class), any(ModelSelectionLookup.class))).thenReturn(receipt);

        assertThat(new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry()).receive(incoming))
                .isEqualTo(receipt);
    }

    @Test
    void preserves_the_closed_message_conflict_at_the_intake_boundary() {
        ConversationStore store = mock(ConversationStore.class);
        IncomingMessage incoming = new IncomingMessage("thread", "alice", "source", "hello");
        when(store.receive(eq(incoming), any(ModelSelection.class), any(ModelSelectionLookup.class)))
                .thenThrow(new MessageConflictException());

        assertThatThrownBy(() -> new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry())
                .receive(incoming))
                .isInstanceOf(MessageConflictException.class);
    }
}
