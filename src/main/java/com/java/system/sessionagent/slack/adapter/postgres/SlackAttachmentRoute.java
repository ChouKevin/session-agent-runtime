package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.artifact.port.AttachmentRoute;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configuration and persisted intake ownership determine attachment eligibility. */
public final class SlackAttachmentRoute implements AttachmentRoute {
    private final JdbcTemplate jdbc;
    private final SlackProperties properties;

    public SlackAttachmentRoute(DataSource dataSource, SlackProperties properties) {
        jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public boolean eligible(UUID sessionId, UUID messageJobId) {
        return properties.enabled() && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from slack_message_receipt receipt
                    join slack_thread_binding binding on binding.session_id=receipt.session_id
                        and binding.team_id=receipt.team_id and binding.channel_id=receipt.channel_id
                    where receipt.session_id=? and receipt.message_job_id=? and receipt.classification='ACCEPTED')
                """, Boolean.class, sessionId, messageJobId));
    }
}
