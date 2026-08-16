package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositorySummary;

import java.util.List;

@FunctionalInterface
public interface RepositoryCatalog {

    List<RepositorySummary> listRepositories();
}
