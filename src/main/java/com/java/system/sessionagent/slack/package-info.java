@org.springframework.modulith.ApplicationModule(allowedDependencies = {
        "conversation :: domain", "conversation :: port.in", "storage :: adapter.postgres.jdbc", "artifact :: port"
})
package com.java.system.sessionagent.slack;
