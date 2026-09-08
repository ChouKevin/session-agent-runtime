package com.java.system.sessionagent.web.adapter.http;

import com.java.system.sessionagent.bootstrap.configuration.RuntimeConfiguration;
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
import com.java.system.sessionagent.conversation.port.in.QueueFullException;
import com.java.system.sessionagent.conversation.port.in.SessionModelPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryKind;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    }

    @Test
    void has_no_result_lookup_endpoint() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(get("/internal/results/{resultId}", "4455b5ba-7b93-44cf-bd76-0d756e325eb5"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidPagination")
    void rejects_invalid_history_pagination(String scenario, String parameter, String[] values) throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);

        mvc(intake, queries).perform(get("/internal/sessions/{sessionId}/messages", SESSION_ID)
                        .queryParam(parameter, values))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    private static Stream<Arguments> invalidPagination() {
        return Stream.of(
                Arguments.of("negative sequence", "afterSequence", new String[]{"-1"}),
                Arguments.of("zero limit", "limit", new String[]{"0"}),
                Arguments.of("non-numeric limit", "limit", new String[]{"not-a-number"}),
                Arguments.of("empty sequence", "afterSequence", new String[]{""}),
                Arguments.of("empty limit", "limit", new String[]{""}),
                Arguments.of("repeated sequence", "afterSequence", new String[]{"1", "2"}),
                Arguments.of("repeated limit", "limit", new String[]{"1", "2"}));
    }

    @Test
    void polls_the_job_status_and_counts_without_a_reply_sequence() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(queries.findJob(JOB_ID)).thenReturn(Optional.of(new MessageJobView(JOB_ID, SESSION_ID, JobStatus.DONE, 1, 2)));

        mvc(intake, queries).perform(get("/internal/message-jobs/{messageJobId}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageJobId").value(JOB_ID))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.retryCount").value(1))
                .andExpect(jsonPath("$.modelCallCount").value(2))
                .andExpect(jsonPath("$.replySequence").doesNotExist());
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
        when(intake.receive(new IncomingMessage("thread", "alice", "source", "hello"))).thenReturn(new MessageReceipt(new SessionId(SESSION_ID), new MessageJobId(JOB_ID)));

        mvc(intake, queries).perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"alice\",\"sourceMessageId\":\"source\",\"message\":\"hello\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.messageJobId").value(JOB_ID));
    }

    @Test
    void returns_the_safe_delivery_status_for_one_slack_logical_source() throws Exception {
        SlackDeliveryStore deliveries = mock(SlackDeliveryStore.class);
        Instant nextAttemptAt = Instant.parse("2026-09-09T01:02:03Z");
        when(deliveries.findBySource("T1", "C1", "1.000001")).thenReturn(List.of(
                new SlackDeliveryView(UUID.fromString("5f0d9fb7-cd73-4a09-8f37-08bd3cbd2738"), SlackDeliveryKind.QUEUED,
                        SlackDeliveryStatus.PENDING, 0, Optional.empty(), Optional.empty(), nextAttemptAt,
                        Optional.of(UUID.fromString(JOB_ID))),
                new SlackDeliveryView(UUID.fromString("d3f80738-bd51-4614-ac4e-58bc0e559ee1"), SlackDeliveryKind.BUSY,
                        SlackDeliveryStatus.FAILED, 1, Optional.of(SlackDeliveryFailureCategory.PERMANENT), Optional.empty(),
                        nextAttemptAt, Optional.empty())));
        MockMvc mvc = mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class),
                mock(ExternalSessionReferenceQueryPort.class), mock(SessionModelPort.class), deliveries);

        mvc.perform(get("/internal/slack-deliveries").queryParam("teamId", "T1").queryParam("channelId", "C1")
                        .queryParam("messageTs", "1.000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deliveryId").value("5f0d9fb7-cd73-4a09-8f37-08bd3cbd2738"))
                .andExpect(jsonPath("$[0].kind").value("QUEUED"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].attemptCount").value(0))
                .andExpect(jsonPath("$[0].failureCategory").isEmpty())
                .andExpect(jsonPath("$[0].nextAttemptAt").value("2026-09-09T01:02:03Z"))
                .andExpect(jsonPath("$[0].jobId").value(JOB_ID))
                .andExpect(jsonPath("$[1].failureCategory").value("PERMANENT"))
                .andExpect(jsonPath("$[1].jobId").isEmpty())
                .andExpect(jsonPath("$[0].totalParts").isEmpty())
                .andExpect(jsonPath("$[0].sentParts").value(0))
                .andExpect(jsonPath("$..terminalText").doesNotExist())
                .andExpect(jsonPath("$..providerFailure").doesNotExist());
    }

    @Test
    void rejects_missing_or_repeated_slack_delivery_source_parameters() throws Exception {
        MockMvc mvc = mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class));

        mvc.perform(get("/internal/slack-deliveries").queryParam("teamId", "T1").queryParam("channelId", "C1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(get("/internal/slack-deliveries").queryParam("teamId", "T1", "T2").queryParam("channelId", "C1")
                        .queryParam("messageTs", "1.000001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void maps_capacity_rejection_to_queue_full() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        when(intake.receive(new IncomingMessage("thread", "alice", "source", "hello"))).thenThrow(new QueueFullException());

        mvc(intake, mock(ConversationQueryPort.class)).perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"alice\",\"sourceMessageId\":\"source\",\"message\":\"hello\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("queue_full"));
    }

    @Test
    void selects_a_configured_model_for_future_messages() throws Exception {
        SessionModelPort sessionModels = mock(SessionModelPort.class);
        when(sessionModels.select(new SessionId(SESSION_ID), "openai-primary"))
                .thenReturn(Optional.of(new SessionModelPort.SessionModelView(new SessionId(SESSION_ID), "openai-primary")));
        MockMvc mvc = mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class),
                mock(ExternalSessionReferenceQueryPort.class), sessionModels);

        mvc.perform(put("/internal/sessions/{sessionId}/model", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai-primary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.model").value("openai-primary"))
                .andExpect(jsonPath("$.effectiveFor").value("NEW_MESSAGES"));
    }

    @Test
    void rejects_an_unknown_or_invalid_session_model_without_selecting_it() throws Exception {
        SessionModelPort sessionModels = mock(SessionModelPort.class);
        when(sessionModels.select(new SessionId(SESSION_ID), "missing-model"))
                .thenThrow(new IllegalArgumentException("Configured model is not available"));
        MockMvc mvc = mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class),
                mock(ExternalSessionReferenceQueryPort.class), sessionModels);

        mvc.perform(put("/internal/sessions/{sessionId}/model", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"missing-model\"}"))
                .andExpect(status().isBadRequest());

        clearInvocations(sessionModels);

        mvc.perform(put("/internal/sessions/{sessionId}/model", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai-primary\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(sessionModels);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://acme.slack.com/archives/C01ABCDEF/p1712345678123456",
            "https://acme.slack.com/archives/C01ABCDEF/p1712345687123456?thread_ts=1712345678.123456&cid=C01ABCDEF"
    })
    void resolves_root_and_reply_permalink_requests_to_the_bound_session(String permalink) throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        ExternalSessionReferenceQueryPort referenceQueries = mock(ExternalSessionReferenceQueryPort.class);
        when(referenceQueries.findSessionId(permalink)).thenReturn(Optional.of(new SessionId(SESSION_ID)));

        mvc(intake, queries, referenceQueries)
                .perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of("permalink", permalink))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.sessionDetailPath").value("/internal/sessions/" + SESSION_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\",\"extra\":true}",
            "{\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\",\"permalink\":\"https://acme.slack.com/archives/C01ABCDEF/p1712345678123456\"}",
            "{\"permalink\":\"   \"}"
    })
    void rejects_non_strict_session_lookup_requests(String content) throws Exception {
        mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class))
                .perform(post("/internal/session-lookups").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void returns_content_light_session_detail() throws Exception {
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
    }

    @Test
    void returns_not_found_for_an_unknown_slack_binding() throws Exception {
        ExternalSessionReferenceQueryPort referenceQueries = mock(ExternalSessionReferenceQueryPort.class);
        String permalink = "https://acme.slack.com/archives/C01MISSING/p1712345678123456";
        when(referenceQueries.findSessionId(permalink)).thenReturn(Optional.empty());
        MockMvc mvc = mvc(mock(MessageIntakePort.class), mock(ConversationQueryPort.class), referenceQueries);

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
        return mvc(intake, queries, referenceQueries, mock(SessionModelPort.class));
    }

    private static MockMvc mvc(
            MessageIntakePort intake,
            ConversationQueryPort queries,
            ExternalSessionReferenceQueryPort referenceQueries,
            SessionModelPort sessionModels) {
        return mvc(intake, queries, referenceQueries, sessionModels, mock(SlackDeliveryStore.class));
    }

    private static MockMvc mvc(
            MessageIntakePort intake,
            ConversationQueryPort queries,
            ExternalSessionReferenceQueryPort referenceQueries,
            SessionModelPort sessionModels,
            SlackDeliveryStore slackDeliveryStore) {
        JsonMapper.Builder builder = JsonMapper.builder();
        new RuntimeConfiguration().strictJsonMapper().customize(builder);
        return MockMvcBuilders.standaloneSetup(new MessageController(intake, queries, referenceQueries, sessionModels, slackDeliveryStore))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(builder))
                .setControllerAdvice(new WebErrorHandler()).build();
    }
}
