package com.java.system.sessionagent.conversation.port.out;

public final class JobCancellationRequestedException extends RuntimeException {

    public JobCancellationRequestedException() {
        super("Job cancellation was requested");
    }
}
