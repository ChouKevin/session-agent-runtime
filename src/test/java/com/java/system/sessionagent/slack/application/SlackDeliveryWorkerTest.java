package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.TextOnlySlackDeliveryStore;
import com.java.system.sessionagent.slack.PostingSlackWebApi;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SlackDeliveryWorkerTest {

    @Test
    void retries_the_exact_committed_terminal_text_after_a_rate_limit_then_marks_the_delivery_sent() {
        SlackPostRequest committedPost = new SlackPostRequest("C1", "1.000001", "Committed terminal response");
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(
                claim(1, 1, committedPost), claim(2, 2, committedPost)));
        FakeSlackWebApi slack = new FakeSlackWebApi();
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(7))));
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(60), 5), "delivery-worker");

        assertThat(worker.poll()).isTrue();
        assertThat(store.retryDelays).containsExactly(Duration.ofSeconds(7));
        assertThat(store.retryCategories).containsExactly(SlackDeliveryFailureCategory.RATE_LIMIT);

        assertThat(worker.poll()).isTrue();

        assertThat(slack.posts).containsExactly(committedPost, committedPost);
        assertThat(store.sentMessageTimestamps).containsExactly("2.000001");
        assertThat(store.transactionActiveDuringPost).isFalse();
    }

    @Test
    void marks_a_permanent_failure_terminal_without_retrying() {
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(claim(1, 1,
                new SlackPostRequest("C1", "1.000001", "Committed terminal response"))));
        FakeSlackWebApi slack = new FakeSlackWebApi();
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty()));
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(store.failedCategories).containsExactly(SlackDeliveryFailureCategory.PERMANENT);
        assertThat(store.retryDelays).isEmpty();
    }

    @Test
    void bounds_transient_backoff_and_marks_the_fifth_failed_attempt_terminal() {
        SlackPostRequest committedPost = new SlackPostRequest("C1", "1.000001", "Committed terminal response");
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(claim(1, 3, committedPost), claim(2, 5, committedPost)));
        FakeSlackWebApi slack = new FakeSlackWebApi();
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty()));
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty()));
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(3), 5), "delivery-worker");

        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isTrue();

        assertThat(store.retryDelays).containsExactly(Duration.ofSeconds(3));
        assertThat(store.retryCategories).containsExactly(SlackDeliveryFailureCategory.TRANSIENT);
        assertThat(store.failedCategories).containsExactly(SlackDeliveryFailureCategory.TRANSIENT);
    }

    @Test
    void reports_an_ambiguous_post_instead_of_sent_when_the_claim_guarded_transition_is_rejected() {
        SlackDeliveryClaim claim = claim(1, 1, new SlackPostRequest("C1", "1.000001", "Committed terminal response"));
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(claim));
        store.transitionsAccepted = false;
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, (PostingSlackWebApi) request -> "2.000001",
                new SlackDeliveryProperties(), "delivery-worker");
        Logger logger = (Logger) LoggerFactory.getLogger(SlackDeliveryWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(worker.poll()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(event -> keyValues(event).get("event"))
                .contains("slack_delivery_attempt", "slack_delivery_ambiguous")
                .doesNotContain("slack_delivery_item_sent");
        ILoggingEvent ambiguous = appender.list.stream()
                .filter(event -> "slack_delivery_ambiguous".equals(keyValues(event).get("event")))
                .findFirst().orElseThrow();
        assertThat(keyValues(ambiguous)).containsEntry("sessionId", claim.sessionId().orElseThrow().toString())
                .containsEntry("messageJobId", claim.messageJobId().orElseThrow().toString())
                .containsEntry("outcome", "POSTED_STATE_NOT_COMMITTED");
    }

    @Test
    void reports_ownership_loss_instead_of_retry_or_failure_when_failure_transitions_are_rejected() {
        SlackDeliveryClaim permanentClaim = claim(1, 1,
                new SlackPostRequest("C1", "1.000001", "Committed terminal response"));
        SlackDeliveryClaim retryClaim = claim(2, 1,
                new SlackPostRequest("C1", "1.000001", "Committed terminal response"));
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(permanentClaim, retryClaim));
        store.transitionsAccepted = false;
        FakeSlackWebApi slack = new FakeSlackWebApi();
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty()));
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty()));
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(), "delivery-worker");
        Logger logger = (Logger) LoggerFactory.getLogger(SlackDeliveryWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(worker.poll()).isTrue();
            assertThat(worker.poll()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<Map<String, Object>> ownershipLost = appender.list.stream()
                .map(SlackDeliveryWorkerTest::keyValues)
                .filter(values -> "slack_delivery_ownership_lost".equals(values.get("event")))
                .toList();
        assertThat(ownershipLost).extracting(values -> values.get("outcome"))
                .containsExactly("FAILED_STATE_NOT_COMMITTED", "RETRY_STATE_NOT_COMMITTED");
        assertThat(ownershipLost).extracting(values -> values.get("sessionId"))
                .containsExactly(permanentClaim.sessionId().orElseThrow().toString(), retryClaim.sessionId().orElseThrow().toString());
        assertThat(appender.list).extracting(event -> keyValues(event).get("event"))
                .doesNotContain("slack_delivery_failed", "slack_delivery_retry");
    }

    @Test
    void delivers_a_whole_persisted_markdown_item_without_a_splitter() {
        String text = "x".repeat(8_001);
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(claim(1, 1,
                new SlackPostRequest("C1", "1.000001", text, com.java.system.sessionagent.slack.domain.SlackPresentation.MARKDOWN))));
        FakeSlackWebApi slack = new FakeSlackWebApi();
        assertThat(new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(), "delivery-worker").poll()).isTrue();
        assertThat(slack.posts).extracting(SlackPostRequest::text).containsExactly(text);
        assertThat(slack.posts.getFirst().presentation()).isEqualTo(com.java.system.sessionagent.slack.domain.SlackPresentation.MARKDOWN);
    }



    @Test
    void marks_a_truncated_part_permanently_failed_while_retaining_its_known_timestamp() {
        FakeDeliveryStore store = new FakeDeliveryStore(List.of(claim(1, 1,
                new SlackPostRequest("C1", "1.000001", "Committed terminal response"))));
        FakeSlackWebApi slack = new FakeSlackWebApi();
        slack.failOnce(new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty(), Optional.of("2.000004")));
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, slack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(store.failedCategories).containsExactly(SlackDeliveryFailureCategory.PERMANENT);
        assertThat(store.failedMessageTimestamps).containsExactly(Optional.of("2.000004"));
    }


    private static SlackDeliveryClaim claim(long claimNumber, int attempts, SlackPostRequest post) {
        return new SlackDeliveryClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), claimNumber, attempts, "delivery-worker",
                Instant.parse("2026-09-06T00:00:30Z"), post);
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static final class FakeDeliveryStore implements TextOnlySlackDeliveryStore {

        private final Queue<SlackDeliveryClaim> claims;
        private final List<Duration> retryDelays = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> retryCategories = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> failedCategories = new ArrayList<>();
        private final List<String> sentMessageTimestamps = new ArrayList<>();
        private final List<Optional<String>> failedMessageTimestamps = new ArrayList<>();
        private final Set<PartKey> sentParts = new HashSet<>();
        private boolean transactionActiveDuringPost;
        private boolean transitionsAccepted = true;

        private FakeDeliveryStore(List<SlackDeliveryClaim> claims) {
            this.claims = new ArrayDeque<>(claims);
        }

        @Override
        public void discover() {
        }

        @Override
        public Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts) {
            transactionActiveDuringPost = false;
            return Optional.ofNullable(claims.poll());
        }

        @Override
        public Optional<com.java.system.sessionagent.slack.domain.SlackDeliveryPart> nextPart(
                SlackDeliveryClaim claim,
                Duration leaseDuration) {
            return sentParts.contains(new PartKey(claim.deliveryId(), 0)) ? Optional.empty()
                    : Optional.of(new com.java.system.sessionagent.slack.domain.SlackDeliveryPart(0, claim.postRequest().text(),
                            claim.postRequest().presentation(), Optional.empty()));
        }

        @Override
        public boolean markPartSent(
                SlackDeliveryClaim claim,
                com.java.system.sessionagent.slack.domain.SlackDeliveryPart part,
                String slackMessageTs) {
            sentMessageTimestamps.add(slackMessageTs);
            sentParts.add(new PartKey(claim.deliveryId(), part.index()));
            return transitionsAccepted;
        }

        @Override
        public boolean markPartFailed(
                SlackDeliveryClaim claim,
                com.java.system.sessionagent.slack.domain.SlackDeliveryPart part,
                SlackDeliveryFailureCategory category,
            Optional<String> slackMessageTs) {
            failedCategories.add(category);
            failedMessageTimestamps.add(slackMessageTs);
            return transitionsAccepted;
        }

        @Override
        public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
            retryCategories.add(category);
            retryDelays.add(delay);
            return transitionsAccepted;
        }

        @Override
        public Optional<SlackDeliveryView> read(UUID deliveryId) {
            return Optional.empty();
        }

        @Override
        public List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs) {
            return List.of();
        }


        private record PartKey(UUID deliveryId, int index) {
        }
    }

    private static final class FakeSlackWebApi implements PostingSlackWebApi {

        private final List<SlackPostRequest> posts = new ArrayList<>();
        private final Queue<SlackPostFailure> failures = new ArrayDeque<>();

        private void failOnce(SlackPostFailure failure) {
            failures.add(failure);
        }


        @Override
        public String post(SlackPostRequest request) {
            posts.add(request);
            SlackPostFailure failure = failures.poll();
            if (java.util.Objects.nonNull(failure)) {
                throw failure;
            }
            return "2.000001";
        }
    }
}
