package com.java.system.sessionagent.slack;

public interface SlackRootIntakePort {

    SlackEventOutcome receive(SlackRootIntake intake);
}
