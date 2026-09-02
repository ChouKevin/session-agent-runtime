@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "tool :: application",
        "mcp",
        "conversation :: application", "conversation :: domain", "conversation :: port.in", "conversation :: port.out",
        "semantic", "semantic :: domain", "semantic :: http", "semantic :: tool", "model", "storage", "web", "worker"
})
package com.java.system.sessionagent.bootstrap;
