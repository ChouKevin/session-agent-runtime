package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;

public interface SlackWebApi {

    String post(SlackPostRequest request);

    void update(SlackUpdateRequest request);

    String getPermalink(String channelId, String messageTs);

    SlackUploadTicket allocateUpload(String filename, int byteLength);

    void uploadBytes(SlackUploadTicket ticket, byte[] content);

    void finalizeUpload(SlackUploadTicket ticket, String title, String channelId, String rootThreadTs);
}
