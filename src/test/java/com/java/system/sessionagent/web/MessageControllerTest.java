package com.java.system.sessionagent.web;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.in.ConversationResultView;
import com.java.system.sessionagent.bootstrap.RuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageControllerTest {

    @Test
    void accepts_database_identity_limits_and_rejects_values_that_exceed_them() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        MessageReceipt receipt = new MessageReceipt(new SessionId("a1d4cefe-d5b5-4f40-b9f6-beb41a6831af"),
                new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62"));
        when(intake.receive(any(IncomingMessage.class))).thenReturn(receipt);
        MockMvc mvc = mvc(intake, queries);
        String accepted = "x".repeat(256);
        String rejected = "x".repeat(257);

        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content("""
                {"sessionKey":"%s","participantId":"%s","sourceMessageId":"%s","message":"hello"}
                """.formatted(accepted, accepted, accepted)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content("""
                {"sessionKey":"%s","participantId":"alice","sourceMessageId":"source","message":"hello"}
                """.formatted(rejected)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content("""
                {"sessionKey":"session","participantId":"%s","sourceMessageId":"source","message":"hello"}
                """.formatted(rejected)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content("""
                {"sessionKey":"session","participantId":"alice","sourceMessageId":"%s","message":"hello"}
                """.formatted(rejected)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsTheOriginalReceiptForAnExactDuplicateWithoutCallingTheModel() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        MessageReceipt receipt = new MessageReceipt(new SessionId("a1d4cefe-d5b5-4f40-b9f6-beb41a6831af"),
                new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62"));
        when(intake.receive(any(IncomingMessage.class))).thenReturn(receipt);
        MockMvc mvc = mvc(intake, queries);

        String request = """
                {"sessionKey":"thread-1","participantId":"alice","sourceMessageId":"source-1","message":"hello"}
                """;
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value(receipt.sessionId().value()))
                .andExpect(jsonPath("$.messageJobId").value(receipt.messageJobId().value()));
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageJobId").value(receipt.messageJobId().value()));

        verify(intake, times(2)).receive(any(IncomingMessage.class));
        assertThat(MessageController.class.getDeclaredConstructors()).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes()).containsExactly(MessageIntakePort.class, ConversationQueryPort.class));
        verifyNoInteractions(queries);
    }

    @Test
    void rejectsUnknownFieldsAndConflictingDuplicatesWithClosedResponses() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        when(intake.receive(any(IncomingMessage.class))).thenThrow(new com.java.system.sessionagent.conversation.port.in.MessageConflictException());
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"a\",\"sourceMessageId\":\"s\",\"message\":\"x\",\"extra\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"a\",\"sourceMessageId\":\"s\",\"message\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("message_conflict"));
    }

    @Test
    void rejectsScalarCoercionAndTrailingJsonTokensForMessageIntake() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":42,\"participantId\":\"a\",\"sourceMessageId\":\"s\",\"message\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(post("/internal/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"thread\",\"participantId\":\"a\",\"sourceMessageId\":\"s\",\"message\":\"x\"} {}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));

        verifyNoInteractions(intake, queries);
    }

    @Test
    void returnsSafeJobAndMessageProjections() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        String jobId = "f47bdb7e-75c3-4dbf-bdd4-1f8681705b62";
        String sessionId = "a1d4cefe-d5b5-4f40-b9f6-beb41a6831af";
        when(queries.findJob(jobId)).thenReturn(Optional.of(new MessageJobView(jobId, sessionId,
                com.java.system.sessionagent.conversation.domain.JobStatus.DONE, 1, 2, OptionalLong.of(4))));
        when(queries.messages(sessionId)).thenReturn(Optional.of(List.of(
                new com.java.system.sessionagent.conversation.domain.ToolMessage(
                        new SessionId(sessionId), new com.java.system.sessionagent.conversation.domain.SessionSequence(2), Optional.of(new MessageJobId(jobId)),
                        java.time.Instant.parse("2026-08-16T00:00:00Z"), com.java.system.sessionagent.conversation.domain.MessageRole.TOOL,
                        new com.java.system.sessionagent.conversation.domain.ResultId("4455b5ba-7b93-44cf-bd76-0d756e325eb5"), "call", "dGVzdA==", "source", "v1",
                        "{\"secret\":\"raw-argument\"}", Optional.of("repo-a"), Optional.of("rev-a"), "{\"secret\":\"raw-result\"}", true),
                new com.java.system.sessionagent.conversation.domain.FeedbackMessage(
                        new SessionId(sessionId), new com.java.system.sessionagent.conversation.domain.SessionSequence(3), Optional.of(new MessageJobId(jobId)),
                        java.time.Instant.parse("2026-08-16T00:00:01Z"), com.java.system.sessionagent.conversation.domain.MessageRole.FEEDBACK,
                        "REVISION_OUTDATED", "{\"repositoryId\":\"payment-service\",\"requestedRevision\":\"R1\",\"currentRevision\":\"R2\"}", false,
                        Optional.of("call"), Optional.of("codebase_search"), Optional.of("{\"repositoryId\":\"payment-service\",\"revision\":\"R1\"}"),
                        Optional.of("sensitive-model-context")))));
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(get("/internal/message-jobs/{messageJobId}", jobId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/internal/sessions/{sessionId}/messages", sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].resultId").value("4455b5ba-7b93-44cf-bd76-0d756e325eb5"))
                .andExpect(jsonPath("$[0].resultJson").doesNotExist())
                .andExpect(jsonPath("$[0].arguments").doesNotExist())
                .andExpect(jsonPath("$[0].modelContext").doesNotExist())
                .andExpect(jsonPath("$[1].toolName").value("codebase_search"))
                .andExpect(jsonPath("$[1].feedbackCode").value("REVISION_OUTDATED"))
                .andExpect(jsonPath("$[1].terminal").value(false))
                .andExpect(jsonPath("$[1].rejectedArguments").value("{\"repositoryId\":\"payment-service\",\"revision\":\"R1\"}"))
                .andExpect(jsonPath("$[1].message").value("{\"repositoryId\":\"payment-service\",\"requestedRevision\":\"R1\",\"currentRevision\":\"R2\"}"))
                .andExpect(jsonPath("$[1].modelContext").doesNotExist())
                .andExpect(jsonPath("$[1].resultJson").doesNotExist());
    }

    @Test
    void returnsFullResultsAndClosedNotFoundOrMalformedReadErrors() throws Exception {
        MessageIntakePort intake = mock(MessageIntakePort.class);
        ConversationQueryPort queries = mock(ConversationQueryPort.class);
        String resultId = "4455b5ba-7b93-44cf-bd76-0d756e325eb5";
        String sessionId = "a1d4cefe-d5b5-4f40-b9f6-beb41a6831af";
        when(queries.findResult(resultId)).thenReturn(Optional.of(new ConversationResultView(resultId, sessionId, "source", "v1",
                "{\"safe\":true}", Optional.of("repo-a"), Optional.of("rev-a"), "{\"payload\":true}", true)));
        when(queries.findJob("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62")).thenReturn(Optional.empty());
        when(queries.messages(sessionId)).thenReturn(Optional.empty());
        when(queries.findResult("e148e1e1-d085-47fd-b81c-d4d807d7fc67")).thenReturn(Optional.empty());
        MockMvc mvc = mvc(intake, queries);

        mvc.perform(get("/internal/results/{resultId}", resultId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultJson").value("{\"payload\":true}"));
        mvc.perform(get("/internal/message-jobs/{messageJobId}", "f47bdb7e-75c3-4dbf-bdd4-1f8681705b62"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/sessions/{sessionId}/messages", sessionId)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/results/{resultId}", "e148e1e1-d085-47fd-b81c-d4d807d7fc67")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/message-jobs/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/sessions/not-a-uuid/messages")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/results/not-a-uuid")).andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(MessageIntakePort intake, ConversationQueryPort queries) {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new RuntimeConfiguration().strictJsonObjectMapper().customize(builder);
        return MockMvcBuilders.standaloneSetup(new MessageController(intake, queries))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .setControllerAdvice(new WebErrorHandler()).build();
    }
}
