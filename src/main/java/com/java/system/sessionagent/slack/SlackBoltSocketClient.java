package com.java.system.sessionagent.slack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.handler.builtin.DefaultUnmatchedRequestHandler;
import com.slack.api.bolt.request.Request;
import com.slack.api.bolt.request.builtin.EventRequest;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.bolt.socket_mode.request.SocketModeRequestParser;
import com.slack.api.bolt.socket_mode.request.SocketModeRequest;
import com.slack.api.socket_mode.response.AckResponse;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageChannelJoinEvent;
import com.slack.api.model.event.MessageChannelLeaveEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.model.event.MessageMeEvent;
import com.slack.api.model.event.MessageRepliedEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SlackBoltSocketClient implements SlackSocketClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SlackProperties properties;
    private final SlackEventAdapter eventAdapter;
    private final SlackSocketRuntimeFactory runtimeFactory;
    private Optional<OwnedRuntime> activeRuntime = Optional.empty();

    public SlackBoltSocketClient(SlackProperties properties, SlackEventAdapter eventAdapter) {
        Assert.notNull(properties, "Slack properties must not be null");
        Assert.notNull(eventAdapter, "Slack event adapter must not be null");
        this.properties = properties;
        this.eventAdapter = eventAdapter;
        this.runtimeFactory = this::createRuntime;
    }

    SlackBoltSocketClient(
            SlackProperties properties,
            SlackEventAdapter eventAdapter,
            SlackSocketRuntimeFactory runtimeFactory) {
        Assert.notNull(properties, "Slack properties must not be null");
        Assert.notNull(eventAdapter, "Slack event adapter must not be null");
        Assert.notNull(runtimeFactory, "Slack socket runtime factory must not be null");
        this.properties = properties;
        this.eventAdapter = eventAdapter;
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public synchronized void start(SlackSocketConnectionListener listener) throws Exception {
        Assert.notNull(listener, "Slack socket connection listener must not be null");
        Assert.isTrue(activeRuntime.isEmpty(), "Slack socket client is already started");
        GuardedConnectionListener guardedListener = new GuardedConnectionListener(listener);
        SlackSocketRuntime runtime = runtimeFactory.create(guardedListener);
        OwnedRuntime ownedRuntime = new OwnedRuntime(runtime, guardedListener);
        activeRuntime = Optional.of(ownedRuntime);
        guardedListener.activate();
        try {
            runtime.startAsync();
        } catch (Exception exception) {
            activeRuntime = Optional.empty();
            guardedListener.retire();
            try {
                runtime.stop();
            } catch (Exception closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    App buildApp() {
        App app = new App(AppConfig.builder()
                .singleTeamBotToken(properties.botToken())
                .requestVerificationEnabled(false)
                .ignoringSelfEventsEnabled(false)
                .unmatchedRequestHandler(this::handleUnmatchedRequest)
                .build());
        registerHandlers(app);
        return app;
    }

    void registerHandlers(App app) {
        Assert.notNull(app, "Slack Bolt app must not be null");
        app.event(AppMentionEvent.class, (request, context) -> {
            AppMentionEvent event = request.getEvent();
            eventAdapter.handle(new SlackRootEvent(
                    request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(),
                    event.getBotId(), "", event.getText(), Objects.nonNull(event.getEdited()) ? "message_changed" : event.getSubtype(),
                    false, hasAttachments(event.getAttachments(), event.getFiles())));
            return context.ack();
        });
        app.event(MessageEvent.class, (request, context) -> {
            MessageEvent event = request.getEvent();
            eventAdapter.handle(new SlackRootEvent(
                    request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(),
                    event.getBotId(), event.getChannelType(), event.getText(), Objects.nonNull(event.getEdited()) ? "message_changed" : "",
                    false, hasAttachments(event.getAttachments(), event.getFiles())));
            return context.ack();
        });
        app.event(MessageFileShareEvent.class, (request, context) -> {
            MessageFileShareEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(),
                    "", event.getChannelType(), event.getText(), event.getSubtype(), false,
                    hasAttachments(event.getAttachments(), event.getFiles()));
            return context.ack();
        });
        app.event(MessageChangedEvent.class, (request, context) -> {
            MessageChangedEvent event = request.getEvent();
            MessageChangedEvent.Message message = Objects.requireNonNull(event.getMessage(), "Changed Slack message must be present");
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), message.getTs(), message.getThreadTs(), message.getUser(),
                    message.getBotId(), event.getChannelType(), "", event.getSubtype(), event.isHidden(), false);
            return context.ack();
        });
        app.event(MessageDeletedEvent.class, (request, context) -> {
            MessageDeletedEvent event = request.getEvent();
            Optional<MessageDeletedEvent.Message> previousMessage = Optional.ofNullable(event.getPreviousMessage());
            String messageTs = firstText(previousMessage.map(MessageDeletedEvent.Message::getTs).orElse(""),
                    firstText(event.getDeletedTs(), event.getTs()));
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), messageTs,
                    previousMessage.map(MessageDeletedEvent.Message::getThreadTs).orElse(""),
                    previousMessage.map(MessageDeletedEvent.Message::getUser).orElse(""),
                    previousMessage.map(MessageDeletedEvent.Message::getBotId).orElse(""), event.getChannelType(), "", event.getSubtype(),
                    event.isHidden(), false);
            return context.ack();
        });
        app.event(MessageThreadBroadcastEvent.class, (request, context) -> {
            MessageThreadBroadcastEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(), "",
                    event.getChannelType(), event.getText(), event.getSubtype(), false, hasAttachments(event.getAttachments(), java.util.List.of()));
            return context.ack();
        });
        app.event(MessageMeEvent.class, (request, context) -> {
            MessageMeEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), "", event.getUser(), event.getBotId(),
                    event.getChannelType(), event.getText(), event.getSubtype(), false, false);
            return context.ack();
        });
        app.event(MessageRepliedEvent.class, (request, context) -> {
            MessageRepliedEvent event = request.getEvent();
            MessageRepliedEvent.Message message = Objects.requireNonNull(event.getMessage(), "Reply summary Slack message must be present");
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), message.getTs(), "", message.getUser(), "", "",
                    "", event.getSubtype(), event.isHidden(), false);
            return context.ack();
        });
        app.event(MessageBotEvent.class, (request, context) -> {
            MessageBotEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), "", event.getBotId(),
                    event.getChannelType(), "", event.getSubtype(), false, false);
            return context.ack();
        });
        app.event(MessageChannelJoinEvent.class, (request, context) -> {
            MessageChannelJoinEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), "", event.getUser(), "", event.getChannelType(),
                    "", event.getSubtype(), false, false);
            return context.ack();
        });
        app.event(MessageChannelLeaveEvent.class, (request, context) -> {
            MessageChannelLeaveEvent event = request.getEvent();
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), event.getTs(), "", event.getUser(), "", event.getChannelType(),
                    "", event.getSubtype(), false, false);
            return context.ack();
        });
    }

    @Override
    public void stop() throws Exception {
        Optional<OwnedRuntime> retiredRuntime;
        synchronized (this) {
            retiredRuntime = activeRuntime;
            activeRuntime = Optional.empty();
        }
        retiredRuntime.ifPresent(OwnedRuntime::retire);
        if (retiredRuntime.isPresent()) {
            retiredRuntime.get().runtime().stop();
        }
    }

    private SlackSocketRuntime createRuntime(SlackSocketConnectionListener listener) throws Exception {
        App app = buildApp();
        SocketModeClient client = com.slack.api.Slack.getInstance().socketMode(properties.appToken(), SocketModeClient.Backend.JavaWebSocket);
        try {
            return new SlackSdkSocketRuntime(client, new SocketModeApp(client, app), app, listener);
        } catch (Exception exception) {
            try {
                client.close();
            } catch (Exception closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private record OwnedRuntime(SlackSocketRuntime runtime, GuardedConnectionListener listener) {

        private void retire() {
            listener.retire();
        }
    }

    private static final class GuardedConnectionListener implements SlackSocketConnectionListener {

        private final SlackSocketConnectionListener delegate;
        private final AtomicBoolean active = new AtomicBoolean(false);

        private GuardedConnectionListener(SlackSocketConnectionListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void connected() {
            if (active.get()) {
                delegate.connected();
            }
        }

        @Override
        public void disconnected() {
            if (active.get()) {
                delegate.disconnected();
            }
        }

        private void activate() {
            active.set(true);
        }

        private void retire() {
            active.set(false);
        }
    }

    static final class SlackSdkSocketRuntime implements SlackSocketRuntime {

        private final SocketModeClient client;
        private final SocketModeApp socketModeApp;

        SlackSdkSocketRuntime(
                SocketModeClient client,
                SocketModeApp socketModeApp,
                App app,
                SlackSocketConnectionListener listener) {
            this.client = client;
            this.socketModeApp = socketModeApp;
            registerConnectionListeners(client, app, listener);
        }

        @Override
        public void startAsync() throws Exception {
            socketModeApp.startAsync();
        }

        @Override
        public void stop() throws Exception {
            Exception firstFailure = null; // cs-allow An exception is absent until an owned close operation fails.
            try {
                client.setAutoReconnectEnabled(false);
            } catch (Exception exception) {
                firstFailure = exception;
            }
            try {
                client.close();
            } catch (Exception exception) {
                firstFailure = retainFirstFailure(firstFailure, exception);
            }
            try {
                socketModeApp.close();
            } catch (Exception exception) {
                firstFailure = retainFirstFailure(firstFailure, exception);
            }
            if (firstFailure != null) { // cs-allow An exception is thrown only when one of the independent close operations failed.
                throw firstFailure;
            }
        }

        private static Exception retainFirstFailure(Exception firstFailure, Exception laterFailure) {
            if (firstFailure == null) { // cs-allow The first failure is absent until a close operation fails.
                return laterFailure;
            }
            firstFailure.addSuppressed(laterFailure);
            return firstFailure;
        }

        private static void registerConnectionListeners(SocketModeClient client, App app, SlackSocketConnectionListener listener) {
            SocketModeRequestParser requestParser = new SocketModeRequestParser(app.config());
            client.addWebSocketMessageListener(message -> {
                notifyConnectedForHello(message, listener);
                runBoltApp(message, app, client, requestParser);
            });
            client.addWebSocketCloseListener((code, reason) -> listener.disconnected());
            client.addWebSocketErrorListener(reason -> listener.disconnected());
        }

        private static void notifyConnectedForHello(String message, SlackSocketConnectionListener listener) {
            try {
                JsonNode payload = OBJECT_MAPPER.readTree(message);
                if ("hello".equals(payload.path("type").asText())) {
                    listener.connected();
                }
            } catch (JsonProcessingException ignored) {
                // The Slack SDK will continue processing non-JSON transport frames.
            }
        }

        private static void runBoltApp(
                String message,
                App app,
                SocketModeClient client,
                SocketModeRequestParser requestParser) {
            SocketModeRequest request = requestParser.parse(message);
            if (request == null) { // cs-allow The parser contract represents unsupported frames with null.
                return;
            }
            try {
                Response response = app.run(request.getBoltRequest());
                if (response.getStatusCode() != 200) {
                    return;
                }
                if (response.getBody() == null) { // cs-allow The Bolt response body is optional by SDK contract.
                    client.sendSocketModeResponse(new AckResponse(request.getEnvelope().getEnvelopeId()));
                    return;
                }
                ObjectNode responsePayload = OBJECT_MAPPER.createObjectNode();
                responsePayload.put("envelope_id", request.getEnvelope().getEnvelopeId());
                if (response.getContentType().startsWith("application/json")) {
                    responsePayload.set("payload", OBJECT_MAPPER.readTree(response.getBody()));
                } else {
                    responsePayload.putObject("payload").put("text", response.getBody());
                }
                client.sendSocketModeResponse(OBJECT_MAPPER.writeValueAsString(responsePayload));
            } catch (Exception ignored) {
                // Bolt's existing error handling remains content-free at the Runtime boundary.
            }
        }
    }

    private static boolean hasAttachments(java.util.List<?> attachments, java.util.List<?> files) {
        return !CollectionUtils.isEmpty(attachments) || !CollectionUtils.isEmpty(files);
    }

    private Response handleUnmatchedRequest(Request<?> request) {
        if (request instanceof EventRequest eventRequest && isUnregisteredMessageSubtype(eventRequest)) {
            normalizeUnregisteredMessageSubtype(eventRequest).ifPresent(eventAdapter::handle);
            return Response.ok();
        }
        return new DefaultUnmatchedRequestHandler().handle(request);
    }

    private static boolean isUnregisteredMessageSubtype(EventRequest request) {
        String eventTypeAndSubtype = request.getEventTypeAndSubtype();
        return StringUtils.hasText(eventTypeAndSubtype) && eventTypeAndSubtype.startsWith("message:");
    }

    private Optional<SlackRootEvent> normalizeUnregisteredMessageSubtype(EventRequest request) {
        try {
            JsonNode envelope = OBJECT_MAPPER.readTree(request.getRequestBodyAsString());
            JsonNode event = envelope.path("event");
            String eventId = text(envelope, "event_id");
            String teamId = request.getContext().getTeamId();
            String channelId = text(event, "channel");
            String messageTs = text(event, "ts");
            if (!StringUtils.hasText(eventId) || !StringUtils.hasText(teamId) || !StringUtils.hasText(channelId)
                    || !StringUtils.hasText(messageTs)) {
                return Optional.empty();
            }
            return Optional.of(new SlackRootEvent(eventId, teamId, channelId, messageTs, text(event, "thread_ts"), text(event, "user"),
                    text(event, "bot_id"), text(event, "channel_type"), text(event, "text"), text(event, "subtype"),
                    event.path("hidden").asBoolean(false), hasAttachments(event.path("attachments"), event.path("files"))));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText("");
    }

    private static boolean hasAttachments(JsonNode attachments, JsonNode files) {
        return (attachments.isArray() && !attachments.isEmpty()) || (files.isArray() && !files.isEmpty());
    }

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private void handle(
            String eventId,
            String teamId,
            String channelId,
            String messageTs,
            String threadTs,
            String participantId,
            String botId,
            String channelType,
            String text,
            String subtype,
            boolean hidden,
            boolean hasAttachments) {
        eventAdapter.handle(new SlackRootEvent(eventId, teamId, channelId, messageTs, threadTs, participantId, botId, channelType,
                text, subtype, hidden, hasAttachments));
    }

}
