package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackProgressClaim;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;
import com.java.system.sessionagent.slack.port.out.SlackProgressStore;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import com.java.system.sessionagent.slack.TextSlackWebApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;

class SlackProgressWorkerTest {
    private final SlackProgressStore store = Mockito.mock(SlackProgressStore.class);
    private final SlackProgressClaim first = new SlackProgressClaim(UUID.randomUUID(), "progress-worker", 1);
    private final SlackProgressClaim recovered = new SlackProgressClaim(first.messageJobId(), "progress-worker", 2);
    private final List<SlackUpdateRequest> sent = new ArrayList<>();

    @Test
    void claims_and_refreshes_latest_before_http_and_acknowledges_the_exact_revision_after_http() {
        SlackProgressUpdate update = update(4, "model waiting", false);
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30))).thenReturn(Optional.of(first));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(update));
        SlackWebApi api = api(request -> {
            Mockito.verify(store, Mockito.never()).markSent(Mockito.any(), Mockito.anyLong());
            sent.add(request);
        });

        assertThat(worker(api).poll()).isTrue();

        assertThat(sent).containsExactly(new SlackUpdateRequest("C1", "confirmed-notice", "model waiting"));
        InOrder order = Mockito.inOrder(store);
        order.verify(store).discover();
        order.verify(store).claimNext("progress-worker", Duration.ofSeconds(30));
        order.verify(store).refresh(first);
        order.verify(store).markSent(first, 4);
    }

    @Test
    void retry_reobserves_changed_state_and_preserves_the_database_observation_instead_of_replaying_failed_text() {
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(first)).thenReturn(Optional.of(recovered));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(update(1, "model waiting at original observation", false)));
        Mockito.when(store.refresh(recovered)).thenReturn(Optional.of(update(2, "delivering at new observation", false)));
        SlackProgressWorker worker = worker(api(request -> {
            sent.add(request);
            if (sent.size() == 1) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(7)));
            }
        }));

        assertThat(worker.poll()).isTrue();
        Mockito.verify(store).recordFailure(first, 1, SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(7)));
        assertThat(worker.poll()).isTrue();
        assertThat(sent).extracting(SlackUpdateRequest::text)
                .containsExactly("model waiting at original observation", "delivering at new observation");
        Mockito.verify(store).markSent(recovered, 2);
        Mockito.verify(store, Mockito.never()).markSent(first, 1);
    }

    @Test
    void rejected_old_ack_leaves_recovery_to_lease_expiry_and_does_not_send_again_outside_workspace_gate() {
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(first)).thenReturn(Optional.empty()).thenReturn(Optional.of(recovered));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(update(1, "old running", false)));
        Mockito.when(store.refresh(recovered)).thenReturn(Optional.of(update(2, "completed", true)));
        Mockito.when(store.markSent(first, 1)).thenReturn(false);
        SlackProgressWorker worker = worker(api(sent::add));

        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isFalse();
        assertThat(sent).hasSize(1);
        assertThat(worker.poll()).isTrue();
        assertThat(sent).extracting(SlackUpdateRequest::text).containsExactly("old running", "completed");
        Mockito.verify(store, Mockito.never()).recordFailure(Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any());
        Mockito.verify(store).markSent(recovered, 2);
    }

    @Test
    void permanent_and_ambiguous_failures_are_fenced_progress_failures_not_answer_posts() {
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(first)).thenReturn(Optional.of(recovered));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(update(1, "queued", false)));
        Mockito.when(store.refresh(recovered)).thenReturn(Optional.of(update(2, "queued", false)));
        SlackProgressWorker worker = worker(api(request -> {
            sent.add(request);
            if (sent.size() == 1) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
            }
            throw new IllegalStateException("untrusted provider details");
        }));
        worker.poll();
        worker.poll();
        Mockito.verify(store).recordFailure(first, 1, SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        Mockito.verify(store).recordFailure(recovered, 2, SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        Mockito.verify(store, Mockito.never()).markSent(Mockito.any(), Mockito.anyLong());
    }

    @Test
    void long_model_refresh_and_second_terminal_pass_remain_ordinary_store_scheduled_attempts() {
        SlackProgressClaim third = new SlackProgressClaim(first.messageJobId(), "progress-worker", 3);
        SlackProgressClaim fourth = new SlackProgressClaim(first.messageJobId(), "progress-worker", 4);
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(first)).thenReturn(Optional.of(recovered)).thenReturn(Optional.of(third))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(fourth));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(update(1, "model waiting observation one", false)));
        Mockito.when(store.refresh(recovered)).thenReturn(Optional.of(update(2, "model waiting observation two", false)));
        Mockito.when(store.refresh(third)).thenReturn(Optional.of(update(3, "completed", true)));
        Mockito.when(store.refresh(fourth)).thenReturn(Optional.of(update(3, "completed", true)));
        SlackProgressWorker worker = worker(api(sent::add));
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isFalse();
        assertThat(worker.poll()).isTrue();
        assertThat(sent).extracting(SlackUpdateRequest::text).containsExactly(
                "model waiting observation one", "model waiting observation two", "completed", "completed");
        Mockito.verify(store).markSent(third, 3);
        Mockito.verify(store).markSent(fourth, 3);
    }

    @Test
    void expired_claim_cannot_send_even_when_claim_selection_previously_succeeded() {
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30))).thenReturn(Optional.of(first));
        Mockito.when(store.refresh(first)).thenReturn(Optional.empty());
        assertThat(worker(api(sent::add)).poll()).isTrue();
        assertThat(sent).isEmpty();
        Mockito.verify(store, Mockito.never()).markSent(Mockito.any(), Mockito.anyLong());
    }

    @Test
    void missing_link_uses_one_outbound_call_then_next_claim_reobserves_before_update() {
        Mockito.when(store.claimNext("progress-worker", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(first)).thenReturn(Optional.of(recovered));
        Mockito.when(store.refresh(first)).thenReturn(Optional.of(new SlackProgressUpdate(1,
                new SlackUpdateRequest("C1", "confirmed-notice", "queued"), false, Instant.now(), "2.000002", Optional.empty())));
        Mockito.when(store.refresh(recovered)).thenReturn(Optional.of(update(2, "completed", true)));
        SlackWebApi api = Mockito.mock(SlackWebApi.class);
        String link = "https://example.slack.com/archives/C1/p2000002?thread_ts=1.000001&cid=C1";
        Mockito.when(api.getPermalink("C1", "2.000002")).thenReturn(link);
        SlackProgressWorker worker = worker(api);
        worker.poll();
        Mockito.verify(api, Mockito.never()).update(Mockito.any());
        Mockito.verify(store).saveQuestionPermalink(first, link);
        Mockito.verify(store, Mockito.never()).markSent(first, 1);
        worker.poll();
        Mockito.verify(api).update(new SlackUpdateRequest("C1", "confirmed-notice", "completed"));
        Mockito.verify(api, Mockito.times(1)).getPermalink(Mockito.anyString(), Mockito.anyString());
    }

    private SlackProgressWorker worker(SlackWebApi api) {
        return new SlackProgressWorker(store, api, new SlackDeliveryProperties(), "progress-worker");
    }

    private static SlackProgressUpdate update(long revision, String text, boolean terminal) {
        return new SlackProgressUpdate(revision, new SlackUpdateRequest("C1", "confirmed-notice", text), terminal,
                Instant.parse("2026-10-24T00:00:00Z"), "1.000001", Optional.of("https://example.slack.com/archives/C1/p1000001"));
    }

    private static SlackWebApi api(java.util.function.Consumer<SlackUpdateRequest> update) {
        return new TextSlackWebApi() {
            @Override
            public String post(SlackPostRequest request) { throw new AssertionError("Progress must never post"); }
            @Override
            public void update(SlackUpdateRequest request) { update.accept(request); }
            @Override
            public String getPermalink(String channelId, String messageTs) {
                throw new AssertionError("Cached question must not look up its permalink again");
            }
        };
    }
}
