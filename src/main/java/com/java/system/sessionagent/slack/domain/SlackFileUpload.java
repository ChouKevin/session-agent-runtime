package com.java.system.sessionagent.slack.domain;

import java.util.Optional;
import java.util.UUID;

/** Runtime-selected file metadata. Bytes are loaded only for the ALLOCATED byte-upload stage. */
public record SlackFileUpload(UUID identity, String filename, String title, String mediaType, int byteLength,
                              Optional<byte[]> uploadContent, SlackUploadState state, Optional<SlackUploadTicket> ticket) {
    @Override public String toString() { return "SlackFileUpload[identity=" + identity + ", state=" + state + "]"; }
}
