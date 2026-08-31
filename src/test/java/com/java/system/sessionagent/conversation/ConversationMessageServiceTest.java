package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMessageServiceTest {

    @Test
    void forwards_the_unchanged_inbound_message_and_receipt() {
        ConversationStore store = mock(ConversationStore.class);
        IncomingMessage incoming = new IncomingMessage("thread", "alice", "source", "hello");
        MessageReceipt receipt = new MessageReceipt(new SessionId("session"), new MessageJobId("job"));
        when(store.receive(incoming)).thenReturn(receipt);

        assertThat(new ConversationMessageService(store).receive(incoming)).isEqualTo(receipt);
    }

    @Test
    void preserves_the_closed_message_conflict_at_the_intake_boundary() {
        ConversationStore store = mock(ConversationStore.class);
        IncomingMessage incoming = new IncomingMessage("thread", "alice", "source", "hello");
        when(store.receive(incoming)).thenThrow(new MessageConflictException());

        assertThatThrownBy(() -> new ConversationMessageService(store).receive(incoming))
                .isInstanceOf(MessageConflictException.class);
    }
}
