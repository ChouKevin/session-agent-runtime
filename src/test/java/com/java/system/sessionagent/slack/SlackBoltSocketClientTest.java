package com.java.system.sessionagent.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.socket_mode.listener.WebSocketCloseListener;
import com.slack.api.socket_mode.listener.WebSocketErrorListener;
import com.slack.api.socket_mode.listener.WebSocketMessageListener;
import com.slack.api.socket_mode.response.AckResponse;
import com.slack.api.socket_mode.response.SocketModeResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void keeps_available_when_a_retired_socket_closes_after_the_replacement_hello() {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        RecordingListener listener = new RecordingListener();
        new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, new SlackBoltSocketClient(properties(), adapter()).buildApp(), listener);
        ArgumentCaptor<WebSocketMessageListener> messageListener = ArgumentCaptor.forClass(WebSocketMessageListener.class);
        ArgumentCaptor<WebSocketCloseListener> closeListener = ArgumentCaptor.forClass(WebSocketCloseListener.class);
        Mockito.verify(socketModeClient).addWebSocketMessageListener(messageListener.capture());
        Mockito.verify(socketModeClient).addWebSocketCloseListener(closeListener.capture());
        Mockito.when(socketModeClient.verifyConnection()).thenReturn(true);

        messageListener.getValue().handle("{\"type\":\"hello\"}");
        closeListener.getValue().handle(1000, "retired socket closed");

        assertThat(listener.connectedCalls).hasValue(1);
        assertThat(listener.disconnectedCalls).hasValue(0);
    }

    @Test
    void reports_degraded_connection_evidence_when_a_socket_error_leaves_no_current_connection() {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        RecordingListener listener = new RecordingListener();
        new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, new SlackBoltSocketClient(properties(), adapter()).buildApp(), listener);
        ArgumentCaptor<WebSocketErrorListener> errorListener = ArgumentCaptor.forClass(WebSocketErrorListener.class);
        Mockito.verify(socketModeClient).addWebSocketErrorListener(errorListener.capture());
        Mockito.when(socketModeClient.verifyConnection()).thenReturn(false);

        errorListener.getValue().handle(new IllegalStateException("socket failed"));

        assertThat(listener.disconnectedCalls).hasValue(1);
    }

    @Test
    void dispatches_a_socket_mode_event_envelope_and_acknowledges_its_envelope_id() {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        AtomicReference<SlackRootIntake> intake = new AtomicReference<>();
        SlackEventAdapter eventAdapter = new SlackEventAdapter("UBOT", received -> {
            intake.set(received);
            return SlackIntakeResult.newIgnored();
        });
        SlackBoltSocketClient client = new SlackBoltSocketClient(properties(), eventAdapter);
        new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, unauthenticatedApp(client), new RecordingListener());
        ArgumentCaptor<WebSocketMessageListener> messageListener = ArgumentCaptor.forClass(WebSocketMessageListener.class);
        Mockito.verify(socketModeClient).addWebSocketMessageListener(messageListener.capture());

        messageListener.getValue().handle("""
                {"envelope_id":"envelope-1","type":"events_api","accepts_response_payload":false,
                "payload":{"token":"verification-token","team_id":"T1","api_app_id":"A1","type":"event_callback",
                "event_id":"Ev1","event_time":1,"event":{"type":"app_mention","user":"U1","text":"<@UBOT> hello",
                "channel":"C1","ts":"1.000001"}}}
                """);

        assertThat(intake.get()).isNotNull();
        assertThat(intake.get().classification()).isEqualTo(SlackIntakeClassification.ACCEPTED);
        assertThat(intake.get().message()).hasValueSatisfying(message -> assertThat(message.message()).isEqualTo("hello"));
        ArgumentCaptor<SocketModeResponse> acknowledgement = ArgumentCaptor.forClass(SocketModeResponse.class);
        Mockito.verify(socketModeClient).sendSocketModeResponse(acknowledgement.capture());
        assertThat(acknowledgement.getValue()).isInstanceOf(AckResponse.class);
        assertThat(((AckResponse) acknowledgement.getValue()).getEnvelopeId()).isEqualTo("envelope-1");
    }

    @Test
    void rejects_socket_event_intake_and_acknowledgement_after_runtime_retirement() throws Exception {
        SocketModeClient socketModeClient = Mockito.mock(SocketModeClient.class);
        SocketModeApp socketModeApp = Mockito.mock(SocketModeApp.class);
        AtomicReference<SlackRootIntake> intake = new AtomicReference<>();
        SlackEventAdapter eventAdapter = new SlackEventAdapter("UBOT", received -> {
            intake.set(received);
            return SlackIntakeResult.newIgnored();
        });
        SlackBoltSocketClient client = new SlackBoltSocketClient(properties(), eventAdapter);
        SlackBoltSocketClient.SlackSdkSocketRuntime runtime = new SlackBoltSocketClient.SlackSdkSocketRuntime(
                socketModeClient, socketModeApp, unauthenticatedApp(client), new RecordingListener());
        ArgumentCaptor<WebSocketMessageListener> messageListener = ArgumentCaptor.forClass(WebSocketMessageListener.class);
        Mockito.verify(socketModeClient).addWebSocketMessageListener(messageListener.capture());

        runtime.stop();
        messageListener.getValue().handle("""
                {"envelope_id":"envelope-1","type":"events_api","accepts_response_payload":false,
                "payload":{"token":"verification-token","team_id":"T1","api_app_id":"A1","type":"event_callback",
                "event_id":"Ev1","event_time":1,"event":{"type":"app_mention","user":"U1","text":"<@UBOT> hello",
                "channel":"C1","ts":"1.000001"}}}
                """);

        assertThat(intake.get()).isNull();
        Mockito.verify(socketModeClient, Mockito.never()).sendSocketModeResponse(Mockito.any(SocketModeResponse.class));
    }

    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static SlackEventAdapter adapter() {
        return new SlackEventAdapter("UBOT", ignored -> SlackIntakeResult.newIgnored());
    }

    private static App unauthenticatedApp(SlackBoltSocketClient client) {
        App app = new App(client.buildApp().config().toBuilder().singleTeamBotToken(null).build()); // cs-allow Bolt uses null to disable fake-backed test authorization.
        client.registerHandlers(app);
        return app;
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
