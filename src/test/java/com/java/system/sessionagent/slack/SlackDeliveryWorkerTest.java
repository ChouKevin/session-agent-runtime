package com.java.system.sessionagent.slack;

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
        SlackDeliveryWorker worker = new SlackDeliveryWorker(store, request -> "2.000001",
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
                .doesNotContain("slack_delivery_sent");
        ILoggingEvent ambiguous = appender.list.stream()
                .filter(event -> "slack_delivery_ambiguous".equals(keyValues(event).get("event")))
                .findFirst().orElseThrow();
        assertThat(keyValues(ambiguous)).containsEntry("sessionId", claim.sessionId().toString())
                .containsEntry("messageJobId", claim.messageJobId().toString())
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
                .containsExactly(permanentClaim.sessionId().toString(), retryClaim.sessionId().toString());
        assertThat(appender.list).extracting(event -> keyValues(event).get("event"))
                .doesNotContain("slack_delivery_failed", "slack_delivery_retry");
    }

    private static SlackDeliveryClaim claim(long claimNumber, int attempts, SlackPostRequest post) {
        return new SlackDeliveryClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), claimNumber, attempts, "delivery-worker",
                Instant.parse("2026-09-06T00:00:30Z"), post);
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static final class FakeDeliveryStore implements SlackDeliveryStore {

        private final Queue<SlackDeliveryClaim> claims;
        private final List<Duration> retryDelays = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> retryCategories = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> failedCategories = new ArrayList<>();
        private final List<String> sentMessageTimestamps = new ArrayList<>();
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
        public boolean markSent(SlackDeliveryClaim claim, String slackMessageTs) {
            sentMessageTimestamps.add(slackMessageTs);
            return transitionsAccepted;
        }

        @Override
        public boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category) {
            failedCategories.add(category);
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
    }

    private static final class FakeSlackWebApi implements SlackWebApi {

        private final List<SlackPostRequest> posts = new ArrayList<>();
        private final Queue<SlackPostFailure> failures = new ArrayDeque<>();

        private void failOnce(SlackPostFailure failure) {
            failures.add(failure);
        }

        @Override
        public String post(SlackPostRequest request) {
            posts.add(request);
            SlackPostFailure failure = failures.poll();
            if (failure != null) { // cs-allow Queue API uses null as the empty sentinel
                throw failure;
            }
            return "2.000001";
        }
    }
}
