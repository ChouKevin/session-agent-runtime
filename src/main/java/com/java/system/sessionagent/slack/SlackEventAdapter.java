package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public final class SlackEventAdapter {

    private final String botUserId;
    private final SlackRootIntakePort rootIntakePort;

    public SlackEventAdapter(String botUserId, SlackRootIntakePort rootIntakePort) {
        Assert.notNull(botUserId, "Slack bot user ID must not be null");
        Assert.notNull(rootIntakePort, "Slack root intake port must not be null");
        this.botUserId = botUserId;
        this.rootIntakePort = rootIntakePort;
    }

    public SlackEventOutcome handle(SlackRootEvent event) {
        Assert.notNull(event, "Slack root event must not be null");
        if (!isCandidateHumanRoot(event)) {
            return SlackEventOutcome.IGNORED;
        }
        String message = normalizedMessage(event);
        if (!StringUtils.hasText(message)) {
            return SlackEventOutcome.IGNORED;
        }
        String rootThreadTs = StringUtils.hasText(event.threadTs()) ? event.threadTs() : event.messageTs();
        IncomingMessage incomingMessage = new IncomingMessage(
                IncomingMessageSource.SLACK,
                boundedKey("slack/", event.teamId(), event.channelId(), rootThreadTs),
                event.participantId(),
                boundedKey("slack/", event.teamId(), event.channelId(), event.messageTs()),
                message);
        rootIntakePort.receive(new SlackRootIntake(
                event.teamId(), event.channelId(), rootThreadTs, event.messageTs(), incomingMessage));
        return SlackEventOutcome.ACCEPTED;
    }

    private boolean isCandidateHumanRoot(SlackRootEvent event) {
        if (!StringUtils.hasText(event.participantId()) || StringUtils.hasText(event.botId())
                || botUserId.equals(event.participantId()) || StringUtils.hasText(event.subtype())) {
            return false;
        }
        return !StringUtils.hasText(event.threadTs()) || event.messageTs().equals(event.threadTs());
    }

    private String normalizedMessage(SlackRootEvent event) {
        if (!StringUtils.hasText(event.text())) {
            return "";
        }
        if ("im".equals(event.channelType())) {
            return event.text().trim();
        }
        String addressingMention = "<@" + botUserId + ">";
        int addressingIndex = event.text().indexOf(addressingMention);
        if (addressingIndex < 0) {
            return "";
        }
        return (event.text().substring(0, addressingIndex)
                + event.text().substring(addressingIndex + addressingMention.length())).trim();
    }

    private static String boundedKey(String prefix, String... parts) {
        String value = prefix + String.join("/", parts);
        Assert.isTrue(value.length() <= 256, "Slack source identifiers must not exceed 256 characters");
        return value;
    }
}
