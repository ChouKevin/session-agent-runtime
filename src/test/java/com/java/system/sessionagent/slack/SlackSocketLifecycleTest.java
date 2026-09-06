package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSocketLifecycleTest {

    @Test
    void keeps_connecting_when_async_start_returns_without_connection_evidence() {
        AsyncStartingSocketClient socketClient = new AsyncStartingSocketClient();
        SlackSocketLifecycle lifecycle = new SlackSocketLifecycle(socketClient, properties());

        lifecycle.start();

        socketClient.awaitStart();
        assertThat(lifecycle.state()).isEqualTo(SlackLifecycleState.CONNECTING);
        lifecycle.stop();
    }

    @Test
    void reports_confirmed_connection_disconnect_and_sdk_reconnect_from_transport_signals() {
        SignallingSocketClient socketClient = new SignallingSocketClient();
        SlackSocketLifecycle lifecycle = new SlackSocketLifecycle(socketClient, properties());

        lifecycle.start();
        SlackSocketConnectionListener listener = socketClient.awaitListener();
        listener.connected();
        awaitState(lifecycle, SlackLifecycleState.AVAILABLE);
        listener.disconnected();
        awaitState(lifecycle, SlackLifecycleState.DEGRADED);
        listener.connected();
        awaitState(lifecycle, SlackLifecycleState.AVAILABLE);

        assertThat(lifecycle.state()).isEqualTo(SlackLifecycleState.AVAILABLE);
        lifecycle.stop();
    }

    @Test
    void keeps_stopped_when_a_retired_transport_reports_a_late_connection() {
        SignallingSocketClient socketClient = new SignallingSocketClient();
        SlackSocketLifecycle lifecycle = new SlackSocketLifecycle(socketClient, properties());

        lifecycle.start();
        SlackSocketConnectionListener listener = socketClient.awaitListener();
        lifecycle.stop();
        listener.connected();
        listener.disconnected();

        assertThat(lifecycle.state()).isEqualTo(SlackLifecycleState.STOPPED);
    }

    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    private static void awaitState(SlackSocketLifecycle lifecycle, SlackLifecycleState expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (lifecycle.state() != expected && System.nanoTime() < deadline) { // cs-allow Enum identity comparison is intentional.
            Thread.onSpinWait();
        }
    }

    private static final class AsyncStartingSocketClient implements SlackSocketClient {

        private final AtomicBoolean started = new AtomicBoolean(false);

        @Override
        public void start(SlackSocketConnectionListener listener) {
            started.set(true);
        }

        @Override
        public void stop() {
        }

        private void awaitStart() {
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (!started.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(started.get()).isTrue();
        }
    }

    private static final class SignallingSocketClient implements SlackSocketClient {

        private final AtomicReference<SlackSocketConnectionListener> listener = new AtomicReference<>();

        @Override
        public void start(SlackSocketConnectionListener listener) {
            this.listener.set(listener);
        }

        @Override
        public void stop() {
        }

        private SlackSocketConnectionListener awaitListener() {
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (listener.get() == null && System.nanoTime() < deadline) { // cs-allow The listener has no value until asynchronous start registers it.
                Thread.onSpinWait();
            }
            return listener.get();
        }
    }
}
