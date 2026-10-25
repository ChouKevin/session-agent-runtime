package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;

/** Text-only fixtures fail loudly on accidental artifact transport. */
public interface TextSlackWebApi extends SlackWebApi {
    @Override default SlackUploadTicket allocateUpload(String filename, int byteLength) {
        throw new AssertionError("Text-only fixture received upload allocation");
    }
    @Override default void uploadBytes(SlackUploadTicket ticket, byte[] content) {
        throw new AssertionError("Text-only fixture received upload bytes");
    }
    @Override default void finalizeUpload(SlackUploadTicket ticket, String title, String channelId, String rootThreadTs) {
        throw new AssertionError("Text-only fixture received upload finalization");
    }
}
