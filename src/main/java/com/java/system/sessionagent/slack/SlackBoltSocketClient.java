package com.java.system.sessionagent.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import org.springframework.util.Assert;

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
        App app = new App(AppConfig.builder()
                .singleTeamBotToken(properties.botToken())
                .requestVerificationEnabled(false)
                .ignoringSelfEventsEnabled(false)
                .build());
        app.event(AppMentionEvent.class, (request, context) -> {
            AppMentionEvent event = request.getEvent();
            eventAdapter.handle(new SlackRootEvent(
                    context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(),
                    event.getBotId(), "", event.getText(), event.getSubtype()));
            return context.ack();
        });
        app.event(MessageEvent.class, (request, context) -> {
            MessageEvent event = request.getEvent();
            eventAdapter.handle(new SlackRootEvent(
                    context.getTeamId(), event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(),
                    event.getBotId(), event.getChannelType(), event.getText(), ""));
            return context.ack();
        });
        SocketModeApp socket = new SocketModeApp(properties.appToken(), app);
        socket.startAsync();
        this.socketModeApp = socket;
    }

    @Override
    public void stop() throws Exception {
        if (socketModeApp != null) { // cs-allow Socket client is absent before the first completed start.
            socketModeApp.close();
        }
    }

}
