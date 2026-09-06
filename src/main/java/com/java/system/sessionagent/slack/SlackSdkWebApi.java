package com.java.system.sessionagent.slack;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SlackSdkWebApi implements SlackWebApi {

    private static final Set<String> PERMANENT_ERRORS = Set.of(
            "account_inactive", "channel_not_found", "invalid_auth", "is_archived", "not_in_channel", "token_revoked");

    private final SlackProperties properties;

    public SlackSdkWebApi(SlackProperties properties) {
        this.properties = java.util.Objects.requireNonNull(properties, "Slack properties must not be null");
    }

    @Override
    public String post(SlackPostRequest request) {
        SlackPostRequest requiredRequest = java.util.Objects.requireNonNull(request, "Slack post request must not be null");
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            ChatPostMessageResponse response = Slack.getInstance().methods(properties.botToken()).chatPostMessage(builder -> builder
                    .channel(requiredRequest.channelId())
                    .threadTs(requiredRequest.rootThreadTs())
                    .text(requiredRequest.text())
                    .replyBroadcast(false));
            if (!response.isOk()) {
                throw classifiedFailure(response);
            }
            if (!StringUtils.hasText(response.getTs())) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
            }
            return response.getTs();
        } catch (IOException | SlackApiException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    private static SlackPostFailure classifiedFailure(ChatPostMessageResponse response) {
        Optional<Duration> retryAfter = retryAfter(response.getHttpResponseHeaders());
        if (retryAfter.isPresent()) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter);
        }
        String error = response.getError();
        if (StringUtils.hasText(error) && PERMANENT_ERRORS.contains(error)) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        }
        return new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
    }

    private static Optional<Duration> retryAfter(Map<String, List<String>> headers) {
        return Optional.ofNullable(headers)
                .stream()
                .flatMap(values -> values.entrySet().stream())
                .filter(entry -> "retry-after".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .flatMap(SlackSdkWebApi::seconds);
    }

    private static Optional<Duration> seconds(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
