package com.java.system.sessionagent.conversation.port.out;

import org.springframework.util.Assert;

public sealed interface RevisionLookup {

    record CurrentRevision(String revision) implements RevisionLookup {

        public CurrentRevision {
            Assert.hasText(revision, "Revision must not be blank");
        }
    }

    record UnknownRepository() implements RevisionLookup {
    }

    record TemporaryFailure() implements RevisionLookup {
    }

    record Forbidden() implements RevisionLookup {
    }

    record InvalidResponse() implements RevisionLookup {
    }
}
