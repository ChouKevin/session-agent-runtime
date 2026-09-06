package com.java.system.sessionagent.web;

import com.java.system.sessionagent.bootstrap.RuntimeConfiguration;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageControllerTest {

    private static final String SESSION_ID = "a1d4cefe-d5b5-4f40-b9f6-beb41a6831af";
    private static final String JOB_ID = "f47bdb7e-75c3-4dbf-bdd4-1f8681705b62";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T10:00:01Z");

    @Test
    void serializes_each_final_history_message_with_only_its_type_specific_fields() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(queries.findJob(JOB_ID)).thenReturn(Optional.of(new MessageJobView(JOB_ID, SESSION_ID, JobStatus.DONE, 1, 2)));
        when(queries.messages(SESSION_ID)).thenReturn(Optional.of(List.of(
                new UserMessage(new SessionId(SESSION_ID), new SessionSequence(1), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.USER, "alice", "hello"),
                new AssistantToolCallsMessage(new SessionId(SESSION_ID), new SessionSequence(2), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("I will inspect it."), List.of(
                        new ToolRequest(new ToolCallId("call-1"), new com.java.system.sessionagent.tool.domain.ToolName("lookup"), Map.of("query", "fees")))),
                new ToolObservation(new SessionId(SESSION_ID), new SessionSequence(3), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.TOOL, new ToolCallId("call-1"), "lookup", Map.of("isError", false, "result", Map.of())),
                new AssistantMessage(new SessionId(SESSION_ID), new SessionSequence(4), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.ASSISTANT, "answer"),
                new RuntimeMessage(new SessionId(SESSION_ID), new SessionSequence(5), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.RUNTIME, "MODEL_UNAVAILABLE", "retry later"))));
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("USER"))
                .andExpect(jsonPath("$[0].participantId").value("alice"))
                .andExpect(jsonPath("$[0].toolName").doesNotExist())
                .andExpect(jsonPath("$[1].sequence").value(2))
                .andExpect(jsonPath("$[1].createdAt").exists())
                .andExpect(jsonPath("$[1].type").value("ASSISTANT_TOOL_CALLS"))
                .andExpect(jsonPath("$[1].messageJobId").value(JOB_ID))
                .andExpect(jsonPath("$[1].calls[0].toolCallId").value("call-1"))
                .andExpect(jsonPath("$[1].calls[0].toolName").value("lookup"))
                .andExpect(jsonPath("$[1].calls[0].arguments.query").value("fees"))
                .andExpect(jsonPath("$[1].observationId").doesNotExist())
                .andExpect(jsonPath("$[2].type").value("TOOL"))
                .andExpect(jsonPath("$[2].toolCallId").value("call-1"))
                .andExpect(jsonPath("$[2].toolName").value("lookup"))
                .andExpect(jsonPath("$[2].output.isError").value(false))
                .andExpect(jsonPath("$[2].input").doesNotExist())
                .andExpect(jsonPath("$[1].resultId").doesNotExist())
                .andExpect(jsonPath("$[1].toolVersion").doesNotExist())
                .andExpect(jsonPath("$[1].repositoryId").doesNotExist())
                .andExpect(jsonPath("$[1].revision").doesNotExist())
                .andExpect(jsonPath("$[1].feedbackCode").doesNotExist())
                .andExpect(jsonPath("$[1].terminal").doesNotExist())
                .andExpect(jsonPath("$[1].rejectedArguments").doesNotExist())
                .andExpect(jsonPath("$..continuation").doesNotExist())
                .andExpect(jsonPath("$..modelRouteId").doesNotExist())
                .andExpect(jsonPath("$..format").doesNotExist())
                .andExpect(jsonPath("$..payload").doesNotExist())
                .andExpect(jsonPath("$..thoughtSignatures").doesNotExist())
                .andExpect(jsonPath("$[3].type").value("ASSISTANT"))
                .andExpect(jsonPath("$[3].message").value("answer"))
                .andExpect(jsonPath("$[4].type").value("RUNTIME"))
                .andExpect(jsonPath("$[4].code").value("MODEL_UNAVAILABLE"));
        mvc.perform(get("/internal/message-jobs/{messageJobId}", JOB_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replySequence").doesNotExist());
    }

    @Test
    void has_no_result_lookup_endpoint() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(get("/internal/results/{resultId}", "4455b5ba-7b93-44cf-bd76-0d756e325eb5"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejects_invalid_history_pagination_without_changing_the_complete_history_contract() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(queries.messages(SESSION_ID)).thenReturn(Optional.of(List.of(
                new UserMessage(new SessionId(SESSION_ID), new SessionSequence(1), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.USER, "alice", "raw history"))));

        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("afterSequence", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("limit", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("afterSequence", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("limit", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("afterSequence", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("limit", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void pages_raw_history_strictly_after_the_requested_sequence_in_ascending_order() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(queries.messages(eq(SESSION_ID), eq(1L), eq(1))).thenReturn(Optional.of(List.of(
                new AssistantMessage(new SessionId(SESSION_ID), new SessionSequence(2), Optional.of(new MessageJobId(JOB_ID)), CREATED_AT,
                        MessageRole.ASSISTANT, "second"))));

        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam("afterSequence", "1").queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequence").value(2))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void accepts_valid_inbound_messages() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(intake.receive(any(IncomingMessage.class))).thenReturn(new MessageReceipt(new SessionId(SESSION_ID), new MessageJobId(JOB_ID)));

        mvc(intake, queries).perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"alice\",\"sourceMessageId\":\"source\",\"message\":\"hello\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.messageJobId").value(JOB_ID));
    }

    @Test
    void resolves_strict_root_and_reply_permalink_requests_to_the_bound_session() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        ExternalSessionReferenceQueryPort referenceQueries = mock(ExternalSessionReferenceQueryPort.class);
        when(referenceQueries.findSessionId(any(String.class)))
                .thenReturn(Optional.of(new SessionId(SESSION_ID)));
        MockMvc mvc = mvc(intake, queries, referenceQueries);

        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.sessionDetailPath").value("/internal/sessions/" + SESSION_ID));
        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345687123456?thread_ts=1712345678.123456&cid=C01ABCDEF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID));
        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\",\"extra\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\",\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void returns_content_light_session_detail_and_not_found_for_unknown_slack_binding() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        ExternalSessionReferenceQueryPort referenceQueries = mock(ExternalSessionReferenceQueryPort.class);
        when(queries.session(SESSION_ID)).thenReturn(Optional.of(new SessionDetailView(SESSION_ID, CREATED_AT,
                Optional.of(new MessageJobView(JOB_ID, SESSION_ID, JobStatus.DONE, 1, 2)),
                Optional.of(new SessionDetailView.CompactionBoundaryView(2, 4, "THRESHOLD", CREATED_AT)),
                new SessionDetailView.ContextUsageView("gemini-3.1-flash-lite", 1_048_576, 512, 0.00048828125,
                        ContextEstimate.Basis.PROVIDER_PLUS_TRAILING_ESTIMATE))));
        when(referenceQueries.findBinding(new SessionId(SESSION_ID))).thenReturn(Optional.of(
                new ExternalSessionReferenceQueryPort.ExternalSessionBindingView("SLACK", "T01", "C01ABCDEF", "1712345678.123456", CREATED_AT)));
        when(referenceQueries.findSessionId(any(String.class))).thenReturn(Optional.empty());
        MockMvc mvc = mvc(intake, queries, referenceQueries);

        mvc.perform(get("/internal/sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.slack.channelId").value("C01ABCDEF"))
                .andExpect(jsonPath("$.currentJob.status").value("DONE"))
                .andExpect(jsonPath("$.currentJob.retryCount").doesNotExist())
                .andExpect(jsonPath("$.latestCompaction.coveredThrough").value(4))
                .andExpect(jsonPath("$.context.modelId").value("gemini-3.1-flash-lite"))
                .andExpect(jsonPath("$.context.basis").value("PROVIDER_PLUS_TRAILING_ESTIMATE"))
                .andExpect(jsonPath("$..message").doesNotExist())
                .andExpect(jsonPath("$..summary").doesNotExist())
                .andExpect(jsonPath("$..continuation").doesNotExist())
                .andExpect(jsonPath("$..promptTokens").doesNotExist());
        mvc.perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permalink\":\"https://acme.slack.com/archives/C01MISSING/p1712345678123456\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sessionKey\":\"thread\",\"participantId\":\"alice\",\"sourceMessageId\":\"source\",\"message\":\"hello\",\"unexpected\":true}",
            "{\"sessionKey\":\"thread\",\"participantId\":123,\"sourceMessageId\":\"source\",\"message\":\"hello\"}",
            "{\"sessionKey\":\"thread\",\"participantId\":\"alice\",\"sourceMessageId\":\"source\",\"message\":\"hello\"} trailing"
    })
    void rejects_non_strict_inbound_json(String content) throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);

        mvc(intake, queries).perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(MessageIntakePort intake, ConversationQueryPort queries) {
        return mvc(intake, queries, mock(ExternalSessionReferenceQueryPort.class));
    }

    private static MockMvc mvc(
            MessageIntakePort intake,
            ConversationQueryPort queries,
            ExternalSessionReferenceQueryPort referenceQueries) {
        JsonMapper.Builder builder = JsonMapper.builder();
        new RuntimeConfiguration().strictJsonMapper().customize(builder);
        return MockMvcBuilders.standaloneSetup(new MessageController(intake, queries, referenceQueries))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder))
                .setControllerAdvice(new WebErrorHandler()).build();
    }
}
