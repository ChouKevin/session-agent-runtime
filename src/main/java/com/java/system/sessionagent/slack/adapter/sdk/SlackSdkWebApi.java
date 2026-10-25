package com.java.system.sessionagent.slack.adapter.sdk;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.methods.response.chat.ChatGetPermalinkResponse;
import com.slack.api.methods.response.files.FilesGetUploadURLExternalResponse;
import com.slack.api.methods.response.files.FilesCompleteUploadExternalResponse;
import com.slack.api.methods.request.files.FilesCompleteUploadExternalRequest.FileDetails;
import com.slack.api.model.block.MarkdownBlock;
import com.slack.api.model.File;
import com.java.system.sessionagent.slack.domain.SlackAnswerPresentation;
import com.java.system.sessionagent.slack.domain.SlackPresentation;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import java.net.URI;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import com.slack.api.util.http.SlackHttpClient;
import com.java.system.sessionagent.slack.domain.SlackPermalinkParser;
import okhttp3.Response;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;

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
            "token_revoked", "file_type_not_allowed", "file_uploads_disabled", "not_allowed_token_type",
            "invalid_file_type", "file_not_found", "not_in_channel", "restricted_action", "upload_failed",
            "access_denied", "accesslimited", "ekm_access_denied", "enterprise_is_restricted",
            "file_upload_size_restricted", "file_uploads_except_images_disabled", "snippet_too_large",
            "storage_limit_reached", "team_access_not_granted", "posting_to_channel_denied", "token_expired",
            "not_authed", "user_is_external_guest");
    private static final Set<String> PERMANENT_UPDATE_ERRORS = Set.of(
            "message_not_found", "cant_update_message", "edit_window_closed", "not_in_channel", "restricted_action");

    private final SlackProperties properties;
    private final Slack slack;
    private final OkHttpClient uploadClient;
    private final Predicate<URI> uploadDestination;

    public SlackSdkWebApi(SlackProperties properties) {
        this(properties, slackFor(properties));
    }

    SlackSdkWebApi(SlackProperties properties, Slack slack) {
        this(properties, slack, SlackSdkWebApi::slackUploadDestination);
    }

    SlackSdkWebApi(SlackProperties properties, Slack slack, Predicate<URI> uploadDestination) {
        this.properties = java.util.Objects.requireNonNull(properties, "Slack properties must not be null");
        this.slack = java.util.Objects.requireNonNull(slack, "Slack SDK client must not be null");
        this.uploadDestination = java.util.Objects.requireNonNull(uploadDestination, "Upload destination policy must not be null");
        this.uploadClient = new OkHttpClient.Builder()
                .callTimeout(properties.delivery().callTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .connectTimeout(properties.delivery().callTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    @Override
    public String post(SlackPostRequest request) {
        SlackPostRequest requiredRequest = java.util.Objects.requireNonNull(request, "Slack post request must not be null");
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            ChatPostMessageResponse response = slack.methods(properties.botToken()).chatPostMessage(builder -> {
                builder.channel(requiredRequest.channelId()).threadTs(requiredRequest.rootThreadTs())
                        .replyBroadcast(false);
                if (requiredRequest.presentation() == SlackPresentation.MARKDOWN) {
                    builder.text(SlackAnswerPresentation.INLINE_NOTIFICATION)
                            .blocks(List.of(MarkdownBlock.builder().text(requiredRequest.text()).build()));
                } else {
                    builder.text(requiredRequest.text()).mrkdwn(false);
                }
                return builder;
            });
            if (!response.isOk()) {
                throw classifiedFailure(response);
            }
            if (!StringUtils.hasText(response.getTs())) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
            }
            if (messageWasTruncated(response)) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty(), Optional.of(response.getTs()));
            }
            return response.getTs();
        } catch (SlackApiException exception) {
            throw classifiedFailure(exception);
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    @Override
    public void update(SlackUpdateRequest request) {
        SlackUpdateRequest requiredRequest = java.util.Objects.requireNonNull(request, "Slack update request must not be null");
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            ChatUpdateResponse response = slack.methods(properties.botToken()).chatUpdate(builder -> builder
                    .channel(requiredRequest.channelId()).ts(requiredRequest.messageTs()).text(requiredRequest.text()));
            if (!response.isOk()) {
                Optional<Duration> delay = retryAfter(response.getHttpResponseHeaders());
                if (delay.isPresent()) {
                    throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, delay);
                }
                throw updateFailure(response.getError());
            }
        } catch (SlackApiException exception) {
            SlackPostFailure failure = classifiedFailure(exception);
            if (failure.category() == SlackDeliveryFailureCategory.RATE_LIMIT) {
                throw failure;
            }
            throw updateFailure(Optional.ofNullable(exception.getError()).map(value -> value.getError()).orElse(""));
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    @Override
    public String getPermalink(String channelId, String messageTs) {
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            ChatGetPermalinkResponse response = slack.methods(properties.botToken()).chatGetPermalink(builder -> builder
                    .channel(channelId).messageTs(messageTs));
            if (!response.isOk()) {
                Optional<Duration> delay = retryAfter(response.getHttpResponseHeaders());
                if (delay.isPresent()) {
                    throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, delay);
                }
                throw updateFailure(response.getError());
            }
            try {
                new SlackPermalinkParser().requireMessage(response.getPermalink(), channelId, messageTs);
            } catch (IllegalArgumentException exception) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
            }
            return response.getPermalink();
        } catch (SlackApiException exception) {
            SlackPostFailure failure = classifiedFailure(exception);
            if (failure.category() == SlackDeliveryFailureCategory.RATE_LIMIT) {
                throw failure;
            }
            throw updateFailure(Optional.ofNullable(exception.getError()).map(value -> value.getError()).orElse(""));
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    @Override
    public SlackUploadTicket allocateUpload(String filename, int byteLength) {
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            FilesGetUploadURLExternalResponse response = slack.methods(properties.botToken())
                    .filesGetUploadURLExternal(builder -> builder.filename(filename).length(byteLength));
            if (!response.isOk()) {
                throw remoteFailure(response.getError(), response.getHttpResponseHeaders());
            }
            if (!StringUtils.hasText(response.getFileId()) || !StringUtils.hasText(response.getUploadUrl())) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
            }
            SlackUploadTicket ticket = new SlackUploadTicket(response.getFileId(), response.getUploadUrl());
            requireUploadDestination(ticket);
            return ticket;
        } catch (SlackApiException exception) {
            throw uploadFailure(exception);
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    @Override
    public void uploadBytes(SlackUploadTicket ticket, byte[] content) {
        requireUploadDestination(ticket);
        // Dedicated client has no Slack token, cookies, redirects, or automatic transport retries.
        Request request = new Request.Builder().url(ticket.uploadUrl())
                .post(RequestBody.create(content, MediaType.get("application/octet-stream"))).build();
        try (Response response = uploadClient.newCall(request).execute()) {
            if (response.code() == 200) {
                return;
            }
            if (response.code() == 429 && retryAfter(response).isPresent()) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter(response));
            }
            throw new SlackPostFailure(response.code() >= 500
                    ? SlackDeliveryFailureCategory.TRANSIENT : SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    @Override
    public void finalizeUpload(SlackUploadTicket ticket, String title, String channelId, String rootThreadTs) {
        Assert.isTrue(properties.enabled(), "Slack Web API requires complete Slack configuration");
        try {
            FilesCompleteUploadExternalResponse response = slack.methods(properties.botToken())
                    .filesCompleteUploadExternal(builder -> builder
                            .files(List.of(FileDetails.builder().id(ticket.fileId()).title(title).build()))
                            .channelId(channelId).threadTs(rootThreadTs));
            if (!response.isOk()) {
                throw remoteFailure(response.getError(), response.getHttpResponseHeaders());
            }
            List<File> files = Optional.ofNullable(response.getFiles()).orElse(List.of());
            if (files.size() != 1 || !ticket.fileId().equals(files.getFirst().getId())) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
            }
        } catch (SlackApiException exception) {
            throw uploadFailure(exception);
        } catch (IOException exception) {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
    }

    private static SlackPostFailure remoteFailure(String error, Map<String, List<String>> headers) {
        Optional<Duration> delay = retryAfter(headers);
        // A timing header is not proof that a one-time operation was rejected before execution.
        return "ratelimited".equals(error) && delay.isPresent()
                ? new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, delay) : classifiedFailure(error);
    }

    private static SlackPostFailure uploadFailure(SlackApiException exception) {
        String error = Optional.ofNullable(exception.getError()).map(value -> value.getError()).orElse("");
        Map<String, List<String>> headers = Optional.ofNullable(exception.getResponse())
                .map(response -> response.headers().toMultimap()).orElse(Map.of());
        return remoteFailure(error, headers);
    }

    private void requireUploadDestination(SlackUploadTicket ticket) {
        try {
            if (uploadDestination.test(URI.create(ticket.uploadUrl()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // A capability is never included in diagnostics, including malformed-URI exceptions.
        }
        throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
    }

    private static boolean slackUploadDestination(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "files.slack.com".equalsIgnoreCase(uri.getHost())
                && java.util.Objects.isNull(uri.getUserInfo()) && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getPath().startsWith("/upload/");
    }

    private static SlackPostFailure updateFailure(String error) {
        if (PERMANENT_UPDATE_ERRORS.contains(Optional.ofNullable(error).orElse(""))) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        }
        return classifiedFailure(error);
    }

    private static SlackPostFailure classifiedFailure(ChatPostMessageResponse response) {
        Optional<Duration> retryAfter = retryAfter(response.getHttpResponseHeaders());
        if (retryAfter.isPresent()) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter);
        }
        return classifiedFailure(response.getError());
    }

    private static boolean messageWasTruncated(ChatPostMessageResponse response) {
        return Optional.ofNullable(response.getResponseMetadata())
                .map(metadata -> metadata.getWarnings())
                .orElse(List.of())
                .contains("message_truncated");
    }

    private static SlackPostFailure classifiedFailure(SlackApiException exception) {
        SlackApiException requiredException = java.util.Objects.requireNonNull(exception, "Slack API exception must not be null");
        Optional<Response> response = Optional.ofNullable(requiredException.getResponse());
        Optional<Duration> retryAfter = response.flatMap(SlackSdkWebApi::retryAfter);
        if (retryAfter.isPresent() && response.map(value -> value.code() == 429).orElse(false)) {
            return new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, retryAfter);
        }
        String error = Optional.ofNullable(requiredException.getError())
                .map(value -> value.getError())
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
        SlackHttpClient httpClient = new SlackHttpClient(SlackHttpClient.buildOkHttpClient(config).newBuilder()
                .retryOnConnectionFailure(false).build());
        httpClient.setConfig(config);
        return Slack.getInstance(config, httpClient);
    }

    @Override
    public void close() {
        try {
            uploadClient.dispatcher().executorService().shutdown();
            uploadClient.connectionPool().evictAll();
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
