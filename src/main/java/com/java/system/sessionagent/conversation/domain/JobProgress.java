package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

public record JobProgress(JobPhase phase, Instant startedAt, Optional<String> toolName) {

    public JobProgress {
        Assert.notNull(phase, "Job phase must not be null");
        Assert.notNull(startedAt, "Phase start time must not be null");
        Assert.notNull(toolName, "Current tool name must not be null");
        Assert.isTrue((phase == JobPhase.TOOL_EXECUTION) == toolName.isPresent(),
                "Only tool execution must identify a current tool");
        toolName.ifPresent(ToolName::new);
    }
}
