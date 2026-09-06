package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;

public interface MessageJobPort {

    MessageJobProcessingResult process(MessageWorkClaim claim, WorkGuard workGuard);
}
