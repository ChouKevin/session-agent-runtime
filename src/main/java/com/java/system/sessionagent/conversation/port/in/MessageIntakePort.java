package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;

public interface MessageIntakePort {

    MessageReceipt receive(IncomingMessage incomingMessage);
}
