package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SlackDeliveryLifecycleTest {

    @Test
    void isolates_a_blocking_slack_call_from_message_polling_and_bounds_shutdown() throws Exception {
        CountDownLatch slackCallStarted = new CountDownLatch(1);
        CountDownLatch releaseSlackCall = new CountDownLatch(1);
        CountDownLatch messagePollRan = new CountDownLatch(1);
        SlackDeliveryWorker worker = new SlackDeliveryWorker(
                new OneClaimStore(),
                request -> awaitRelease(slackCallStarted, releaseSlackCall),
                new SlackDeliveryProperties(),
                "delivery-worker");
        SlackDeliveryLifecycle lifecycle = new SlackDeliveryLifecycle(worker, enabledProperties());
        ThreadPoolTaskScheduler messageJobScheduler = new ThreadPoolTaskScheduler();
        messageJobScheduler.setPoolSize(1);
        messageJobScheduler.initialize();
        try {
            lifecycle.start();
            assertThat(slackCallStarted.await(1, TimeUnit.SECONDS)).isTrue();

            messageJobScheduler.execute(messagePollRan::countDown);

            assertThat(messagePollRan.await(1, TimeUnit.SECONDS)).isTrue();
            Instant stopStarted = Instant.now();
            lifecycle.stop();
            assertThat(Duration.between(stopStarted, Instant.now())).isLessThan(Duration.ofMillis(250));
        } finally {
            releaseSlackCall.countDown();
            messageJobScheduler.shutdown();
        }
    }

    private static String awaitRelease(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException exception) {
                // The fake models an uncooperative network call; lifecycle shutdown must still be bounded.
            }
        }
        return "2.000001";
    }

    private static SlackProperties enabledProperties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofMillis(25));
    }

    private static final class OneClaimStore implements SlackDeliveryStore {

        private final Queue<SlackDeliveryClaim> claims = new ArrayDeque<>(java.util.List.of(new SlackDeliveryClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, "delivery-worker", Instant.now().plusSeconds(30),
                new SlackPostRequest("C1", "1.000001", "Committed terminal response"))));

        @Override
        public void discover() {
        }

        @Override
        public Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts) {
            return Optional.ofNullable(claims.poll());
        }

        @Override
        public boolean markSent(SlackDeliveryClaim claim, String slackMessageTs) {
            return true;
        }

        @Override
        public boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category) {
            return true;
        }

        @Override
        public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
            return true;
        }

        @Override
        public Optional<SlackDeliveryView> read(UUID deliveryId) {
            return Optional.empty();
        }
    }
}
