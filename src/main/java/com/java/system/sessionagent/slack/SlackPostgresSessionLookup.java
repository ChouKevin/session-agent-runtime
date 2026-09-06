package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SlackPostgresSessionLookup implements ExternalSessionReferenceQueryPort {

    private final JdbcTemplate jdbcTemplate;
    private final SlackPermalinkParser permalinkParser;

    public SlackPostgresSessionLookup(DataSource dataSource, SlackPermalinkParser permalinkParser) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        this.permalinkParser = Objects.requireNonNull(permalinkParser, "Slack permalink parser must not be null");
    }

    @Override
    public Optional<SessionId> findSessionId(String reference) {
        String requiredReference = Objects.requireNonNull(reference, "External session reference must not be null");
        SlackPermalink permalink = permalinkParser.parse(requiredReference);
        List<SessionId> sessions = jdbcTemplate.query("""
                select session_id from slack_thread_binding
                where channel_id = ? and root_thread_ts = ?
                """, (resultSet, rowNumber) -> new SessionId(resultSet.getObject("session_id", UUID.class).toString()),
                permalink.channelId(), permalink.rootThreadTs());
        Assert.isTrue(sessions.size() <= 1, "Slack permalink identity must resolve within one workspace");
        return sessions.stream().findFirst();
    }

    @Override
    public Optional<ExternalSessionBindingView> findBinding(SessionId sessionId) {
        SessionId requiredSessionId = Objects.requireNonNull(sessionId, "Session ID must not be null");
        List<ExternalSessionBindingView> bindings = jdbcTemplate.query("""
                select team_id, channel_id, root_thread_ts, session_id, created_at
                from slack_thread_binding where session_id = ?
                """, (resultSet, rowNumber) -> new ExternalSessionBindingView("SLACK", resultSet.getString("team_id"),
                resultSet.getString("channel_id"), resultSet.getString("root_thread_ts"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant()), UUID.fromString(requiredSessionId.value()));
        Assert.isTrue(bindings.size() <= 1, "Runtime session must retain one Slack thread binding");
        return bindings.stream().findFirst();
    }
}
