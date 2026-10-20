package com.java.system.sessionagent.storage.adapter.postgres.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Global admission policy shared by PostgreSQL intake adapters.
 *
 * <p>Callers lock before inspecting duplicate identities, then check capacity only
 * for new jobs. Both operations and the resulting job or rejection receipt writes
 * must use the same data source and transaction; the lock lasts until completion.</p>
 */
public final class PostgresIntakeCapacity {

    private final JdbcTemplate jdbcTemplate;
    private final int maximumUnfinishedJobs;

    public PostgresIntakeCapacity(DataSource dataSource) {
        this(dataSource, 100);
    }

    public PostgresIntakeCapacity(DataSource dataSource, int maximumUnfinishedJobs) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "Data source must not be null"));
        Assert.isTrue(maximumUnfinishedJobs > 0, "Maximum unfinished jobs must be positive");
        this.maximumUnfinishedJobs = maximumUnfinishedJobs;
    }

    public void lock() {
        jdbcTemplate.execute("select pg_advisory_xact_lock(731_937_205)");
    }

    public boolean hasCapacity() {
        Long unfinishedJobs = jdbcTemplate.queryForObject(
                "select count(*) from message_job where status in ('PENDING', 'WORKING', 'RETRY')", Long.class);
        return Objects.requireNonNull(unfinishedJobs, "Unfinished job count must not be null") < maximumUnfinishedJobs;
    }
}
