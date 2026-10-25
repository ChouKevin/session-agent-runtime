package com.java.system.sessionagent.storage.adapter.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.port.ArtifactReceipt;
import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolOutput;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/** Shares the conversation datasource transaction when associating actual observations. */
public final class PostgresArtifactStore implements ArtifactStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public PostgresArtifactStore(DataSource dataSource, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mapper = mapper;
    }

    @Override
    public Saved create(ToolInvocation invocation, String title, String extension, String mediaType,
                        byte[] content, String toolName, Map<String, Object> arguments, Map<String, Object> disclosures) {
        Assert.isTrue(content.length <= ArtifactStore.MAX_BYTES && title.codePointCount(0, title.length()) <= 120,
                "Artifact exceeds limits");
        Assert.isTrue(extension.equals("md") || extension.equals("csv"), "Unsupported artifact format");
        return Objects.requireNonNull(transactions.execute(transaction -> {
            ToolExecutionContext identity = invocation.context();
            requireClaim(invocation);
            List<Stored> existing = jdbc.query("""
                    select artifact_id, observation_token, title, filename, media_type, content, tool_name,
                           expected_observation::text, input_arguments::text from generated_artifact
                    where message_job_id=? and model_call_number=? and tool_ordinal=?
                    """, (row, index) -> new Stored(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                    row.getString(3), row.getString(4), row.getString(5), row.getBytes(6), row.getString(7), row.getString(8), row.getString(9)),
                    identity.messageJobId(), identity.modelCallNumber(), identity.toolOrdinal());
            UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst().id();
            UUID token = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst().token();
            String filename = id + "." + extension;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>(disclosures);
            result.put("artifactId", id.toString());
            result.put("filename", filename);
            result.put("mediaType", mediaType);
            result.put("byteLength", content.length);
            result.put("status", "SAVED_PENDING_DELIVERY");
            String expected = json(new ToolOutput(false, result).asStructuredValue());
            if (!existing.isEmpty()) {
                Stored stored = existing.getFirst();
                Assert.isTrue(stored.title().equals(title) && stored.filename().equals(filename)
                        && stored.mediaType().equals(mediaType) && stored.toolName().equals(toolName)
                        && Arrays.equals(stored.content(), content) && jsonEqual(stored.observation(), expected)
                        && jsonEqual(stored.arguments(), json(arguments)),
                        "Artifact invocation identity collision");
            } else {
                Long count = jdbc.queryForObject("select count(*) from generated_artifact where message_job_id=?", Long.class,
                        identity.messageJobId());
                Long bytes = jdbc.queryForObject("select coalesce(sum(octet_length(content)),0) from generated_artifact where message_job_id=?",
                        Long.class, identity.messageJobId());
                Assert.isTrue(Objects.requireNonNull(count) < ArtifactStore.MAX_ARTIFACTS
                        && Objects.requireNonNull(bytes) + content.length <= ArtifactStore.MAX_JOB_BYTES, "Artifact job quota exceeded");
                jdbc.update("""
                        insert into generated_artifact(artifact_id,session_id,message_job_id,model_call_number,tool_ordinal,
                            title,filename,media_type,content,tool_name,observation_token,expected_observation,input_arguments)
                        values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                        """, id, identity.sessionId(), identity.messageJobId(), identity.modelCallNumber(), identity.toolOrdinal(),
                        title, filename, mediaType, content, toolName, token, expected, json(arguments));
            }
            requireLiveLease(identity);
            return new Saved(new ArtifactReceipt(id, token, identity), Map.copyOf(result));
        }));
    }

    private void requireClaim(ToolInvocation invocation) {
        ToolExecutionAuthority claim = invocation.authority();
        List<Map<String, Object>> jobs = jdbc.queryForList("""
                select status,worker_id,claim_number,
                       cancellation_requested_at is not null as cancelled,model_calls
                from message_job where message_job_id=? and session_id=? for update
                """, invocation.context().messageJobId(), invocation.context().sessionId());
        if (jobs.isEmpty()) {
            throw new StaleWorkClaimException();
        }
        Map<String, Object> job = jobs.getFirst();
        if (!"WORKING".equals(job.get("status")) || !claim.workerId().equals(job.get("worker_id"))
                || ((Number) job.get("claim_number")).longValue() != claim.claimNumber()) {
            throw new StaleWorkClaimException();
        }
        if (Boolean.TRUE.equals(job.get("cancelled"))) {
            throw new JobCancellationRequestedException();
        }
        requireLiveLease(invocation.context());
        Assert.isTrue(invocation.context().modelCallNumber() <= ((Number) job.get("model_calls")).longValue(),
                "Tool invocation requires an already counted model call");
    }

    private void requireLiveLease(ToolExecutionContext identity) {
        // Evaluate time only after acquiring the job lock, including after lock waits.
        boolean live = Boolean.TRUE.equals(jdbc.queryForObject(
                "select locked_until > clock_timestamp() from message_job where message_job_id=? and session_id=?",
                Boolean.class, identity.messageJobId(), identity.sessionId()));
        if (!live) {
            throw new StaleWorkClaimException();
        }
    }

    /** Called only inside the fenced, atomic assistant/tool append transaction. */
    public void associate(MessageWorkClaim claim, ConversationStore.ToolObservationData observation, long sequence,
                          ConversationStore.ToolCallData call, int toolOrdinal) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Observation association requires transaction");
        if (observation.artifactReceipt().isEmpty()) {
            return;
        }
        ArtifactReceipt receipt = observation.artifactReceipt().orElseThrow();
        ToolExecutionContext identity = receipt.context();
        Assert.isTrue(identity.sessionId().toString().equals(claim.sessionId().value())
                && identity.messageJobId().toString().equals(claim.messageJobId().value())
                && identity.toolOrdinal() == toolOrdinal && observation.toolCallId().equals(call.toolCallId())
                && observation.toolName().equals(call.toolName()), "Artifact must match actual tool invocation");
        int updated = jdbc.update("""
                update generated_artifact set observation_sequence=?
                where artifact_id=? and observation_token=? and session_id=? and message_job_id=?
                  and model_call_number=? and tool_ordinal=? and tool_name=?
                  and expected_observation=?::jsonb and input_arguments=?::jsonb and observation_sequence is null
                """, sequence, receipt.artifactId(), receipt.observationToken(), identity.sessionId(), identity.messageJobId(),
                identity.modelCallNumber(), toolOrdinal, observation.toolName(), json(observation.output()), json(call.arguments()));
        Assert.isTrue(updated == 1, "Artifact does not match successful observation");
    }

    public void release(UUID jobId) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Artifact release requires transaction");
        jdbc.update("update generated_artifact set released=true where message_job_id=? and observation_sequence is not null", jobId);
    }

    @Override
    public List<Artifact> released(UUID sessionId, UUID messageJobId) {
        return jdbc.query("""
                select artifact_id,title,filename,media_type,content from generated_artifact
                where session_id=? and message_job_id=? and released order by model_call_number,tool_ordinal
                """, (row, index) -> new Artifact(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getString(4), row.getBytes(5)), sessionId, messageJobId);
    }

    private boolean jsonEqual(String first, String second) {
        try {
            return mapper.readTree(first).equals(mapper.readTree(second));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored artifact observation", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid artifact observation", exception);
        }
    }

    private record Stored(UUID id, UUID token, String title, String filename, String mediaType, byte[] content,
                          String toolName, String observation, String arguments) {}
}
