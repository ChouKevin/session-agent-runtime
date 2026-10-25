package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.SkillLoadObserver;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits only STARTED; history persistence alone can finalize a successful read. */
public final class PostgresSkillLoadObserver implements SkillLoadObserver {
    private final PostgresConversationMapper mapper;
    private final TransactionTemplate transactions;

    public PostgresSkillLoadObserver(DataSource dataSource) {
        mapper = PostgresMybatisMapperFactory.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public UUID started(ToolInvocation invocation, String uri) {
        ToolExecutionContext context = invocation.context();
        ToolExecutionAuthority authority = invocation.authority();
        return Objects.requireNonNull(transactions.execute(status -> {
            if (mapper.requireLiveClaim(context.messageJobId(), context.sessionId(), authority.workerId(), authority.claimNumber()) != 1) {
                throw new StaleWorkClaimException();
            }
            if (mapper.isCancellationRequested(context.messageJobId())) {
                throw new JobCancellationRequestedException();
            }
            return mapper.startSkillLoad(UUID.randomUUID(), context.messageJobId(), context.sessionId(), "AUTOMATIC",
                    SkillReadReceipt.resource(uri), authority.workerId(), authority.claimNumber(), context.modelCallNumber(),
                    context.toolOrdinal()).stream().findFirst().orElseThrow(StaleWorkClaimException::new);
        }));
    }
}
