package com.java.system.sessionagent.tool.port;

public interface ToolCatalog {

    ToolSnapshot snapshot();

    default ToolSnapshot snapshot(String sessionId, String messageJobId) {
        return snapshot();
    }
}
