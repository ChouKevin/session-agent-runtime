package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.configuration.SlackProgressProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPermalinkParser;
import com.java.system.sessionagent.slack.domain.SlackProgressClaim;
import com.java.system.sessionagent.slack.domain.SlackProgressState;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;
import com.java.system.sessionagent.slack.port.out.SlackProgressStore;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate.SkillPresentation.ResourceKind;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Database-clock observations and ownership, independent of answer execution. */
public final class SlackPostgresProgressStore implements SlackProgressStore {
    private final SlackProgressMapper mapper;
    private final TransactionTemplate transactions;
    private final SlackProgressProperties properties;
    private final SlackDeliveryProperties delivery;

    public SlackPostgresProgressStore(DataSource dataSource, SlackProgressProperties properties, SlackDeliveryProperties delivery) {
        this.mapper = SlackMybatisMapperFactory.create(dataSource, SlackProgressMapper.class);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.properties = Objects.requireNonNull(properties, "Progress properties must not be null");
        this.delivery = Objects.requireNonNull(delivery, "Delivery properties must not be null");
    }

    @Override
    public void discover() {
        transactions.executeWithoutResult(status -> {
            mapper.interruptAbandonedLoads();
            mapper.discover();
            mapper.discoverWorkspaces();
        });
    }

    @Override
    public Optional<SlackProgressClaim> claimNext(String workerId, Duration leaseDuration) {
        Assert.hasText(workerId, "Progress worker ID must not be blank");
        Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        Assert.isTrue(!leaseDuration.isNegative() && !leaseDuration.isZero(), "Lease duration must be positive");
        Optional<SlackProgressClaim> result = transactions.execute(status -> {
            mapper.discoverWorkspaces();
            return mapper.claim(workerId, milliseconds(leaseDuration), milliseconds(properties.workspaceInterval())).stream()
                    .findFirst().map(row -> new SlackProgressClaim(row.messageJobId(), row.workerId(), row.claimNumber()));
        });
        return Objects.requireNonNull(result);
    }

    @Override
    public Optional<SlackProgressUpdate> refresh(SlackProgressClaim claim) {
        Objects.requireNonNull(claim, "Progress claim must not be null");
        Optional<SlackProgressUpdate> result = transactions.execute(status -> {
            if (mapper.lock(claim).isEmpty()) {
                return Optional.empty();
            }
            List<SlackProgressMapper.ObservationRow> refreshed = mapper.refresh(claim);
            // An owned not-yet-due reconciliation still uses its persisted genuine observation.
            return (refreshed.isEmpty() ? mapper.current(claim) : refreshed).stream().findFirst().map(this::update);
        });
        return Objects.requireNonNull(result);
    }

    @Override
    public boolean saveQuestionPermalink(SlackProgressClaim claim, String permalink) {
        Objects.requireNonNull(claim, "Progress claim must not be null");
        Boolean result = transactions.execute(status -> {
            if (mapper.lock(claim).isEmpty()) {
                return false;
            }
            return mapper.current(claim).stream().findFirst().map(row -> {
                new SlackPermalinkParser().requireMessage(permalink, row.channelId(), row.sourceMessageTs());
                return mapper.saveQuestionPermalink(claim, permalink) == 1;
            }).orElse(false);
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean markSent(SlackProgressClaim claim, long revision) {
        Objects.requireNonNull(claim, "Progress claim must not be null");
        Boolean result = transactions.execute(status -> {
            if (mapper.lock(claim).isEmpty()) {
                return false;
            }
            return mapper.current(claim).stream().findFirst()
                    .filter(row -> row.revision() == revision)
                    .map(row -> mapper.sent(claim, revision, SlackProgressState.valueOf(row.observedState()).terminal(),
                            milliseconds(delivery.leaseDuration()), milliseconds(properties.refreshInterval())) == 1)
                    .orElse(false);
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean recordFailure(SlackProgressClaim claim, long revision, SlackDeliveryFailureCategory category,
            Optional<Duration> retryAfter) {
        Objects.requireNonNull(claim, "Progress claim must not be null");
        Objects.requireNonNull(category, "Failure category must not be null");
        Objects.requireNonNull(retryAfter, "Retry-After must not be null");
        retryAfter.ifPresent(delay -> Assert.isTrue(!delay.isNegative(), "Retry-After must not be negative"));
        Boolean result = transactions.execute(status -> {
            if (mapper.lock(claim).isEmpty()) {
                return false;
            }
            Optional<Integer> failures = mapper.failures(claim, revision).stream().findFirst();
            if (failures.isEmpty()) {
                return false;
            }
            int count = failures.orElseThrow() + 1;
            Duration backoff = backoffDuration(count);
            Duration delay = retryAfter.filter(value -> value.compareTo(backoff) > 0).orElse(backoff);
            boolean retire = category == SlackDeliveryFailureCategory.PERMANENT || count >= delivery.maximumAttempts();
            if (mapper.failed(claim, revision, category.name(), retire, milliseconds(delay)) != 1) {
                return false;
            }
            if (category == SlackDeliveryFailureCategory.RATE_LIMIT || retryAfter.isPresent()) {
                mapper.cooldown(claim.messageJobId(), milliseconds(delay));
            }
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    private Duration backoffDuration(int count) {
        Duration backoff = delivery.initialBackoff();
        for (int attempt = 1; attempt < count && backoff.compareTo(delivery.maximumBackoff()) < 0; attempt++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(delivery.maximumBackoff()) > 0 ? delivery.maximumBackoff() : backoff;
    }

    private SlackProgressUpdate update(SlackProgressMapper.ObservationRow row) {
        SlackProgressState state = SlackProgressState.valueOf(row.observedState());
        Instant observedAt = row.observedAt().toInstant();
        Optional<String> permalink = Optional.ofNullable(row.questionPermalink());
        Optional<SlackProgressUpdate.SkillPresentation> presentation = Optional.ofNullable(row.presentationId())
                .map(id -> new SlackProgressUpdate.SkillPresentation(id,
                        SlackProgressUpdate.SkillPresentation.Kind.valueOf(row.presentationKind()), row.skillName(),
                        ResourceKind.valueOf(row.resourceKind())));
        String description = presentation.map(skill -> skillDescription(state, skill)).orElseGet(state::description);
        String text = description + permalink.map(link -> "\n<" + link + "|原始問題>").orElse("")
                + "\n最後確認（UTC）：" + observedAt
                + "\n此為最後確認的狀態；時間較舊不代表仍在執行。";
        return new SlackProgressUpdate(row.revision(), new SlackUpdateRequest(row.channelId(), row.targetMessageTs(), text),
                state.terminal(), observedAt, row.sourceMessageTs(), permalink, presentation);
    }

    private static String skillDescription(SlackProgressState state, SlackProgressUpdate.SkillPresentation skill) {
        boolean reference = skill.resourceKind() == ResourceKind.REFERENCE;
        String action = reference ? switch (state) {
            case SKILL_LOADING -> "正在讀取技能參考資料";
            case SKILL_LOADED -> "技能參考資料已讀取";
            case SKILL_FAILED -> "技能參考資料讀取失敗";
            case SKILL_INTERRUPTED -> "技能參考資料讀取已中斷";
            default -> throw new IllegalArgumentException("Invalid skill presentation state");
        } : state.description();
        return action + "：" + skill.skillName();
    }

    private static long milliseconds(Duration duration) { return Math.max(1, duration.toMillis()); }
}
