package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositoryRevision;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SemanticRepositoryClientTest {

    @Test
    void lists_exact_published_repository_revision_pairs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://semantic.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://semantic.test/v1/repositories")).andRespond(withSuccess("""
                [{"repositoryId":{"value":"payment-service"},"revision":{"value":"1111111111111111111111111111111111111111"},"generationId":{"value":"generation-a"},"manifestDigest":{"value":"digest-a"},"publishedAt":"2026-08-25T00:00:00Z"}]
                """, MediaType.APPLICATION_JSON));

        List<RepositorySummary> repositories = new SemanticRepositoryClient(builder.build()).listRepositories();

        assertThat(repositories).containsExactly(new RepositorySummary(new RepositoryId("payment-service"),
                new RepositoryRevision("1111111111111111111111111111111111111111")));
        server.verify();
    }
}
