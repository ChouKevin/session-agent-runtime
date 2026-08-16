@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "conversation :: domain", "conversation :: port.out",
        "tool :: domain", "tool :: application", "tool :: json"})
package com.java.system.sessionagent.model;
