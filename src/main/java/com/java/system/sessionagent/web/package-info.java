@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "conversation :: domain", "conversation :: port.in", "tool :: domain",
        "slack :: domain", "slack :: port.out", "skill :: domain"
})
package com.java.system.sessionagent.web;
