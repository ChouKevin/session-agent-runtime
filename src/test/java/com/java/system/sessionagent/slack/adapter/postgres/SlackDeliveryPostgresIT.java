package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackDeliveryWorker;
import com.java.system.sessionagent.slack.domain.IncomingEvent;
import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackEventOutcome;
import com.java.system.sessionagent.slack.domain.SlackIntakeClassification;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.PostingSlackWebApi;
import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import com.java.system.sessionagent.slack.domain.SlackUploadState;
import com.java.system.sessionagent.slack.domain.SlackPresentation;
import com.java.system.sessionagent.artifact.application.ArtifactEncoder;
import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresArtifactStore;
import com.java.system.sessionagent.tool.TestToolInvocation;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.artifact.application.ArtifactToolCatalog;
import com.java.system.sessionagent.tool.domain.ToolName;
import java.util.LinkedHashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import com.java.system.sessionagent.slack.adapter.sdk.SlackSdkWebApi;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.util.http.SlackHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.JobCancellationService;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.MessageJobCancellationPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackDeliveryPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private PostgresIntakeCapacity intakeCapacity;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
        intakeCapacity = new PostgresIntakeCapacity(dataSource());
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n"})
    void delivers_committed_markdown_and_csv_with_blank_titles_without_changing_metadata_or_bytes(String title) {
        PostgresConversationStore conversations = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), intakeCapacity);
        MessageIntakePort messages = new MessageService(conversations, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission intake = new PostgresMessageSubmission(dataSource(), messages,
                new JobCancellationService(conversations), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity);
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:1.000001", "U1", "1.000001", "Export reports");
        assertThat(intake.receive(new MessageSubmission("blank-title", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.ACCEPTED, Optional.of(message))).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        MessageReceipt receipt = conversations.receive(message, TestConfiguredModels.withDefault("test").defaultSelection(),
                TestConfiguredModels.withDefault("test"));
        MessageWorkClaim claim = conversations.claimNext("blank-title-tools", Duration.ofMinutes(1)).orElseThrow();
        assertThat(conversations.reserveModelCall(claim, 2, NOW)).hasValue(1);
        PostgresArtifactStore artifacts = new PostgresArtifactStore(dataSource(), new ObjectMapper());
        SlackProperties configured = new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
        ArtifactToolCatalog catalog = new ArtifactToolCatalog(emptyToolCatalog(), artifacts, new SlackAttachmentRoute(dataSource(), configured));
        String markdown = "# Exact report\n\n```text\n中😀\n```\n";
        Map<String, Object> markdownArguments = Map.of("title", title, "content", markdown);
        Map<String, Object> csvArguments = Map.of("title", title, "columns", List.of("name", "value"),
                "rows", List.of(List.of("中😀", "=1")));
        ToolOutput markdownOutput;
        ToolOutput csvOutput;
        try (ToolSnapshot tools = catalog.snapshot(receipt.sessionId().value(), receipt.messageJobId().value())) {
            markdownOutput = tools.invoke(TestToolInvocation.of(claim, 1, 0), new ToolName("createMarkdownReport"), markdownArguments);
            csvOutput = tools.invoke(TestToolInvocation.of(claim, 1, 1), new ToolName("createCsv"), csvArguments);
        }
        assertThat(markdownOutput.isError()).isFalse();
        assertThat(csvOutput.isError()).isFalse();
        ToolCallId markdownCall = new ToolCallId("blank-title-markdown");
        ToolCallId csvCall = new ToolCallId("blank-title-csv");
        conversations.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(markdownCall, "createMarkdownReport", markdownArguments),
                        new ConversationStore.ToolCallData(csvCall, "createCsv", csvArguments))),
                new ConversationStore.ToolObservationData(markdownCall, "createMarkdownReport", markdownOutput.asStructuredValue(),
                        markdownOutput.artifactReceipt()),
                new ConversationStore.ToolObservationData(csvCall, "createCsv", csvOutput.asStructuredValue(), csvOutput.artifactReceipt())),
                ConversationStore.JobUpdate.KEEP_WORKING), NOW);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        assertThat(artifacts.released(sessionId, jobId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from generated_artifact where message_job_id=? and observation_sequence is not null
                """, Integer.class, jobId)).isEqualTo(2);
        conversations.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("Both reports are ready.")),
                ConversationStore.JobUpdate.COMPLETE), NOW);
        List<ArtifactStore.Artifact> released = artifacts.released(sessionId, jobId);
        assertThat(released).hasSize(2).allSatisfy(file -> assertThat(file.title()).isEqualTo(title));
        assertThat(released.get(0).content()).isEqualTo(markdown.getBytes(StandardCharsets.UTF_8));
        assertThat(released.get(1).content()).isEqualTo("\uFEFF\"name\",\"value\"\r\n\"中😀\",\"'=1\"\r\n".getBytes(StandardCharsets.UTF_8));
        markQueuedNoticeSent();
        List<SlackPostRequest> posts = new ArrayList<>();
        Map<String, ArtifactStore.Artifact> allocated = new LinkedHashMap<>();
        Map<String, byte[]> uploaded = new LinkedHashMap<>();
        Map<String, String> finalizedTitles = new LinkedHashMap<>();
        PostingSlackWebApi api = new PostingSlackWebApi() {
            @Override public String post(SlackPostRequest request) {
                posts.add(request);
                return "2.000001";
            }
            @Override public SlackUploadTicket allocateUpload(String filename, int byteLength) {
                ArtifactStore.Artifact file = released.stream().filter(candidate -> candidate.filename().equals(filename)).findFirst().orElseThrow();
                assertThat(byteLength).isEqualTo(file.content().length);
                String fileId = "F" + allocated.size();
                allocated.put(fileId, file);
                return new SlackUploadTicket(fileId, "https://files.slack.com/upload/v1/" + fileId);
            }
            @Override public void uploadBytes(SlackUploadTicket ticket, byte[] content) {
                ArtifactStore.Artifact file = allocated.get(ticket.fileId());
                assertThat(content).isEqualTo(file.content());
                uploaded.put(file.filename(), content);
            }
            @Override public void finalizeUpload(SlackUploadTicket ticket, String fileTitle, String channelId, String rootThreadTs) {
                assertThat(channelId).isEqualTo("C1");
                assertThat(rootThreadTs).isEqualTo("1.000001");
                assertThat(fileTitle).isEqualTo(title);
                finalizedTitles.put(allocated.get(ticket.fileId()).filename(), fileTitle);
            }
        };
        SlackPostgresDeliveryStore deliveries = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveries, api, new SlackDeliveryProperties(), "blank-title-delivery");
        assertThat(worker.poll()).isTrue();
        assertThat(allocated).as("Accepted title metadata must not prevent preparing and allocating the first required file").hasSize(1);
        for (int stage = 1; stage < 6; stage++) { assertThat(worker.poll()).isTrue(); }
        assertThat(worker.poll()).isFalse();
        assertThat(posts).containsExactly(new SlackPostRequest("C1", "1.000001", "Both reports are ready.", SlackPresentation.MARKDOWN));
        assertThat(uploaded).hasSize(2);
        assertThat(finalizedTitles).hasSize(2).allSatisfy((filename, fileTitle) -> assertThat(fileTitle).isEqualTo(title));
        assertThat(artifacts.released(sessionId, jobId)).allSatisfy(file -> {
            assertThat(file.title()).isEqualTo(title);
            assertThat(uploaded.get(file.filename())).isEqualTo(file.content());
        });
        assertThat(jdbcTemplate.queryForList("select title from slack_delivery_upload where delivery_id=? order by part_index",
                String.class, answerDeliveryId())).containsExactly(title, title);
        assertThat(jdbcTemplate.queryForList("select state from slack_delivery_upload where delivery_id=? order by part_index",
                String.class, answerDeliveryId())).containsExactly("SENT", "SENT");
        assertThat(deliveries.read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(view.totalParts()).contains(3);
            assertThat(view.sentParts()).isEqualTo(3);
        });
    }

    @Test
    void prepares_exact_long_answer_and_released_artifacts_atomically_before_any_parent_completion() {
        String text = "# Exact 中😀\n" + "x".repeat(10_001);
        MessageReceipt receipt = completeSlackAssistantJob(text, true);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore store = new SlackPostgresDeliveryStore(dataSource());
        store.discover();
        SlackDeliveryClaim claim = store.claimNext("prepare", Duration.ofSeconds(30), 5).orElseThrow();
        jdbcTemplate.execute("""
                create function reject_tool_upload() returns trigger language plpgsql as $$
                begin if new.part_index=2 then raise exception 'injected preparation rollback'; end if; return new; end $$;
                create trigger reject_tool_upload before insert on slack_delivery_upload
                for each row execute function reject_tool_upload()
                """);
        assertThatThrownBy(() -> store.nextPart(claim, Duration.ofSeconds(30))).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery_part where delivery_id=?", Integer.class,
                claim.deliveryId())).isZero();
        assertThat(jdbcTemplate.queryForObject("select items_prepared from slack_delivery where delivery_id=?", Boolean.class,
                claim.deliveryId())).isFalse();
        assertThat(store.markPartSent(claim, new SlackDeliveryPart(0, "invented"), "2.0")).isFalse();
        jdbcTemplate.execute("drop trigger reject_tool_upload on slack_delivery_upload; drop function reject_tool_upload()");
        SlackDeliveryPart notification = store.nextPart(claim, Duration.ofSeconds(30)).orElseThrow();
        assertThat(notification.presentation()).isEqualTo(SlackPresentation.PLAIN);
        assertThat(jdbcTemplate.queryForList("select filename from slack_delivery_upload where delivery_id=? order by part_index",
                String.class, claim.deliveryId())).hasSize(2).first().isEqualTo("answer.md");
        assertThat(jdbcTemplate.queryForObject("select content from slack_delivery_upload where delivery_id=? and part_index=1",
                byte[].class, claim.deliveryId())).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        assertThat(jdbcTemplate.queryForObject("""
                select count(distinct identity) from slack_delivery_upload where delivery_id=?
                """, Integer.class, claim.deliveryId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select identity from slack_delivery_upload where delivery_id=? and part_index=1",
                UUID.class, claim.deliveryId())).isEqualTo(UUID.nameUUIDFromBytes(
                        ("slack-answer:" + receipt.messageJobId().value()).getBytes(StandardCharsets.UTF_8)));
        assertThat(store.markPartSent(claim, notification, "2.000001")).isTrue();
        assertThat(store.read(claim.deliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.WORKING);
            assertThat(view.totalParts()).contains(3);
            assertThat(view.sentParts()).isOne();
        });
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id=?", String.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo("DONE");
    }

    @Test
    void parent_waits_for_both_answer_file_and_tool_file_across_fresh_worker_instances() {
        String answer = "x".repeat(10_001);
        completeSlackAssistantJob(answer, true);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        for (int stage = 0; stage < 5; stage++) {
            assertThat(uploadWorker(api).poll()).isTrue();
            assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view ->
                    assertThat(view.status()).isNotEqualTo(SlackDeliveryStatus.SENT));
        }
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(api.allocations).isEqualTo(2);
        assertThat(api.uploads).isEqualTo(2);
        assertThat(api.finalizations).isEqualTo(2);
        assertThat(api.uploadedContents).hasSize(2);
        assertThat(api.uploadedContents.get(0)).isEqualTo(answer.getBytes(StandardCharsets.UTF_8));
        assertThat(api.uploadedContents.get(1)).isEqualTo("# Tool report\nExact 中😀".getBytes(StandardCharsets.UTF_8));
        assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(view.sentParts()).isEqualTo(3);
            assertThat(view.slackMessageTs()).contains("2.000001");
        });
    }

    @Test
    void overlapping_acknowledgement_cannot_complete_parent_while_items_are_still_being_prepared() throws Exception {
        completeSlackAssistantJob("x".repeat(10_001), true);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore store = new SlackPostgresDeliveryStore(dataSource());
        store.discover();
        SlackDeliveryClaim claim = store.claimNext("preparer", Duration.ofSeconds(30), 5).orElseThrow();
        jdbcTemplate.execute("""
                create function pause_upload_preparation() returns trigger language plpgsql as $$
                begin perform pg_advisory_xact_lock(424242); return new; end $$;
                create trigger pause_upload_preparation before insert on slack_delivery_upload
                for each row execute function pause_upload_preparation()
                """);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection gate = dataSource().getConnection()) {
            try (PreparedStatement lock = gate.prepareStatement("select pg_advisory_lock(424242)")) { lock.execute(); }
            Future<Optional<SlackDeliveryPart>> preparing = executor.submit(() -> store.nextPart(claim, Duration.ofSeconds(30)));
            awaitDatabaseLockWaiter("insert into slack_delivery_upload");
            Future<Boolean> acknowledgement = executor.submit(() -> store.markPartSent(claim, new SlackDeliveryPart(0, "notification"), "2.0"));
            awaitDatabaseLockWaiter("select delivery_id from slack_delivery");
            assertThat(acknowledgement.isDone()).isFalse();
            try (PreparedStatement unlock = gate.prepareStatement("select pg_advisory_unlock(424242)")) { unlock.execute(); }
            assertThat(preparing.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(acknowledgement.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.read(claim.deliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.WORKING);
            assertThat(view.totalParts()).contains(3);
            assertThat(view.sentParts()).isOne();
        });
    }

    @Test
    void crash_after_byte_acceptance_reuses_the_persisted_ticket_without_fresh_allocation() {
        completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        assertThat(uploadWorker(api).poll()).isTrue();
        api.expireDuringUpload = true;
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadState()).isEqualTo("ALLOCATED");
        api.expireDuringUpload = false;
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadState()).isEqualTo("UPLOADED");
        assertThat(api.allocations).isOne();
        assertThat(api.uploads).isEqualTo(2);
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(api.finalizations).isOne();
    }

    @Test
    void restarts_after_allocation_and_byte_upload_without_reallocation_and_fences_stale_acknowledgements() {
        completeSlackAssistantJob("😀".repeat(5_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        assertThat(uploadWorker(api).poll()).isTrue(); // notification + allocation; stage ownership released
        assertThat(api.allocations).isOne();
        assertThat(uploadState()).isEqualTo("ALLOCATED");
        SlackPostgresDeliveryStore oldStore = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryClaim oldClaim = oldStore.claimNext("old-byte-worker", Duration.ofSeconds(30), 5).orElseThrow();
        SlackDeliveryPart oldPart = oldStore.nextPart(oldClaim, Duration.ofSeconds(30)).orElseThrow();
        expireUploadLease();
        SlackPostgresDeliveryStore restarted = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryClaim newClaim = restarted.claimNext("new-byte-worker", Duration.ofSeconds(30), 5).orElseThrow();
        SlackDeliveryPart newPart = restarted.nextPart(newClaim, Duration.ofSeconds(30)).orElseThrow();
        assertThat(newPart.file().orElseThrow().ticket()).contains(new SlackUploadTicket("F1", "https://files.slack.com/upload/v1/private"));
        assertThat(oldStore.uploaded(oldClaim, oldPart)).isFalse();
        api.uploadBytes(newPart.file().orElseThrow().ticket().orElseThrow(), newPart.file().orElseThrow().uploadContent().orElseThrow());
        assertThat(restarted.uploaded(newClaim, newPart)).isTrue();
        expireUploadLease(); // crash after persisted bytes, before finalization intent
        SlackPostgresDeliveryStore beforeIntent = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryClaim finalClaim = beforeIntent.claimNext("finalizer", Duration.ofSeconds(30), 5).orElseThrow();
        SlackDeliveryPart finalPart = beforeIntent.nextPart(finalClaim, Duration.ofSeconds(30)).orElseThrow();
        assertThat(finalPart.file().orElseThrow().state()).isEqualTo(SlackUploadState.UPLOADED);
        assertThat(beforeIntent.beginFinalization(finalClaim, finalPart, Duration.ofSeconds(30))).isTrue();
        assertThat(new SlackPostgresDeliveryStore(dataSource()).claimNext("competitor", Duration.ofSeconds(30), 5)).isEmpty();
        assertThat(beforeIntent.scheduleRetry(finalClaim, SlackDeliveryFailureCategory.TRANSIENT, Duration.ZERO)).isFalse();
        expireUploadLease(); // crash after intent, even if no remote call can be proved
        SlackPostgresDeliveryStore recovered = new SlackPostgresDeliveryStore(dataSource());
        assertThat(recovered.claimNext("recovery", Duration.ofSeconds(30), 5)).isEmpty();
        assertThat(uploadState()).isEqualTo("UNCONFIRMED");
        assertThat(beforeIntent.markPartSent(finalClaim, finalPart, "F1")).isFalse();
        assertThat(recovered.read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(view.failureCategory()).contains(SlackDeliveryFailureCategory.UNCONFIRMED);
        });
        assertThat(api.allocations).isOne();
        assertThat(api.uploads).isOne();
        assertThat(api.finalizations).isZero();
    }

    @Test
    void accepted_but_response_lost_finalization_is_unconfirmed_once_without_new_upload_or_model_retry() {
        MessageReceipt receipt = completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        api.failStage = "finalize";
        api.failure = Optional.of(new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty()));
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadWorker(api).poll()).isFalse();
        assertThat(api.allocations).isOne();
        assertThat(api.uploads).isOne();
        assertThat(api.finalizations).isOne();
        assertThat(uploadState()).isEqualTo("UNCONFIRMED");
        assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(view.failureCategory()).contains(SlackDeliveryFailureCategory.UNCONFIRMED);
            assertThat(view.sentParts()).isOne();
        });
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id=?", String.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id=?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery_upload", Integer.class)).isOne();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503, 429})
    void ambiguous_sdk_finalization_with_retry_after_is_not_replayed_or_reallocated(int status) throws Exception {
        MessageReceipt receipt = completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi stages = new UploadApi();
        assertThat(uploadWorker(stages).poll()).isTrue();
        assertThat(uploadWorker(stages).poll()).isTrue();
        assertThat(uploadState()).isEqualTo("UPLOADED");
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "7");
            byte[] response = "{\"ok\":false,\"error\":\"internal_error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try (MockedStatic<Slack> factory = Mockito.mockStatic(Slack.class, Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> Slack.getInstance(Mockito.any(SlackConfig.class), Mockito.any(SlackHttpClient.class)))
                    .thenAnswer(invocation -> {
                        SlackConfig config = invocation.getArgument(0);
                        config.setMethodsEndpointUrlPrefix("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                        return invocation.callRealMethod();
                    });
            SlackProperties properties = new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
            try (SlackSdkWebApi api = new SlackSdkWebApi(properties)) {
                SlackDeliveryWorker worker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), api,
                        new SlackDeliveryProperties(), "sdk-finalizer");
                assertThat(worker.poll()).isTrue();
                assertThat(worker.poll()).isFalse();
            }
        } finally { server.stop(0); }
        assertThat(paths).containsExactly("/files.completeUploadExternal");
        assertThat(stages.allocations).isOne();
        assertThat(stages.uploads).isOne();
        assertThat(uploadState()).isEqualTo("UNCONFIRMED");
        assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(view.failureCategory()).contains(SlackDeliveryFailureCategory.UNCONFIRMED);
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery_upload", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id=?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isOne();
    }

    @Test
    void crash_after_remote_acceptance_before_ack_commit_never_finalizes_twice() {
        completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadWorker(api).poll()).isTrue();
        api.expireDuringFinalize = true;
        assertThat(uploadWorker(api).poll()).isTrue();
        assertThat(uploadState()).isEqualTo("FINALIZING");
        assertThat(uploadWorker(api).poll()).isFalse();
        assertThat(uploadState()).isEqualTo("UNCONFIRMED");
        assertThat(api.finalizations).isOne();
        assertThat(api.allocations).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"allocate", "upload", "finalize"})
    void safely_rejected_rate_limits_keep_the_ticket_and_honor_retry_after(String stage) {
        completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        api.failStage = stage;
        api.failure = Optional.of(new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(7))));
        for (int index = 0; index < 3; index++) {
            assertThat(uploadWorker(api).poll()).isTrue();
            if (api.failure.isEmpty()) { break; }
        }
        assertThat(jdbcTemplate.queryForObject("""
                select next_attempt_at>clock_timestamp()+interval '5 seconds' from slack_delivery where kind='ANSWER'
                """, Boolean.class)).isTrue();
        assertThat(uploadWorker(api).poll()).isFalse();
        jdbcTemplate.update("update slack_delivery set next_attempt_at=clock_timestamp() where kind='ANSWER'");
        for (int index = 0; index < 3; index++) { uploadWorker(api).poll(); }
        assertThat(uploadState()).isEqualTo("SENT");
        assertThat(api.allocations).isEqualTo(stage.equals("allocate") ? 2 : 1);
        assertThat(api.uploads).isEqualTo(stage.equals("upload") ? 2 : 1);
        assertThat(api.finalizations).isEqualTo(stage.equals("finalize") ? 2 : 1);
        assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(view.slackMessageTs()).contains("2.000001");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"allocate", "upload", "finalize"})
    void permission_type_or_expired_capability_errors_terminate_without_regeneration(String stage) {
        completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        UploadApi api = new UploadApi();
        api.failStage = stage;
        api.failure = Optional.of(new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty()));
        for (int index = 0; index < 3; index++) { uploadWorker(api).poll(); }
        assertThat(uploadWorker(api).poll()).isFalse();
        assertThat(uploadState()).isEqualTo("FAILED");
        assertThat(api.allocations).isOne();
        assertThat(new SlackPostgresDeliveryStore(dataSource()).read(answerDeliveryId())).hasValueSatisfying(view ->
                assertThat(view.status()).isEqualTo(SlackDeliveryStatus.FAILED));
    }

    private SlackDeliveryWorker uploadWorker(UploadApi api) {
        return new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), api, new SlackDeliveryProperties(), "upload-worker");
    }

    private String uploadState() {
        return jdbcTemplate.queryForObject("select state from slack_delivery_upload where delivery_id=? and part_index=1",
                String.class, answerDeliveryId());
    }

    private void expireUploadLease() {
        jdbcTemplate.update("update slack_delivery set locked_until=clock_timestamp()-interval '1 second' where kind='ANSWER'");
    }

    private final class UploadApi implements PostingSlackWebApi {
        private int allocations;
        private int uploads;
        private int finalizations;
        private String failStage = "";
        private Optional<SlackPostFailure> failure = Optional.empty();
        private boolean expireDuringFinalize;
        private boolean expireDuringUpload;
        private final List<byte[]> uploadedContents = new ArrayList<>();
        @Override public String post(SlackPostRequest request) {
            assertNetworkLease();
            return "2.000001";
        }
        @Override public SlackUploadTicket allocateUpload(String filename, int byteLength) {
            assertNetworkLease();
            assertThat(filename).endsWith(".md");
            assertThat(byteLength).isPositive();
            allocations++;
            fail("allocate");
            return new SlackUploadTicket("F" + allocations, "https://files.slack.com/upload/v1/private");
        }
        @Override public void uploadBytes(SlackUploadTicket ticket, byte[] content) {
            assertNetworkLease();
            assertThat(ticket.fileId()).startsWith("F");
            uploadedContents.add(content);
            uploads++;
            fail("upload");
            if (expireDuringUpload) { expireUploadLease(); }
        }
        @Override public void finalizeUpload(SlackUploadTicket ticket, String title, String channelId, String rootThreadTs) {
            assertNetworkLease();
            assertThat(ticket.fileId()).startsWith("F");
            assertThat(channelId).isEqualTo("C1");
            assertThat(rootThreadTs).isEqualTo("1.000001");
            assertThat(jdbcTemplate.queryForObject("select state from slack_delivery_upload where file_id=?",
                    String.class, ticket.fileId())).isEqualTo("FINALIZING");
            finalizations++; // models remote acceptance even when acknowledgement is then lost
            fail("finalize");
            if (expireDuringFinalize) { expireUploadLease(); }
        }
        private void fail(String stage) {
            if (stage.equals(failStage) && failure.isPresent()) {
                SlackPostFailure value = failure.orElseThrow();
                failure = Optional.empty();
                throw value;
            }
        }
        private void assertNetworkLease() {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbcTemplate.queryForObject("""
                    select status='WORKING' and locked_until>clock_timestamp() from slack_delivery where kind='ANSWER'
                    """, Boolean.class)).isTrue();
        }
    }

    @Test
    void smoke_stops_full_thread_without_model_wait_and_delivers_durable_receipt_and_each_cancellation() throws Exception {
        PostgresIntakeCapacity capacity = new PostgresIntakeCapacity(dataSource(), 4);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PostgresConversationStore store = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), capacity);
        MessageIntakePort messages = new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry());
        MessageHandler handler = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                new JobCancellationService(store), clock, capacity));
        assertThat(handler.handle(stopEvent("root", "1", "", "<@UBOT> work"))).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(handler.handle(stopEvent("pending", "2", "1", "queued"))).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(handler.handle(stopEvent("retry", "3", "1", "retry"))).isEqualTo(SlackEventOutcome.ACCEPTED);
        jdbcTemplate.update("update message_job set status = 'RETRY', retry_count = 1 where user_message_sequence = 3");
        assertThat(handler.handle(stopEvent("other", "10", "", "<@UBOT> other"))).isEqualTo(SlackEventOutcome.ACCEPTED);
        MessageWorkClaim claim = store.claimNext("model-worker", Duration.ofMinutes(1)).orElseThrow();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        ConversationModel model = new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return new ModelRouteId("stop-smoke");
            }

            @Override
            public ModelDescriptor descriptor() {
                return new ModelDescriptor(routeId(), "fake", 1_000_000);
            }

            @Override
            public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> observer) {
                reservation.reserve();
                modelStarted.countDown();
                try {
                    if (!releaseModel.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Fake model was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new ModelCallResult(new ModelReply.Text("in-flight answer must not replace cancellation"), Optional.empty());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model), emptyToolCatalog(), new FileSystemSkillLibrary(new SkillProperties()), clock, 3,
                    new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());
            Future<?> processing = executor.submit(() -> processor.process(claim, () -> true));
            assertThat(modelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            IncomingEvent stop = new IncomingEvent("stop-message", "T1", "C1", "4", "1", "U99", "", "channel",
                    " <@UBOT> 停止工作 ", "", false, false);
            assertThat(handler.handle(stop)).isEqualTo(SlackEventOutcome.HANDLED);
            assertThat(processing.isDone()).isFalse();
            assertThat(store.readJob(claim.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.WORKING);
            assertThat(store.isCancellationRequested(claim)).isTrue();
            assertThat(jdbcTemplate.queryForList("select status from message_job where session_id = ? order by user_message_sequence",
                    String.class, UUID.fromString(claim.sessionId().value()))).containsExactly("WORKING", "CANCELLED", "CANCELLED");
            assertThat(handler.handle(stopEvent("later", "5", "1", "new work"))).isEqualTo(SlackEventOutcome.ACCEPTED);
            MessageHandler restarted = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                    new JobCancellationService(store), clock, capacity));
            assertThat(restarted.handle(stop)).isEqualTo(SlackEventOutcome.HANDLED);
            assertThat(restarted.handle(stopEvent("stop-app-mention", "4", "1", "<@UBOT> 停止工作")))
                    .isEqualTo(SlackEventOutcome.HANDLED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where cancellation_requested_at is not null",
                    Integer.class)).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where status = 'PENDING'", Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery where kind = 'STOP'", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select terminal_text from slack_delivery where kind = 'STOP'", String.class))
                    .contains("3", "呼叫", "逾時");
            assertThat(jdbcTemplate.queryForObject("select count(*) from source_message", Integer.class)).isEqualTo(5);
            List<SlackPostRequest> posts = new ArrayList<>();
            SlackDeliveryWorker delivery = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), (PostingSlackWebApi) request -> {
                posts.add(request);
                return "sent-" + posts.size();
            }, new SlackDeliveryProperties(), "receipt-worker");
            while (delivery.poll()) {
                // Drain durable notices and already-terminal queued-job cancellation results.
            }
            assertThat(posts).filteredOn(post -> post.text().contains("已收到停止請求")).singleElement()
                    .satisfies(post -> assertThat(post.rootThreadTs()).isEqualTo("1"));
            releaseModel.countDown();
            processing.get(5, TimeUnit.SECONDS);
            assertThat(store.readJob(claim.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
            SlackDeliveryWorker recovered = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), (PostingSlackWebApi) request -> {
                posts.add(request);
                return "sent-" + posts.size();
            }, new SlackDeliveryProperties(), "restarted-worker");
            while (recovered.poll()) {
                // The running job now has its own durable terminal cancellation delivery.
            }
            assertThat(posts).filteredOn(post -> JobCancellation.MESSAGE.equals(post.text())).hasSize(3)
                    .allSatisfy(post -> assertThat(post.rootThreadTs()).isEqualTo("1"));
            assertThat(posts).noneSatisfy(post -> assertThat(post.text()).contains("in-flight answer"));
            assertThat(jdbcTemplate.queryForObject("select sum(model_calls) from message_job", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery where status <> 'SENT'", Integer.class)).isZero();
        } finally {
            releaseModel.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stop_guidance_creates_no_session_or_job_and_is_delivered_once_for_roots_and_unbound_threads() {
        PostgresConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        MessageIntakePort messages = new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry());
        MessageHandler handler = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                new JobCancellationService(store), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity));
        assertThat(handler.handle(stopEvent("top", "1", "", "<@UBOT> stop"))).isEqualTo(SlackEventOutcome.HANDLED);
        assertThat(handler.handle(stopEvent("top-dual", "1", "", "<@UBOT> stop"))).isEqualTo(SlackEventOutcome.HANDLED);
        assertThat(handler.handle(stopEvent("unbound", "3", "2", "<@UBOT> stop"))).isEqualTo(SlackEventOutcome.HANDLED);
        assertThat(jdbcTemplate.queryForObject("select count(*) from conversation_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_thread_binding", Integer.class)).isZero();
        List<SlackPostRequest> posts = new ArrayList<>();
        SlackDeliveryWorker delivery = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), (PostingSlackWebApi) request -> {
            posts.add(request);
            return "sent-" + posts.size();
        }, new SlackDeliveryProperties(), "guidance-worker");
        while (delivery.poll()) {
            // Drain both command notices.
        }
        assertThat(posts).containsExactly(
                new SlackPostRequest("C1", "1", "請在要停止工作的對話串內 @bot stop 或 @bot 停止工作。"),
                new SlackPostRequest("C1", "2", "此對話目前沒有可停止的工作"));
    }

    @Test
    void rolls_back_stop_snapshot_and_receipts_if_the_durable_notice_cannot_be_written() {
        PostgresConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        MessageIntakePort messages = new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry());
        MessageHandler handler = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                new JobCancellationService(store), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity));
        handler.handle(stopEvent("root", "1", "", "<@UBOT> work"));
        jdbcTemplate.execute("""
                create function reject_stop_notice() returns trigger language plpgsql as $$
                begin
                    if new.kind = 'STOP' then raise exception 'stop notice rejected'; end if;
                    return new;
                end;
                $$;
                create trigger reject_stop_notice before insert on slack_delivery
                    for each row execute function reject_stop_notice();
                """);
        assertThatThrownBy(() -> handler.handle(stopEvent("stop", "2", "1", "<@UBOT> stop")))
                .hasMessageContaining("stop notice rejected");
        assertThat(jdbcTemplate.queryForObject("select status from message_job", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where cancellation_requested_at is not null",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from runtime_message", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_message_receipt where classification = 'STOP_REQUEST'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_event_receipt where classification = 'STOP_REQUEST'",
                Integer.class)).isZero();
    }

    @Test
    void serializes_stop_commit_with_new_intake_worker_append_http_cancel_and_dual_event_replay() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PostgresConversationStore store = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), intakeCapacity);
        MessageIntakePort messages = new MessageService(store, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry());
        JobCancellationService cancellations = new JobCancellationService(store);
        MessageHandler ordinary = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                cancellations, clock, intakeCapacity));
        ordinary.handle(stopEvent("root", "1", "", "<@UBOT> work"));
        ordinary.handle(stopEvent("pending", "2", "1", "queued"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        String queuedJob = jdbcTemplate.queryForObject(
                "select message_job_id::text from message_job where user_message_sequence = 2", String.class);
        CountDownLatch snapshotCancelled = new CountDownLatch(1);
        CountDownLatch commitStop = new CountDownLatch(1);
        MessageJobCancellationPort staged = new MessageJobCancellationPort() {
            @Override
            public Optional<MessageJobView> cancel(String jobId) {
                return cancellations.cancel(jobId);
            }

            @Override
            public int cancelSession(String sessionId) {
                int count = cancellations.cancelSession(sessionId);
                snapshotCancelled.countDown();
                try {
                    if (!commitStop.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Stop transaction was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return count;
            }
        };
        MessageHandler stopping = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource(), messages,
                staged, clock, intakeCapacity));
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<SlackEventOutcome> stopped = executor.submit(() -> stopping.handle(stopEvent("stop", "3", "1", "<@UBOT> stop")));
            assertThat(snapshotCancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where cancellation_requested_at is not null",
                    Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery where kind = 'STOP'", Integer.class)).isZero();
            Future<SlackEventOutcome> later = executor.submit(() -> ordinary.handle(stopEvent("later", "4", "1", "later work")));
            Future<JobStatus> appended = executor.submit(() -> store.append(claim,
                    new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("late answer")),
                            ConversationStore.JobUpdate.COMPLETE), NOW));
            Future<?> httpCancelled = executor.submit(() -> cancellations.cancel(queuedJob));
            Future<SlackEventOutcome> duplicate = executor.submit(() ->
                    ordinary.handle(stopEvent("stop-app-mention", "3", "1", "<@UBOT> stop")));
            commitStop.countDown();
            assertThat(stopped.get(5, TimeUnit.SECONDS)).isEqualTo(SlackEventOutcome.HANDLED);
            assertThat(appended.get(5, TimeUnit.SECONDS)).isEqualTo(JobStatus.CANCELLED);
            httpCancelled.get(5, TimeUnit.SECONDS);
            assertThat(later.get(5, TimeUnit.SECONDS)).isEqualTo(SlackEventOutcome.ACCEPTED);
            assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo(SlackEventOutcome.HANDLED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where status = 'CANCELLED'", Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where status = 'PENDING' and cancellation_requested_at is null",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from runtime_message where code = 'JOB_CANCELLED'", Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery where kind = 'STOP'", Integer.class)).isEqualTo(1);
            ordinary.handle(stopEvent("stop-again", "5", "1", "<@UBOT> stop"));
            ordinary.handle(stopEvent("empty-stop", "6", "1", "<@UBOT> stop"));
            assertThat(jdbcTemplate.queryForObject("select terminal_text from slack_delivery where message_ts = '6'", String.class))
                    .isEqualTo("此對話目前沒有可停止的工作");
        } finally {
            commitStop.countDown();
            executor.shutdownNow();
        }
    }

    private static IncomingEvent stopEvent(String eventId, String messageTs, String threadTs, String text) {
        return new IncomingEvent(eventId, "T1", "C1", messageTs, threadTs, "U2", "", "channel", text, "", false, false);
    }

    @Test
    void discovers_and_delivers_a_cancelled_job_exactly_once_and_accepts_the_next_slack_message() {
        PostgresIntakeCapacity capacity = new PostgresIntakeCapacity(dataSource(), 1);
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), capacity);
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        MessageHandler adapter = new MessageHandler("UBOT",
                new PostgresMessageSubmission(dataSource(), messageIntake, new JobCancellationService(conversationStore),
                        Clock.fixed(NOW, ZoneOffset.UTC), capacity));
        assertThat(adapter.handle(new IncomingEvent("cancel-event", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> hello", "", false, false))).isEqualTo(SlackEventOutcome.ACCEPTED);
        MessageJobId jobId = new MessageJobId(jdbcTemplate.queryForObject(
                "select message_job_id::text from message_job", String.class));

        assertThat(conversationStore.requestCancellation(jobId).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        conversationStore.requestCancellation(jobId);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        deliveryStore.discover();
        List<SlackPostRequest> posts = new ArrayList<>();
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, (PostingSlackWebApi) request -> {
            posts.add(request);
            return "2.000001";
        }, new SlackDeliveryProperties(), "delivery-worker");
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isFalse();
        assertThat(posts).containsExactly(new SlackPostRequest("C1", "1.000001", JobCancellation.MESSAGE));
        assertThat(deliveryStore.findBySource("T1", "C1", "1.000001"))
                .extracting(value -> value.kind().name()).containsExactly("QUEUED", "ANSWER");
        assertThat(adapter.handle(new IncomingEvent("next-event", "T1", "C1", "1.000002", "1.000001", "U1", "", "channel",
                "<@UBOT> next", "", false, false))).isEqualTo(SlackEventOutcome.ACCEPTED);
    }

    @Test
    void rediscovers_one_terminal_runtime_delivery_and_reclaims_it_after_its_lease_expires() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore initialStore = new SlackPostgresDeliveryStore(dataSource());

        initialStore.discover();
        initialStore.discover();
        SlackDeliveryClaim expiredClaim = initialStore.claimNext("first-delivery-worker", Duration.ofSeconds(30), 5).orElseThrow();
        jdbcTemplate.update("update slack_delivery set locked_until = clock_timestamp() - interval '1 millisecond' where delivery_id = ?",
                expiredClaim.deliveryId());
        DatabaseInspectingSlackWebApi slack = new DatabaseInspectingSlackWebApi(jdbcTemplate);
        SlackDeliveryWorker recoveredWorker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), slack,
                new SlackDeliveryProperties(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(60), 5),
                "recovered-delivery-worker");

        assertThat(recoveredWorker.poll()).isTrue();

        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery", Integer.class)).isEqualTo(2);
        assertThat(slack.posts).containsExactly(new SlackPostRequest("C1", "1.000001", "Runtime terminal response."));
        assertThat(slack.claimWasCommittedBeforePost).isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from slack_delivery where kind = 'ANSWER'", String.class)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject("select attempt_count from slack_delivery where kind = 'ANSWER'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(4);
    }

    @Test
    void persists_an_exhausted_transient_delivery_as_queryable_failed_without_changing_its_message_job() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        UUID deliveryId = answerDeliveryId();
        jdbcTemplate.update("update slack_delivery set attempt_count = 4 where delivery_id = ?", deliveryId);
        PostingSlackWebApi failingSlack = request -> {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, failingSlack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.attemptCount()).isEqualTo(5);
            assertThat(delivery.failureCategory()).contains(SlackDeliveryFailureCategory.TRANSIENT);
        });
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
    }


    @Test
    void preserves_whitespace_and_the_complete_terminal_runtime_notice_as_plain_text() {
        String terminalText = "\n".repeat(4_000) + "  indented answer";
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime("1.000001", terminalText);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        List<SlackPostRequest> posts = new ArrayList<>();
        PostingSlackWebApi slack = request -> {
            posts.add(request);
            return "2.000001";
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, slack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        UUID deliveryId = answerDeliveryId();
        assertThat(posts).singleElement().satisfies(post -> {
            assertThat(post.text()).isEqualTo(terminalText);
            assertThat(post.presentation()).isEqualTo(com.java.system.sessionagent.slack.domain.SlackPresentation.PLAIN);
        });
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(delivery.totalParts()).contains(1);
            assertThat(delivery.sentParts()).isEqualTo(1);
        });
        assertThat(jdbcTemplate.queryForList("""
                select part_index from slack_delivery_part where delivery_id = ? order by part_index
                """, Integer.class, deliveryId)).containsExactly(0);
        assertThat(jdbcTemplate.queryForList("""
                select part_text from slack_delivery_part where delivery_id = ? order by part_index
                """, String.class, deliveryId)).containsExactly(terminalText);
        assertThat(jdbcTemplate.queryForObject("""
                select runtime.message from runtime_message runtime
                join session_message session on session.session_id = runtime.session_id and session.sequence = runtime.sequence
                where session.message_job_id = ?
                """, String.class, UUID.fromString(receipt.messageJobId().value()))).isEqualTo(terminalText);
    }


    @Test
    void serializes_nonfinal_item_acknowledgement_with_an_overlapping_reclaim() throws Exception {
        completeSlackAssistantJob("x".repeat(10_001), false);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        SlackDeliveryClaim oldClaim = deliveryStore.claimNext("first-worker", Duration.ofSeconds(30), 5).orElseThrow();
        SlackDeliveryPart firstPart = deliveryStore.nextPart(oldClaim, Duration.ofSeconds(30)).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection partLock = dataSource().getConnection()) {
            partLock.setAutoCommit(false);
            try (PreparedStatement statement = partLock.prepareStatement("""
                    select 1 from slack_delivery_part where delivery_id = ? and part_index = 0 for update
                    """)) {
                statement.setObject(1, oldClaim.deliveryId());
                statement.executeQuery();
            }
            Future<Boolean> confirmation = executor.submit(() -> deliveryStore.markPartSent(oldClaim, firstPart, "2.000001"));
            awaitDatabaseLockWaiter("update slack_delivery_part part");
            Future<Integer> reclaim = executor.submit(() -> jdbcTemplate.update("""
                    update slack_delivery set worker_id = 'recovered-worker', claim_number = claim_number + 1,
                        locked_until = clock_timestamp() + interval '30 seconds'
                    where delivery_id = ?
                    """, oldClaim.deliveryId()));
            awaitDatabaseLockWaiter("update slack_delivery set worker_id");

            partLock.commit();

            assertThat(confirmation.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reclaim.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from slack_delivery_part where delivery_id = ? and status = 'SENT'
                """, Integer.class, oldClaim.deliveryId())).isEqualTo(1);
    }

    @Test
    void retains_a_known_truncation_timestamp_on_the_failed_part_without_marking_the_parent_sent() {
        completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        PostingSlackWebApi truncatingSlack = request -> {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty(), Optional.of("2.000099"));
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, truncatingSlack, new SlackDeliveryProperties(),
                "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(deliveryStore.read(answerDeliveryId())).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.sentParts()).isZero();
        });
        assertThat(jdbcTemplate.queryForObject("""
                select slack_message_ts from slack_delivery_part where delivery_id = ? and part_index = 0
                """, String.class, answerDeliveryId())).isEqualTo("2.000099");
    }

    @Test
    void terminalizes_an_expired_fifth_working_attempt_without_a_sixth_slack_call() {
        completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        UUID deliveryId = answerDeliveryId();
        jdbcTemplate.update("""
                update slack_delivery set status = 'WORKING', worker_id = 'stopped-worker',
                    locked_until = clock_timestamp() - interval '1 millisecond', claim_number = 1, attempt_count = 5
                where delivery_id = ?
                """, deliveryId);
        AtomicBoolean posted = new AtomicBoolean(false);
        PostingSlackWebApi slack = request -> {
            posted.set(true);
            return "2.000001";
        };
        SlackDeliveryWorker recoveredWorker = new SlackDeliveryWorker(deliveryStore, slack, new SlackDeliveryProperties(),
                "recovered-delivery-worker");

        assertThat(recoveredWorker.poll()).isFalse();

        assertThat(posted.get()).isFalse();
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.attemptCount()).isEqualTo(5);
            assertThat(delivery.failureCategory()).contains(SlackDeliveryFailureCategory.TRANSIENT);
        });
    }

    @Test
    void does_not_claim_an_answer_while_its_queued_notice_remains_unfinished() {
        completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'QUEUED'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).isEmpty();

        jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = 'PERMANENT'
                where kind = 'QUEUED'
                """);
        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.kind().name()).isEqualTo("ANSWER"));
    }

    @Test
    void returns_safe_delivery_statuses_for_one_logical_source_in_creation_order() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();

        List<SlackDeliveryView> deliveries = deliveryStore.findBySource("T1", "C1", "1.000001");

        assertThat(deliveries).extracting(delivery -> delivery.kind().name()).containsExactly("QUEUED", "ANSWER");
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.messageJobId()).contains(UUID.fromString(receipt.messageJobId().value()));
            assertThat(delivery.failureCategory()).isEmpty();
            assertThat(delivery.nextAttemptAt()).isNotNull();
        });
    }

    @Test
    void rejects_conflicting_busy_notice_for_a_source_while_permitting_its_answer() {
        completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into slack_delivery(delivery_id, kind, team_id, channel_id, root_thread_ts, message_ts, terminal_text,
                    status, attempt_count, next_attempt_at, claim_number, created_at)
                values (?, 'BUSY', 'T1', 'C1', '1.000001', '1.000001', '目前忙碌中。',
                    'PENDING', 0, clock_timestamp(), 0, clock_timestamp())
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deliveryStore.findBySource("T1", "C1", "1.000001"))
                .extracting(delivery -> delivery.kind().name())
                .containsExactly("QUEUED", "ANSWER");
    }

    @Test
    void migrates_v9_prepared_plain_items_and_resumes_only_the_unacknowledged_suffix() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).cleanDisabled(false).target("9").load().migrate();
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into slack_delivery(delivery_id,kind,terminal_session_id,terminal_sequence,message_job_id,team_id,channel_id,
                    root_thread_ts,message_ts,terminal_text,status,attempt_count,next_attempt_at,claim_number,created_at)
                select ?,'ANSWER',session_id,sequence,message_job_id,'T1','C1','1.000001','1.000001',
                    'Do not regenerate this full source','RETRY',2,clock_timestamp(),2,clock_timestamp()
                from session_message where session_id=? and message_job_id=? and role='RUNTIME'
                """, deliveryId, sessionId, jobId);
        jdbcTemplate.update("""
                insert into slack_delivery_part(delivery_id,part_index,part_text,status,slack_message_ts,sent_at)
                values (?,0,'Already acknowledged prefix','SENT','2.000010',clock_timestamp()),
                       (?,1,'Remaining exact suffix\n```text\nunchanged\n```','PENDING',null,null)
                """, deliveryId, deliveryId);
        Map<String, Object> prefix = jdbcTemplate.queryForMap("select * from slack_delivery_part where delivery_id=? and part_index=0", deliveryId);
        // Compare only original V9 evidence; presentation is the new migration-owned column.
        Map<String, Object> remaining = jdbcTemplate.queryForMap("""
                select delivery_id,part_index,part_text,status,slack_message_ts,sent_at from slack_delivery_part
                where delivery_id=? and part_index=1
                """, deliveryId);
        flyway().migrate();
        assertThat(jdbcTemplate.queryForObject("select items_prepared from slack_delivery where delivery_id=?", Boolean.class, deliveryId)).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                select delivery_id,part_index,part_text,status,slack_message_ts,sent_at from slack_delivery_part
                where delivery_id=? and part_index=0
                """, deliveryId)).isEqualTo(prefix);
        assertThat(jdbcTemplate.queryForMap("""
                select delivery_id,part_index,part_text,status,slack_message_ts,sent_at from slack_delivery_part
                where delivery_id=? and part_index=1
                """, deliveryId)).isEqualTo(remaining);
        List<SlackPostRequest> posts = new ArrayList<>();
        SlackPostgresDeliveryStore store = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, (PostingSlackWebApi) request -> {
            posts.add(request);
            return "2.000011";
        }, new SlackDeliveryProperties(), "v10-resumer");
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isFalse();
        assertThat(posts).containsExactly(new SlackPostRequest("C1", "1.000001", "Remaining exact suffix\n```text\nunchanged\n```"));
        assertThat(store.read(deliveryId)).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(view.totalParts()).contains(2);
            assertThat(view.sentParts()).isEqualTo(2);
        });
        assertThat(jdbcTemplate.queryForMap("""
                select delivery_id,part_index,part_text,status,slack_message_ts,sent_at from slack_delivery_part
                where delivery_id=? and part_index=0
                """, deliveryId)).isEqualTo(prefix);
        assertThat(jdbcTemplate.queryForList("select presentation from slack_delivery_part where delivery_id=? order by part_index",
                String.class, deliveryId)).containsExactly("PLAIN", "PLAIN");
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery_upload", Integer.class)).isZero();
    }

    @Test
    void migrates_a_v2_answer_delivery_with_its_accepted_source_and_progress_preserved() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).cleanDisabled(false).target("2").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        ConversationModels models = TestConfiguredModels.withDefault("test");
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "v2-session", "U1", "v2-source",
                "v2 request");
        MessageReceipt receipt = conversationStore.receive(message, models.defaultSelection(), models);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID messageJobId = UUID.fromString(receipt.messageJobId().value());
        // Seed the V2 contract directly; current job processing requires the latest schema.
        jdbcTemplate.update("""
                with allocated as (
                    update conversation_session set next_sequence = next_sequence + 1
                    where session_id = ?
                    returning session_id, next_sequence - 1 as sequence
                ), event as (
                    insert into session_message(session_id, sequence, message_job_id, role, created_at)
                    select session_id, sequence, ?, 'RUNTIME', clock_timestamp() from allocated
                    returning session_id, sequence
                )
                insert into runtime_message(session_id, sequence, code, message)
                select session_id, sequence, 'MODEL_UNAVAILABLE', 'Runtime terminal response.' from event
                """, sessionId, messageJobId);
        jdbcTemplate.update("""
                update message_job set status = 'DONE', completed_at = clock_timestamp()
                where message_job_id = ?
                """, messageJobId);
        Long terminalSequence = jdbcTemplate.queryForObject("""
                select sequence from session_message
                where session_id = ? and message_job_id = ? and role = 'RUNTIME'
                """, Long.class, sessionId, messageJobId);
        UUID deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into slack_message_receipt(team_id, channel_id, message_ts, top_level, classification, session_id,
                    message_job_id, created_at)
                values ('Tv2', 'Cv2', '9.000001', true, 'ACCEPTED', ?, ?, clock_timestamp())
                """, sessionId, messageJobId);
        jdbcTemplate.update("""
                insert into slack_delivery(delivery_id, terminal_session_id, terminal_sequence, message_job_id, channel_id,
                    root_thread_ts, terminal_text, status, attempt_count, next_attempt_at, claim_number, slack_message_ts,
                    created_at, sent_at)
                values (?, ?, ?, ?, 'Cv2', '9.000001', 'v2 terminal text', 'SENT', 3, clock_timestamp(), 3,
                    '10.000001', clock_timestamp(), clock_timestamp())
                """, deliveryId, sessionId, terminalSequence, messageJobId);

        flyway().migrate();

        Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                select kind, team_id, channel_id, root_thread_ts, message_ts, terminal_session_id, terminal_sequence,
                    message_job_id, terminal_text, status, attempt_count, claim_number, slack_message_ts
                from slack_delivery where delivery_id = ?
                """, deliveryId);
        assertThat(delivery).containsEntry("kind", "ANSWER").containsEntry("team_id", "Tv2")
                .containsEntry("channel_id", "Cv2").containsEntry("root_thread_ts", "9.000001")
                .containsEntry("message_ts", "9.000001").containsEntry("terminal_session_id", sessionId)
                .containsEntry("terminal_sequence", terminalSequence).containsEntry("message_job_id", messageJobId)
                .containsEntry("terminal_text", "v2 terminal text").containsEntry("status", "SENT")
                .containsEntry("attempt_count", 3).containsEntry("claim_number", 3L)
                .containsEntry("slack_message_ts", "10.000001");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from slack_delivery delivery
                join slack_message_receipt receipt on receipt.team_id = delivery.team_id
                    and receipt.channel_id = delivery.channel_id and receipt.message_ts = delivery.message_ts
                    and receipt.message_job_id = delivery.message_job_id
                where delivery.delivery_id = ? and receipt.classification = 'ACCEPTED'
                """, Integer.class, deliveryId)).isEqualTo(1);
    }

    @Test
    void does_not_claim_a_later_answer_for_the_same_session_while_an_earlier_answer_retries() {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), intakeCapacity);
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake,
                new JobCancellationService(conversationStore), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity);
        assertThat(slackIntake.receive(acceptedIntake("event-root", "1.000001", "1.000001")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(slackIntake.receive(acceptedIntake("event-reply-one", "1.000001", "1.000002")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(slackIntake.receive(acceptedIntake("event-reply-two", "1.000001", "1.000003")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        completeNextWithTerminalRuntime(conversationStore);
        completeNextWithTerminalRuntime(conversationStore);
        completeNextWithTerminalRuntime(conversationStore);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'SENT', slack_message_ts = '2.000000', sent_at = clock_timestamp()
                where kind = 'ANSWER' and message_ts = '1.000001'
                """);
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'ANSWER' and message_ts = '1.000002'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).isEmpty();

        jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = 'PERMANENT'
                where kind = 'ANSWER' and message_ts = '1.000002'
                """);
        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.postRequest().text()).isEqualTo("Runtime terminal response."));
    }

    @Test
    void claims_another_session_answer_while_a_previous_session_answer_retries() {
        completeSlackJobWithTerminalRuntime("1.000001");
        completeSlackJobWithTerminalRuntime("2.000001");
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'ANSWER' and message_ts = '1.000001'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.postRequest().rootThreadTs()).isEqualTo("2.000001"));
    }

    @Test
    void rolls_back_the_session_job_receipts_and_notice_when_notice_persistence_fails() {
        jdbcTemplate.execute("""
                create function reject_slack_delivery_insert() returns trigger language plpgsql as $$
                begin
                    raise exception 'delivery write rejected';
                end;
                $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_slack_delivery_insert before insert on slack_delivery
                for each row execute function reject_slack_delivery_insert()
                """);
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), intakeCapacity);
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake,
                new JobCancellationService(conversationStore), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity);
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:1.000001", "U1", "1.000001", "request");
        MessageSubmission intake = new MessageSubmission("event-rollback", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.ACCEPTED, Optional.of(message));

        assertThatThrownBy(() -> slackIntake.receive(intake)).hasMessageContaining("delivery write rejected");

        assertThat(jdbcTemplate.queryForObject("select count(*) from conversation_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_thread_binding", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_message_receipt", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_event_receipt", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery", Integer.class)).isZero();
    }

    @Test
    void correlates_new_and_duplicate_slack_intake_through_model_processing_and_terminal_delivery() {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), intakeCapacity);
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        MessageHandler adapter = new MessageHandler("UBOT",
                new PostgresMessageSubmission(dataSource(), messageIntake, new JobCancellationService(conversationStore),
                        Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity));
        ListAppender<ILoggingEvent> intakeLogs = capture(MessageHandler.class);
        ListAppender<ILoggingEvent> modelLogs = capture(JobProcessor.class);
        ListAppender<ILoggingEvent> deliveryLogs = capture(SlackDeliveryWorker.class);
        IncomingEvent event = new IncomingEvent("event-lifecycle", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> hello", "", false, false);
        List<SlackPostRequest> posts = new ArrayList<>();
        try {
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
            MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
            ConversationModel model = successfulModel();
            JobProcessor service = new JobProcessor(conversationStore, TestConfiguredModels.withModel(model), emptyToolCatalog(), new FileSystemSkillLibrary(new SkillProperties()),
                    Clock.fixed(NOW, ZoneOffset.UTC), 3, new MessageJobRetryPolicy(3, Duration.ofSeconds(1)),
                    new NoOpConversationTelemetry());
            service.process(claim, () -> true);
            SlackDeliveryWorker deliveryWorker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), (PostingSlackWebApi) request -> {
                posts.add(request);
                return "2.000001";
            }, new SlackDeliveryProperties(), "delivery-worker");
            assertThat(deliveryWorker.poll()).isTrue();
            assertThat(deliveryWorker.poll()).isTrue();
        } finally {
            detach(MessageHandler.class, intakeLogs);
            detach(JobProcessor.class, modelLogs);
            detach(SlackDeliveryWorker.class, deliveryLogs);
        }

        List<Map<String, Object>> inbound = lifecycleEvents(intakeLogs, "slack_inbound");
        assertThat(inbound).hasSize(2);
        assertThat(inbound).extracting(values -> values.get("disposition"))
                .containsExactly("NEW_ACCEPTED", "DUPLICATE_ACCEPTED");
        Object sessionId = inbound.getFirst().get("sessionId");
        Object messageJobId = inbound.getFirst().get("messageJobId");
        assertThat(inbound.getLast()).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId);
        assertThat(lifecycleEvents(intakeLogs, "session_created")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(intakeLogs, "session_resolved")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(modelLogs, "model_request")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(modelLogs, "model_response")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt")).hasSize(2);
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt").getFirst())
                .containsEntry("kind", "QUEUED").containsEntry("messageJobId", messageJobId)
                .doesNotContainKey("sessionId");
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt").getLast())
                .containsEntry("kind", "ANSWER").containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId);
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_item_sent")).hasSize(2);
        assertThat(posts).containsExactly(
                new SlackPostRequest("C1", "1.000001", "已收到，正在排隊處理。"),
                new SlackPostRequest("C1", "1.000001", "done", SlackPresentation.MARKDOWN));
    }

    private static ConversationModel successfulModel() {
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("observability-test"), "fake", 1_000_000);
        return new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return descriptor.routeId();
            }

            @Override
            public ModelDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                reservation.reserve();
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
    }

    private static ToolCatalog emptyToolCatalog() {
        return () -> new ToolSnapshot(List.of());
    }

    private static ListAppender<ILoggingEvent> capture(Class<?> source) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> source, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<Map<String, Object>> lifecycleEvents(ListAppender<ILoggingEvent> appender, String name) {
        return appender.list.stream().map(SlackDeliveryPostgresIT::keyValues)
                .filter(values -> name.equals(values.get("event"))).toList();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime() {
        return completeSlackJobWithTerminalRuntime("1.000001");
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime(String messageTs) {
        return completeSlackJobWithTerminalRuntime(messageTs, "Runtime terminal response.");
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime(String messageTs, String terminalText) {
        return completeSlackJob(messageTs, terminalText, false, false);
    }

    private MessageReceipt completeSlackAssistantJob(String terminalText, boolean artifact) {
        return completeSlackJob("1.000001", terminalText, true, artifact);
    }

    private MessageReceipt completeSlackJob(String messageTs, String terminalText, boolean assistant, boolean artifact) {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), intakeCapacity);
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake,
                new JobCancellationService(conversationStore), Clock.fixed(NOW, ZoneOffset.UTC), intakeCapacity);
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:" + messageTs, "U1", messageTs,
                "request");
        MessageSubmission intake = new MessageSubmission("event-" + messageTs, "T1", "C1", messageTs, messageTs,
                SlackIntakeClassification.ACCEPTED, Optional.of(message));

        assertThat(slackIntake.receive(intake).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        MessageReceipt receipt = conversationStore.receive(message, TestConfiguredModels.withDefault("test").defaultSelection(),
                TestConfiguredModels.withDefault("test"));
        if (assistant) {
            MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
            conversationStore.reserveModelCall(claim, 2, NOW).orElseThrow();
            if (artifact) {
                Map<String, Object> arguments = Map.of("title", "Tool report", "content", "# Tool report\nExact 中😀");
                ArtifactEncoder.Encoded encoded = ArtifactEncoder.markdown(arguments);
                ArtifactStore.Saved saved = new PostgresArtifactStore(dataSource(), new ObjectMapper()).create(
                        TestToolInvocation.of(claim, 1, 0), encoded.title(), encoded.extension(), encoded.mediaType(),
                        encoded.content(), "createMarkdownReport", arguments, encoded.disclosures());
                ToolOutput output = new ToolOutput(false, saved.result(), Optional.of(saved.receipt()));
                ToolCallId call = new ToolCallId("export-call");
                conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(
                        new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                                new ConversationStore.ToolCallData(call, "createMarkdownReport", arguments))),
                        new ConversationStore.ToolObservationData(call, "createMarkdownReport", output.asStructuredValue(), output.artifactReceipt())),
                        ConversationStore.JobUpdate.KEEP_WORKING), NOW);
            }
            conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData(terminalText)),
                    ConversationStore.JobUpdate.COMPLETE), NOW);
        } else {
            completeNextWithTerminalRuntime(conversationStore, terminalText);
        }
        return receipt;
    }

    private static MessageSubmission acceptedIntake(String eventId, String rootThreadTs, String messageTs) {
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:" + rootThreadTs, "U1",
                "slack:T1:C1:" + messageTs, "request");
        return new MessageSubmission(eventId, "T1", "C1", rootThreadTs, messageTs, SlackIntakeClassification.ACCEPTED,
                Optional.of(message));
    }

    private static void completeNextWithTerminalRuntime(PostgresConversationStore conversationStore) {
        completeNextWithTerminalRuntime(conversationStore, "Runtime terminal response.");
    }

    private static void completeNextWithTerminalRuntime(PostgresConversationStore conversationStore, String terminalText) {
        MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(conversationStore.reserveModelCall(claim, 2, NOW)).hasValue(1);
        conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.of("Intermediate tool-call text"), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "lookup", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "lookup", Map.of("result", "value")),
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", terminalText)),
                ConversationStore.JobUpdate.COMPLETE), NOW);
    }

    private UUID answerDeliveryId() {
        return jdbcTemplate.queryForObject("select delivery_id from slack_delivery where kind = 'ANSWER'", UUID.class);
    }

    private void awaitDatabaseLockWaiter(String queryFragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock' and query like ?
                    """, Integer.class, "%" + queryFragment + "%");
            if (waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Expected PostgreSQL lock waiter was not observed");
    }


    private void markQueuedNoticeSent() {
        jdbcTemplate.update("""
                update slack_delivery set status = 'SENT', slack_message_ts = '2.000000', sent_at = clock_timestamp()
                where kind = 'QUEUED'
                """);
    }

    private DriverManagerDataSource dataSource() {
        return dataSource;
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }

    private static final class DatabaseInspectingSlackWebApi implements PostingSlackWebApi {

        private final JdbcTemplate jdbcTemplate;
        private final List<SlackPostRequest> posts = new ArrayList<>();
        private boolean claimWasCommittedBeforePost;

        private DatabaseInspectingSlackWebApi(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public String post(SlackPostRequest request) {
            claimWasCommittedBeforePost = "WORKING".equals(jdbcTemplate.queryForObject(
                    "select status from slack_delivery where kind = 'ANSWER'", String.class));
            posts.add(request);
            return "2.000001";
        }
    }
}
