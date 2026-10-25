package com.java.system.sessionagent.tool;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import java.util.UUID;
import java.time.Instant;

public final class TestToolInvocation {
    private TestToolInvocation() {}
    public static ToolInvocation invocation() {
        return of(new MessageWorkClaim(
                new MessageJobId("00000000-0000-0000-0000-000000000001"),
                new SessionId("00000000-0000-0000-0000-000000000002"),
                new ModelSelection("test", "test"), "test-worker", 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(60)), 1, 0);
    }
    public static ToolInvocation of(MessageWorkClaim claim, long call, int ordinal) {
        return new ToolInvocation(new ToolExecutionContext(UUID.fromString(claim.sessionId().value()),
                UUID.fromString(claim.messageJobId().value()), call, ordinal),
                new ToolExecutionAuthority(claim.workerId(), claim.claimNumber()));
    }
}
