package com.java.system.sessionagent.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
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

import java.util.Objects;

public final class SlackBoltSocketClient implements SlackSocketClient {

    private final SlackProperties properties;
    private final SlackEventAdapter eventAdapter;
    private SocketModeApp socketModeApp;

    public SlackBoltSocketClient(SlackProperties properties, SlackEventAdapter eventAdapter) {
        Assert.notNull(properties, "Slack properties must not be null");
        Assert.notNull(eventAdapter, "Slack event adapter must not be null");
        this.properties = properties;
        this.eventAdapter = eventAdapter;
    }

    @Override
    public void start() throws Exception {
        App app = buildApp();
        SocketModeApp socket = new SocketModeApp(properties.appToken(), app);
        socket.startAsync();
        this.socketModeApp = socket;
    }

    App buildApp() {
        App app = new App(AppConfig.builder()
                .singleTeamBotToken(properties.botToken())
                .requestVerificationEnabled(false)
                .ignoringSelfEventsEnabled(false)
                .subtypedMessageEventsAutoAckEnabled(true)
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
            MessageDeletedEvent.Message message = Objects.requireNonNull(event.getPreviousMessage(), "Deleted Slack message must be present");
            handle(request.getEventId(), context.getTeamId(), event.getChannel(), message.getTs(), message.getThreadTs(), message.getUser(),
                    message.getBotId(), event.getChannelType(), "", event.getSubtype(), event.isHidden(), false);
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
        if (socketModeApp != null) { // cs-allow Socket client is absent before the first completed start.
            socketModeApp.close();
        }
    }

    private static boolean hasAttachments(java.util.List<?> attachments, java.util.List<?> files) {
        return !CollectionUtils.isEmpty(attachments) || !CollectionUtils.isEmpty(files);
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
