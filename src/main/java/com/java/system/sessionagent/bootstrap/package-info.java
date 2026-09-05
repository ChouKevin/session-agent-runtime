@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "tool :: port",
        "mcp",
        "conversation :: application", "conversation :: domain", "conversation :: port.in", "conversation :: port.out",
        "model", "storage", "web", "worker", "slack"
})
package com.java.system.sessionagent.bootstrap;
