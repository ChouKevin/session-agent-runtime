package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelCallRecord;

@FunctionalInterface
public interface ModelCallRecorder {

    void record(ModelCallRecord record);

    static ModelCallRecorder noop() {
        return record -> { };
    }
}
