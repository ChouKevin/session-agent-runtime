package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.JobStatus;

import java.util.OptionalLong;

public record MessageJobView(
        String messageJobId,
        String sessionId,
        JobStatus status,
        int retryCount,
        int modelCallCount,
        OptionalLong replySequence) {
}
