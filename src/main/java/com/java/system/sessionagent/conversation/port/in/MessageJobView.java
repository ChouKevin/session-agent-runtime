package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.JobStatus;

import java.util.Optional;

public record MessageJobView(
        String messageJobId,
        String sessionId,
        JobStatus status,
        int retryCount,
        int modelCallCount,
        Optional<JobProgress> progress,
        Optional<JobFailure> failure) {
}
