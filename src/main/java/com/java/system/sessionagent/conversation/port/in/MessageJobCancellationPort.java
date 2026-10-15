package com.java.system.sessionagent.conversation.port.in;

import java.util.Optional;

public interface MessageJobCancellationPort {

    Optional<MessageJobView> cancel(String messageJobId);
}
