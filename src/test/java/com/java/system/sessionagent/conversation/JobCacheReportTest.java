package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.JobCacheReport;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobCacheReportTest {

    @Test
    void observations_accept_zero_usage_but_reject_invalid_token_pairs_and_nonpositive_ordinals() {
        assertThat(new ModelCallObservation(1, Optional.of(new CacheTokenUsage(0, 0))).cacheUsage())
                .contains(new CacheTokenUsage(0, 0));
        assertThat(new ModelCallObservation(1, Optional.empty()).cacheUsage()).isEmpty();
        assertThatThrownBy(() -> new CacheTokenUsage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheTokenUsage(10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheTokenUsage(10, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCallObservation(0, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completed_job_reports_weighted_totals_with_six_decimal_precision() {
        JobCacheReport report = JobCacheReport.from(JobStatus.DONE, 3, 3, 45_000, 25_000);

        assertThat(report.phase()).isEqualTo(JobCacheReport.Phase.FINAL);
        assertThat(report.status()).isEqualTo(JobCacheReport.Status.COMPLETE);
        assertThat(report.requestCount()).isEqualTo(3);
        assertThat(report.knownUsageRequestCount()).isEqualTo(3);
        assertThat(report.knownInputTokens()).isEqualTo(45_000);
        assertThat(report.knownCachedInputTokens()).isEqualTo(25_000);
        assertThat(report.hitRatio()).contains(new BigDecimal("0.555556"));
    }

    @Test
    void working_job_reports_provisional_known_subset_without_treating_missing_usage_as_zero() {
        JobCacheReport report = JobCacheReport.from(JobStatus.WORKING, 3, 2, 10_000, 6_000);

        assertThat(report.phase()).isEqualTo(JobCacheReport.Phase.PROVISIONAL);
        assertThat(report.status()).isEqualTo(JobCacheReport.Status.PARTIAL);
        assertThat(report.requestCount()).isEqualTo(3);
        assertThat(report.knownUsageRequestCount()).isEqualTo(2);
        assertThat(report.knownInputTokens()).isEqualTo(10_000);
        assertThat(report.knownCachedInputTokens()).isEqualTo(6_000);
        assertThat(report.hitRatio()).contains(new BigDecimal("0.600000"));
    }

    @Test
    void no_requests_unavailable_usage_and_zero_denominators_remain_distinct() {
        JobCacheReport notCalled = JobCacheReport.from(JobStatus.DONE, 0, 0, 0, 0);
        JobCacheReport unavailable = JobCacheReport.from(JobStatus.DONE, 3, 0, 0, 0);
        JobCacheReport completeZeroInput = JobCacheReport.from(JobStatus.DONE, 3, 3, 0, 0);
        JobCacheReport partialZeroInput = JobCacheReport.from(JobStatus.DONE, 3, 2, 0, 0);

        assertThat(notCalled.status()).isEqualTo(JobCacheReport.Status.NOT_CALLED);
        assertThat(unavailable.status()).isEqualTo(JobCacheReport.Status.UNAVAILABLE);
        assertThat(completeZeroInput.status()).isEqualTo(JobCacheReport.Status.NOT_APPLICABLE);
        assertThat(partialZeroInput.status()).isEqualTo(JobCacheReport.Status.PARTIAL);
        assertThat(notCalled.hitRatio()).isEmpty();
        assertThat(unavailable.hitRatio()).isEmpty();
        assertThat(completeZeroInput.hitRatio()).isEmpty();
        assertThat(partialZeroInput.hitRatio()).isEmpty();
    }

    @Test
    void cancellation_finality_is_independent_of_incomplete_usage() {
        JobCacheReport report = JobCacheReport.from(JobStatus.CANCELLED, 2, 1, 10, 0);

        assertThat(report.phase()).isEqualTo(JobCacheReport.Phase.FINAL);
        assertThat(report.status()).isEqualTo(JobCacheReport.Status.PARTIAL);
        assertThat(report.hitRatio()).contains(new BigDecimal("0.000000"));
        assertThat(JobCacheReport.from(JobStatus.RETRY, 2, 2, 10, 5).phase())
                .isEqualTo(JobCacheReport.Phase.PROVISIONAL);
    }
}
