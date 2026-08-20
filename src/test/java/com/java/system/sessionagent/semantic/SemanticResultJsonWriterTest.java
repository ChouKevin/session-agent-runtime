package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.dto.ProviderDtos;
import com.java.system.sessionagent.semantic.json.SemanticResultJsonWriter;
import com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

class SemanticResultJsonWriterTest {

    @Test
    void recursively_removes_only_provider_follow_up_controls_from_a_rich_typed_response() throws Exception {
        ProviderDtos.OutgoingCallGraphResponse response = JsonMapper.builder().build().readValue("""
                {"status":"COMPLETE","analyzedRevision":"revision-42","rootNodeId":"root",
                 "traversal":{"requestedDepth":2,"expandedNodeCount":1,"nodeBudget":100,"rootDirectCallsComplete":true,"limitReason":null},
                 "nodes":[{"nodeId":"root","target":{"sourceType":{"javaType":{"packageName":"com.example","className":"Payments"},"sourceFile":"src/Payments.java"},"methodName":"pay","parameterTypes":[]},"externalSymbol":null,"contentState":"RESOLVED","traversalState":"EXPANDED","dispatchKind":"DIRECT","declarationRange":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"availableFollowUps":[]}],
                 "edges":[],"warnings":[{"code":"NOTE","message":"availableFollowUps","nodeId":"root","callExpression":"pay()","callSite":{"sourceFile":"src/Payments.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},"candidates":[],"availableFollowUps":[]}],"errors":[]}
                """, ProviderDtos.OutgoingCallGraphResponse.class);

        String json = new SemanticResultJsonWriter().write(response);
        JsonNode target = JsonMapper.builder().build().readTree(json).path("nodes").get(0).path("target");

        assertTrue(json.contains("rootNodeId"));
        assertTrue(json.contains("expandedNodeCount"));
        assertTrue(json.contains("\"message\":\"availableFollowUps\""));
        assertFalse(json.contains("\"availableFollowUps\":"));
        assertEquals("src/Payments.java", target.path("sourceType").path("sourceFile").asString());
        assertEquals("com.example", target.path("sourceType").path("javaType").path("packageName").asString());
        assertEquals("Payments", target.path("sourceType").path("javaType").path("className").asString());
        assertFalse(target.has("sourceFile"));
        assertFalse(target.has("packageName"));
        assertFalse(target.has("className"));
    }

    @Test
    void writes_a_real_concept_response_that_the_strict_result_envelope_accepts() throws Exception {
        ProviderDtos.DiscoverConceptsResponse response = JsonMapper.builder().build().readValue("""
                {"repoId":"payment-service","analyzedRevision":"FIXTURE","normalizedTerms":["payment method"],
                 "searchedKinds":["TYPE"],"supportedKinds":["TYPE"],"limitations":[],
                 "candidates":[{"identity":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example.payment","className":"PaymentMethod"},"sourceFile":"src/main/java/com/example/payment/PaymentMethod.java"}},
                 "displayValue":"PaymentMethod","matchedTerms":["payment method"],"authority":"SYNTAX_DECLARED",
                 "evidence":[{"identity":{"kind":"TYPE","sourceType":{"javaType":{"packageName":"com.example.payment","className":"PaymentMethod"},"sourceFile":"src/main/java/com/example/payment/PaymentMethod.java"}}}],
                 "availableFollowUps":[]}],
                 "page":{"offset":0,"limit":50,"returnedCount":1,"totalCount":1,"hasMore":false},
                 "coverage":{"status":"COMPLETE","scannedFileCount":6,"extractedFileCount":6,"syntaxFailedFileCount":0},
                 "issueSummaries":[],"availableFollowUps":[],"unavailableFollowUps":[]}
                """, ProviderDtos.DiscoverConceptsResponse.class);

        String json = new SemanticResultJsonWriter().write(response);
        ToolExecution execution = new ToolExecution(new ToolName("codebase_discover_concepts"), "v1", ToolKind.SOURCE,
                "{}", Optional.of("payment-service"), Optional.of("FIXTURE"), json, true);

        new ToolResultEnvelopeFactory().validate(execution);
        assertFalse(json.contains(":null"));
    }
}
