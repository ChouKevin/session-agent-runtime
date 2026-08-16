package com.java.system.sessionagent.semantic;

import com.java.system.sessionagent.semantic.dto.ProviderDtos;
import com.java.system.sessionagent.semantic.json.SemanticResultJsonWriter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
