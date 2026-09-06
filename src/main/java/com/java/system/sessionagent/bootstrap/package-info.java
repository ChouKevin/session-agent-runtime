@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "tool :: port",
        "mcp :: configuration", "mcp :: adapter.sdk", "mcp :: adapter.actuator",
        "conversation :: application", "conversation :: domain", "conversation :: port.in", "conversation :: port.out",
        "model :: adapter.springai", "storage :: adapter.postgres", "web",
        "worker :: application", "worker :: configuration",
        "slack :: domain", "slack :: application", "slack :: port.out",
        "slack :: adapter.sdk", "slack :: adapter.postgres", "slack :: configuration"
})
package com.java.system.sessionagent.bootstrap;
