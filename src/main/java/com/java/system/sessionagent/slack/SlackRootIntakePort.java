package com.java.system.sessionagent.slack;

public interface SlackRootIntakePort {

    SlackIntakeResult receive(SlackRootIntake intake);
}
