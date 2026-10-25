package com.java.system.sessionagent.slack.domain;

import java.util.Objects;
import org.springframework.util.Assert;

/** Sensitive remote capability. Diagnostic rendering deliberately excludes the URL. */
public final class SlackUploadTicket {
    private final String fileId;
    private final String uploadUrl;
    public SlackUploadTicket(String fileId, String uploadUrl) {
        Assert.hasText(fileId, "Slack file ID must not be blank");
        Assert.hasText(uploadUrl, "Slack upload capability must not be blank");
        this.fileId = fileId;
        this.uploadUrl = uploadUrl;
    }
    public String fileId() { return fileId; }
    public String uploadUrl() { return uploadUrl; }
    @Override public String toString() { return "SlackUploadTicket[redacted]"; }
    @Override public boolean equals(Object other) {
        return other instanceof SlackUploadTicket ticket && fileId.equals(ticket.fileId) && uploadUrl.equals(ticket.uploadUrl);
    }
    @Override public int hashCode() { return Objects.hash(fileId, uploadUrl); }
}
