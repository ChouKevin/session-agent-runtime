@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "tool :: port",
        "skill :: port", "skill :: configuration", "skill :: adapter.filesystem", "skill :: adapter.tool",
        "mcp :: configuration", "mcp :: adapter.sdk", "mcp :: adapter.actuator",
        "conversation :: application", "conversation :: domain", "conversation :: port.in", "conversation :: port.out",
        "model :: adapter.springai", "storage :: adapter.postgres", "storage :: adapter.postgres.jdbc", "web",
        "worker :: application", "worker :: configuration",
        "slack :: domain", "slack :: application", "slack :: port.out",
        "slack :: adapter.sdk", "slack :: adapter.postgres", "slack :: configuration"
})
package com.java.system.sessionagent.bootstrap;
