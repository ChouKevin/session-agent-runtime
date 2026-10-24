package com.java.system.sessionagent.conversation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Model admission and logical placement over one consistent audit snapshot. */
public final class ContextHistoryView {
    private final List<PlacedMessage> timeline;
    private final String eligibilityFingerprint;

    public ContextHistoryView(ConversationHistory snapshot) {
        Objects.requireNonNull(snapshot, "Conversation snapshot must not be null");
        List<SessionMessage> ordered = snapshot.messages().stream()
                .sorted(Comparator.comparingLong(message -> message.sequence().value())).toList();
        Map<MessageJobId, UserMessage> commandUsers = new HashMap<>();
        for (SessionMessage message : ordered) {
            if (message instanceof UserMessage user) {
                user.messageJobId().filter(snapshot.commands()::containsKey)
                        .ifPresent(job -> commandUsers.put(job, user));
            }
        }
        List<PlacedMessage> admitted = new ArrayList<>();
        for (SessionMessage message : ordered) {
            SkillCommandState command = message.messageJobId().map(snapshot.commands()::get).orElse(null);
            if (Objects.nonNull(command)) {
                if (message instanceof UserMessage
                        || (message instanceof RuntimeMessage && command.disposition() == SkillCommandState.Disposition.REJECTED)) {
                    continue;
                }
                if (message instanceof SkillMessage && command.disposition() == SkillCommandState.Disposition.ACCEPTED
                        && command.resolutionSequence().orElseThrow().equals(message.sequence())) {
                    UserMessage user = Objects.requireNonNull(commandUsers.get(command.jobId()), "Accepted command must have its audit USER");
                    admitted.add(new PlacedMessage(message.sequence().value(), user));
                }
            }
            admitted.add(new PlacedMessage(message.sequence().value(), message));
        }
        timeline = List.copyOf(admitted);
        eligibilityFingerprint = fingerprint(snapshot.commands());
    }

    public List<SessionMessage> between(long afterExclusive, long throughInclusive) {
        return timeline.stream().filter(placed -> placed.position() > afterExclusive && placed.position() <= throughInclusive)
                .map(PlacedMessage::message).toList();
    }

    public String eligibilityFingerprint() {
        return eligibilityFingerprint;
    }

    private static String fingerprint(Map<MessageJobId, SkillCommandState> commands) {
        StringBuilder serialized = new StringBuilder();
        commands.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().value())).forEach(entry -> {
            String id = entry.getKey().value();
            SkillCommandState state = entry.getValue();
            serialized.append(id.length()).append(':').append(id).append(':').append(state.disposition()).append(':')
                    .append(state.resolutionSequence().map(sequence -> Long.toString(sequence.value())).orElse("pending")).append(';');
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialized.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private record PlacedMessage(long position, SessionMessage message) { }
}
