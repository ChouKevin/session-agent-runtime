package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

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

    private static SlackDeliveryClaim claim(long claimNumber, int attempts, SlackPostRequest post) {
        return new SlackDeliveryClaim(UUID.randomUUID(), claimNumber, attempts, "delivery-worker",
                Instant.parse("2026-09-06T00:00:30Z"), post);
    }

    private static final class FakeDeliveryStore implements SlackDeliveryStore {

        private final Queue<SlackDeliveryClaim> claims;
        private final List<Duration> retryDelays = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> retryCategories = new ArrayList<>();
        private final List<SlackDeliveryFailureCategory> failedCategories = new ArrayList<>();
        private final List<String> sentMessageTimestamps = new ArrayList<>();
        private boolean transactionActiveDuringPost;

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
            return true;
        }

        @Override
        public boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category) {
            failedCategories.add(category);
            return true;
        }

        @Override
        public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
            retryCategories.add(category);
            retryDelays.add(delay);
            return true;
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
