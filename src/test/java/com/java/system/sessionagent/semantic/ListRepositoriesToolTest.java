package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ListRepositoriesToolTest {
    @Test void returns_semantic_owned_catalog_text_through_the_opaque_runtime_boundary() {
        DirectToolRegistry registry = new DirectToolRegistry(new SemanticToolProvider(() -> List.of(new RepositorySummary(new RepositoryId("repo"), new RepositoryRevision("revision")))).registrations());
        assertThat(registry.invoke(registry.snapshot(), new ToolName("list_repositories"), "{}")).contains("repo", "revision");
    }
}
