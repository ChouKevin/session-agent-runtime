package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackIntakeResult;

public interface MessageSubmissionPort {

    SlackIntakeResult receive(MessageSubmission intake);
}
