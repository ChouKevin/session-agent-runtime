package com.java.system.sessionagent.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.SlackApiErrorResponse;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import okhttp3.Response;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SlackSdkWebApi implements SlackWebApi, AutoCloseable {

    private static final Set<String> PERMANENT_ERRORS = Set.of(
            "account_inactive", "invalid_arg_name", "invalid_arguments", "invalid_array_arg", "invalid_auth",
            "invalid_blocks", "invalid_blocks_format", "invalid_charset", "invalid_form_data", "invalid_post_type",
            "channel_not_found", "is_archived", "missing_scope", "msg_blocks_too_long", "msg_too_long", "no_permission", "no_text",
            "token_revoked");

    private final SlackProperties properties;
    private final Slack slack;

    public SlackSdkWebApi(SlackProperties properties) {
        this(properties, slackFor(properties));
    }

    SlackSdkWebApi(SlackProperties properties, Slack slack) {
        this.properties = java.util.Objects.requireNonNull(properties, "Slack properties must not be null");
        this.slack = java.util.Objects.requireNonNull(slack, "Slack SDK client must not be null");
    }

    @Override
    public String post(SlackPostRequest request) {
        SlackPostRequest requiredRequest = java.util.Objects.requireNonNull(request, "Slack post request must not be null");
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            ChatPostMessageResponse response = slack.methods(properties.botToken()).chatPostMessage(builder -> builder
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
        } catch (SlackApiException exception) {
            throw classifiedFailure(exception);
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    private static SlackPostFailure classifiedFailure(ChatPostMessageResponse response) {
        Optional<Duration> retryAfter = retryAfter(response.getHttpResponseHeaders());
        if (retryAfter.isPresent()) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter);
        }
        return classifiedFailure(response.getError());
    }

    private static SlackPostFailure classifiedFailure(SlackApiException exception) {
        SlackApiException requiredException = java.util.Objects.requireNonNull(exception, "Slack API exception must not be null");
        Optional<Response> response = Optional.ofNullable(requiredException.getResponse());
        Optional<Duration> retryAfter = response.flatMap(SlackSdkWebApi::retryAfter);
        if (retryAfter.isPresent() && response.map(value -> value.code() == 429).orElse(false)) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter);
        }
        String error = Optional.ofNullable(requiredException.getError())
                .map(SlackApiErrorResponse::getError)
                .orElse("");
        return classifiedFailure(error);
    }

    private static SlackPostFailure classifiedFailure(String error) {
        if (StringUtils.hasText(error) && PERMANENT_ERRORS.contains(error)) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        }
        return new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
    }

    private static Optional<Duration> retryAfter(Response response) {
        return retryAfter(response.headers().toMultimap());
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

    private static Slack slackFor(SlackProperties properties) {
        SlackProperties requiredProperties = java.util.Objects.requireNonNull(properties, "Slack properties must not be null");
        SlackConfig config = new SlackConfig();
        config.setStatsEnabled(false);
        config.setHttpClientCallTimeoutMillis(Math.toIntExact(requiredProperties.delivery().callTimeout().toMillis()));
        return Slack.getInstance(config);
    }

    @Override
    public void close() {
        try {
            slack.close();
        } catch (Exception ignored) {
            // SDK shutdown is best effort and provider details are deliberately not logged.
        }
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
