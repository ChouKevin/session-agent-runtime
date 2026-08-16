package com.java.system.sessionagent.conversation.port.out;

@FunctionalInterface
public interface RepositoryRevisionReader {

    RevisionLookup read(String repositoryId);
}
