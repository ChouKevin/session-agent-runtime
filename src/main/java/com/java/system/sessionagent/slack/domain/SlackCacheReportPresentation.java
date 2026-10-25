package com.java.system.sessionagent.slack.domain;

import com.java.system.sessionagent.conversation.domain.JobCacheReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Slack-only presentation of the provider-neutral job accounting report. */
public final class SlackCacheReportPresentation {
    private SlackCacheReportPresentation() {}

    public static String format(JobCacheReport report) {
        Objects.requireNonNull(report, "Job cache report must not be null");
        return "Context cache：" + switch (report.status()) {
            case NOT_CALLED -> "未呼叫模型";
            case UNAVAILABLE -> "未提供";
            case NOT_APPLICABLE -> "不適用（input tokens 為 0）";
            case COMPLETE -> knownUsage(report);
            case PARTIAL -> "資料不完整；已知部分 " + knownUsage(report)
                    + "；完整 usage " + report.knownUsageRequestCount() + "/" + report.requestCount() + " 次";
        };
    }

    private static String knownUsage(JobCacheReport report) {
        if (report.knownInputTokens() == 0) {
            return "不適用（input tokens 為 0）";
        }
        BigDecimal percentage = BigDecimal.valueOf(report.knownCachedInputTokens()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(report.knownInputTokens()), 1, RoundingMode.HALF_UP);
        return percentage.toPlainString() + "%（" + report.knownCachedInputTokens() + " / "
                + report.knownInputTokens() + " input tokens）";
    }
}
