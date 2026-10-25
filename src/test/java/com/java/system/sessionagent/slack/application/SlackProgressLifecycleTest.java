package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.configuration.SlackProgressProperties;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackProgressClaim;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import com.java.system.sessionagent.slack.port.out.SlackProgressStore;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import com.java.system.sessionagent.slack.TextSlackWebApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;

class SlackProgressLifecycleTest {
    @Test
    void a_blocked_progress_http_call_cannot_block_actual_answer_delivery_and_shutdown_is_bounded() throws Exception {
        CountDownLatch updateStarted = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        CountDownLatch answerAcknowledged = new CountDownLatch(1);
        SlackProgressStore progressStore = Mockito.mock(SlackProgressStore.class);
        SlackProgressClaim claim = new SlackProgressClaim(UUID.randomUUID(), "progress", 1);
        Mockito.when(progressStore.claimNext(Mockito.anyString(), Mockito.any())).thenReturn(Optional.of(claim)).thenReturn(Optional.empty());
        Mockito.when(progressStore.refresh(claim)).thenReturn(Optional.of(new SlackProgressUpdate(1,
                new SlackUpdateRequest("C1", "notice-ts", "model waiting"), false, Instant.now(), "1.000001",
                Optional.of("https://example.slack.com/archives/C1/p1000001"))));
        SlackWebApi api = new TextSlackWebApi() {
            @Override
            public String post(SlackPostRequest request) {
                assertThat(request.text()).isEqualTo("Committed answer");
                return "answer-ts";
            }
            @Override
            public void update(SlackUpdateRequest request) {
                updateStarted.countDown();
                while (releaseUpdate.getCount() > 0) {
                    try { releaseUpdate.await(); }
                    catch (InterruptedException ignored) { /* Model an uncooperative HTTP operation. */ }
                }
            }
            @Override
            public String getPermalink(String channelId, String messageTs) {
                throw new AssertionError("Cached question must not look up its permalink again");
            }
        };
        SlackDeliveryStore answers = Mockito.mock(SlackDeliveryStore.class);
        SlackDeliveryClaim answer = new SlackDeliveryClaim(UUID.randomUUID(), UUID.randomUUID(), claim.messageJobId(),
                1, 1, "answers", Instant.now().plusSeconds(30), new SlackPostRequest("C1", "question-ts", "Committed answer"));
        SlackDeliveryPart part = new SlackDeliveryPart(0, "Committed answer");
        Mockito.when(answers.claimNext(Mockito.anyString(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(Optional.of(answer)).thenReturn(Optional.empty());
        Mockito.when(answers.nextPart(Mockito.eq(answer), Mockito.any()))
                .thenReturn(Optional.of(part)).thenReturn(Optional.empty());
        Mockito.when(answers.markPartSent(answer, part, "answer-ts")).thenAnswer(invocation -> {
            answerAcknowledged.countDown();
            return true;
        });
        SlackProperties slack = enabled();
        SlackProgressLifecycle progress = new SlackProgressLifecycle(new SlackProgressWorker(progressStore, api,
                new SlackDeliveryProperties(), "progress"), slack, fastProgress());
        SlackDeliveryLifecycle delivery = new SlackDeliveryLifecycle(new SlackDeliveryWorker(answers, api,
                new SlackDeliveryProperties(), "answers"), slack);
        try {
            progress.start();
            assertThat(updateStarted.await(2, TimeUnit.SECONDS)).isTrue();
            delivery.start();
            assertThat(answerAcknowledged.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseUpdate.getCount()).isEqualTo(1);
            long before = System.nanoTime();
            progress.stop();
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(500));
            assertThat(progress.isRunning()).isFalse();
        } finally {
            releaseUpdate.countDown();
            delivery.stop();
            progress.stop();
        }
    }

    @Test
    void disabled_slack_never_polls_progress() {
        SlackProgressStore store = Mockito.mock(SlackProgressStore.class);
        SlackWebApi api = Mockito.mock(SlackWebApi.class);
        SlackProperties disabled = new SlackProperties("", "", "", Duration.ofSeconds(1), Duration.ofMillis(25));
        SlackProgressLifecycle lifecycle = new SlackProgressLifecycle(new SlackProgressWorker(store, api,
                new SlackDeliveryProperties(), "progress"), disabled, fastProgress());
        try {
            lifecycle.start();
            assertThat(lifecycle.isRunning()).isFalse();
            Mockito.verifyNoInteractions(store, api);
        } finally { lifecycle.stop(); }
    }

    @Test
    void a_failed_poll_is_recovered_on_the_next_scheduled_tick() throws Exception {
        SlackProgressStore store = Mockito.mock(SlackProgressStore.class);
        CountDownLatch recovered = new CountDownLatch(1);
        Mockito.doThrow(new IllegalStateException("database unavailable"))
                .doAnswer(invocation -> { recovered.countDown(); return null; }).when(store).discover();
        Mockito.when(store.claimNext(Mockito.anyString(), Mockito.any())).thenReturn(Optional.empty());
        SlackProgressLifecycle lifecycle = new SlackProgressLifecycle(new SlackProgressWorker(store,
                Mockito.mock(SlackWebApi.class), new SlackDeliveryProperties(), "progress"), enabled(), fastProgress());
        try {
            lifecycle.start();
            lifecycle.start();
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(lifecycle.isRunning()).isTrue();
        } finally { lifecycle.stop(); }
    }

    private static SlackProgressProperties fastProgress() {
        return new SlackProgressProperties(Duration.ofMillis(10), Duration.ofSeconds(30), Duration.ofSeconds(2));
    }
    private static SlackProperties enabled() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofMillis(25));
    }
}
