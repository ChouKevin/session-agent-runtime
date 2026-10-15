package com.java.system.sessionagent.conversation.domain;

public final class JobCancellation {

    public static final String CODE = "JOB_CANCELLED";
    public static final String MESSAGE = "Job was cancelled.";
    public static final String TOOL_CODE = "TOOL_CANCELLED";
    public static final String TOOL_MESSAGE = "Tool was not executed because the job was cancelled.";

    private JobCancellation() {
    }
}
