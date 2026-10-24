@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "conversation :: domain", "conversation :: port.out",
        "tool :: domain", "tool :: port", "skill :: domain"})
package com.java.system.sessionagent.model;
