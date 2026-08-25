package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.semantic.tool.input.GetCodeFactInput;
import com.java.system.sessionagent.semantic.tool.input.SearchCodeFactsInput;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticToolContractTest {

    @Test
    void exposes_only_code_fact_tools_with_required_repository_and_revision_contracts() {
        SemanticToolProvider provider = new SemanticToolProvider(() -> List.of(), new SemanticSourceClient(RestClient.builder()
                .baseUrl("https://semantic.test").build()));
        ToolSnapshot snapshot = new DirectToolRegistry(provider.registrations()).snapshot();

        assertThat(snapshot.definitions()).extracting(definition -> definition.name().value())
                .contains("codebase_search_code_facts", "codebase_get_code_fact")
                .doesNotContain("codebase_discover_concepts", "codebase_resolve_concept");
        assertThat(snapshot.definitions().stream().filter(definition -> definition.name()
                .equals(new ToolName("codebase_search_code_facts"))).findFirst().orElseThrow().inputSchema())
                .contains("repositoryId", "revision", "query");
    }

    @Test
    void code_fact_inputs_keep_required_identity_fields() {
        assertThat(SearchCodeFactsInput.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("repositoryId", "revision", "query", "kinds", "packagePrefix", "offset", "limit");
        assertThat(GetCodeFactInput.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("repositoryId", "revision", "factId");
    }
}
