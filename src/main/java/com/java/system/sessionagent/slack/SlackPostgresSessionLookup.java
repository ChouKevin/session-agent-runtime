package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SlackPostgresSessionLookup implements ExternalSessionReferenceQueryPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackPostgresSessionLookup.class);
    private final SlackDatabaseMapper mapper;
    private final SlackPermalinkParser permalinkParser;

    public SlackPostgresSessionLookup(DataSource dataSource, SlackPermalinkParser permalinkParser) {
        this(SlackMybatisMapperFactory.create(dataSource), permalinkParser);
    }

    public SlackPostgresSessionLookup(SlackDatabaseMapper mapper, SlackPermalinkParser permalinkParser) {
        this.mapper = Objects.requireNonNull(mapper, "Slack database mapper must not be null");
        this.permalinkParser = Objects.requireNonNull(permalinkParser, "Slack permalink parser must not be null");
    }

    @Override
    public Optional<SessionId> findSessionId(String reference) {
        String requiredReference = Objects.requireNonNull(reference, "External session reference must not be null");
        SlackPermalink permalink = permalinkParser.parse(requiredReference);
        List<SessionId> sessions = mapper.findSessionByBinding(permalink.channelId(), permalink.rootThreadTs()).stream()
                .map(value -> new SessionId(value.toString())).toList();
        Assert.isTrue(sessions.size() <= 1, "Slack permalink identity must resolve within one workspace");
        Optional<SessionId> sessionId = sessions.stream().findFirst();
        LoggingEventBuilder builder = LOGGER.atInfo().addKeyValue("event", "slack_session_resolution")
                .addKeyValue("slackChannelId", permalink.channelId())
                .addKeyValue("slackThreadTs", permalink.rootThreadTs())
                .addKeyValue("outcome", sessionId.isPresent() ? "RESOLVED" : "NOT_FOUND");
        sessionId.ifPresent(value -> builder.addKeyValue("sessionId", value.value()));
        builder.log("runtime_lifecycle");
        return sessionId;
    }

    @Override
    public Optional<ExternalSessionBindingView> findBinding(SessionId sessionId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        List<ExternalSessionBindingView> bindings = mapper.findBinding(UUID.fromString(requiredSessionId.value())).stream()
                .map(row -> new ExternalSessionBindingView("SLACK", row.teamId(), row.channelId(), row.rootThreadTs(),
                        row.createdAt().toInstant())).toList();
        Assert.isTrue(bindings.size() <= 1, "Runtime session must retain one Slack thread binding");
        return bindings.stream().findFirst();
    }
}
