package com.java.system.sessionagent.slack.domain;

public enum SlackIntakeDisposition {
    NEW_ACCEPTED,
    DUPLICATE_ACCEPTED,
    NEW_BUSY,
    DUPLICATE_BUSY,
    NEW_COMMAND,
    DUPLICATE_COMMAND,
    NEW_IGNORED,
    DUPLICATE_IGNORED
}
