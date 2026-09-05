package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.MessageReceipt;

public interface SlackRootIntakePort {

    MessageReceipt receive(SlackRootIntake intake);
}
