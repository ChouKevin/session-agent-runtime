package com.java.system.sessionagent.slack;

import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.socket_mode.SocketModeClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackBoltSocketClientTest {

    @Test
    void retires_a_partial_start_and_closes_the_created_runtime() {
        FailingStartRuntime runtime = new FailingStartRuntime();
        SlackBoltSocketClient client = new SlackBoltSocketClient(properties(), adapter(), listener -> runtime);

        assertThatThrownBy(() -> client.start(new RecordingListener())).isInstanceOf(IllegalStateException.class);

        assertThat(runtime.stopCalls).hasValue(1);
    }

    @Test
    void closes_the_underlying_socket_and_bolt_app_even_when_the_socket_close_fails() throws Exception {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        Mockito.doThrow(new IllegalStateException("socket close failed")).when(socketModeClient).close();
        SlackBoltSocketClient.SlackSdkSocketRuntime runtime = new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, new SlackBoltSocketClient(properties(), adapter()).buildApp(), new RecordingListener());

        assertThatThrownBy(runtime::stop).isInstanceOf(IllegalStateException.class);

        Mockito.verify(socketModeClient).setAutoReconnectEnabled(false);
        Mockito.verify(socketModeClient).close();
        Mockito.verify(socketModeApp).close();
    }

    @Test
    void still_closes_both_resources_when_disabling_reconnect_fails() throws Exception {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        Mockito.doThrow(new IllegalStateException("reconnect disable failed"))
                .when(socketModeClient).setAutoReconnectEnabled(false);
        SlackBoltSocketClient.SlackSdkSocketRuntime runtime = new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, new SlackBoltSocketClient(properties(), adapter()).buildApp(), new RecordingListener());

        assertThatThrownBy(runtime::stop).isInstanceOf(IllegalStateException.class);

        Mockito.verify(socketModeClient).close();
        Mockito.verify(socketModeApp).close();
    }

    @Test
    void ignores_connection_signals_from_a_stopped_runtime() throws Exception {
        SignallingRuntime runtime = new SignallingRuntime();
        RecordingListener listener = new RecordingListener();
        SlackBoltSocketClient client = new SlackBoltSocketClient(properties(), adapter(), connectionListener -> {
            runtime.listener = connectionListener;
            return runtime;
        });

        client.start(listener);
        runtime.listener.connected();
        client.stop();
        runtime.listener.connected();
        runtime.listener.disconnected();

        assertThat(listener.connectedCalls).hasValue(1);
        assertThat(listener.disconnectedCalls).hasValue(0);
        assertThat(runtime.stopCalls).hasValue(1);
    }

    @Test
    void reports_socket_mode_hello_as_positive_connection_evidence() {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        RecordingListener listener = new RecordingListener();
        new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, new SlackBoltSocketClient(properties(), adapter()).buildApp(), listener);
        ArgumentCaptor<com.slack.api.socket_mode.listener.WebSocketMessageListener> messageListener = ArgumentCaptor.forClass(
                com.slack.api.socket_mode.listener.WebSocketMessageListener.class);
        Mockito.verify(socketModeClient).addWebSocketMessageListener(messageListener.capture());

        messageListener.getValue().handle("{\"type\":\"hello\"}");

        assertThat(listener.connectedCalls).hasValue(1);
    }

    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static SlackEventAdapter adapter() {
        return new SlackEventAdapter("UBOT", ignored -> SlackIntakeResult.newIgnored());
    }

    private static final class RecordingListener implements SlackSocketConnectionListener {

        private final AtomicInteger connectedCalls = new AtomicInteger();
        private final AtomicInteger disconnectedCalls = new AtomicInteger();

        @Override
        public void connected() {
            connectedCalls.incrementAndGet();
        }

        @Override
        public void disconnected() {
            disconnectedCalls.incrementAndGet();
        }
    }

    private static final class FailingStartRuntime implements SlackSocketRuntime {

        private final AtomicInteger stopCalls = new AtomicInteger();

        @Override
        public void startAsync() {
            throw new IllegalStateException("start failed");
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
        }
    }

    private static final class SignallingRuntime implements SlackSocketRuntime {

        private final AtomicInteger stopCalls = new AtomicInteger();
        private SlackSocketConnectionListener listener;

        @Override
        public void startAsync() {
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
        }
    }
}
