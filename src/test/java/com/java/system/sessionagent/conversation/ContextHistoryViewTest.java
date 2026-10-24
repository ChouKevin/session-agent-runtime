package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.SkillCommand;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.skill.domain.SkillResource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

class ContextHistoryViewTest {
    private static final SessionId SESSION = new SessionId("session");
    private static final MessageJobId COMMAND = new MessageJobId("command");
    private static final MessageJobId ORDINARY = new MessageJobId("ordinary");

    @Test
    void pending_or_rejected_command_never_reaches_context_but_audit_remains_intact() {
        UserMessage command = user(2, COMMAND, "/skill:review secret");
        RuntimeMessage error = new RuntimeMessage(SESSION, new SessionSequence(4), Optional.of(COMMAND), Instant.EPOCH,
                MessageRole.RUNTIME, "SKILL_UNAVAILABLE", "safe error");
        List<SessionMessage> audit = List.of(user(1, ORDINARY, "normal"), command, answer(3), error);
        ContextHistoryView pending = new ContextHistoryView(new ConversationHistory(audit.subList(0, 3),
                Map.of(COMMAND, state(SkillCommandState.Disposition.PENDING, Optional.empty()))));
        ContextHistoryView rejected = new ContextHistoryView(new ConversationHistory(audit,
                Map.of(COMMAND, state(SkillCommandState.Disposition.REJECTED, Optional.of(new SessionSequence(4))))));

        assertThat(pending.between(0, Long.MAX_VALUE)).containsExactly(audit.get(0), audit.get(2));
        assertThat(rejected.between(0, Long.MAX_VALUE)).containsExactly(audit.get(0), audit.get(2));
        assertThat(audit).contains(command, error);
    }

    @Test
    void admitted_command_crossing_old_boundary_reaches_model_once() {
        UserMessage command = user(2, COMMAND, "/skill:review actual question");
        SkillMessage skill = skill(4);
        ContextHistoryView view = accepted(List.of(user(1, ORDINARY, "first"), command, answer(3), skill));

        assertThat(view.between(0, Long.MAX_VALUE)).extracting(message -> message.sequence().value()).containsExactly(1L, 3L, 2L, 4L);
        assertThat(view.between(3, Long.MAX_VALUE)).containsExactly(command, skill);
        assertThat(view.between(0, 3)).extracting(message -> message.sequence().value()).containsExactly(1L, 3L);
        assertThat(view.between(3, 4)).containsExactly(command, skill);
    }

    @Test
    void compacting_resolution_pair_does_not_restore_skill() {
        ContextHistoryView view = accepted(List.of(user(2, COMMAND, "/skill:review"), answer(3), skill(4), answer(5)));

        assertThat(view.between(4, Long.MAX_VALUE)).containsExactly(answer(5));
        assertThat(view.between(5, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void automatic_read_failure_remains_visible_with_original_native_batch_identity() {
        AssistantToolCallsMessage calls = new AssistantToolCallsMessage(SESSION, new SessionSequence(3), Optional.of(ORDINARY), Instant.EPOCH,
                MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(), List.of(request("call", "readSkill")));
        ToolObservation failure = new ToolObservation(SESSION, new SessionSequence(4), Optional.of(ORDINARY), Instant.EPOCH,
                MessageRole.TOOL, new ToolCallId("call"), "readSkill", Map.of("isError", true, "code", "SKILL_UNAVAILABLE"));
        UserMessage pending = user(2, COMMAND, "/skill:missing");
        ContextHistoryView view = new ContextHistoryView(new ConversationHistory(List.of(user(1, ORDINARY, "read"), pending, calls, failure),
                Map.of(COMMAND, state(SkillCommandState.Disposition.PENDING, Optional.empty()))));

        assertThat(view.between(0, Long.MAX_VALUE)).containsExactly(user(1, ORDINARY, "read"), calls, failure);
        assertThat(view.between(2, Long.MAX_VALUE)).containsExactly(calls, failure);
    }

    @Test
    void eligibility_fingerprint_is_order_independent_and_changes_with_classification_or_resolution() {
        MessageJobId other = new MessageJobId("other");
        SkillCommandState pending = state(SkillCommandState.Disposition.PENDING, Optional.empty());
        SkillCommandState rejected = state(SkillCommandState.Disposition.REJECTED, Optional.of(new SessionSequence(4)));
        Map<MessageJobId, SkillCommandState> first = new LinkedHashMap<>();
        first.put(COMMAND, pending);
        first.put(other, new SkillCommandState(other, pending.command(), pending.disposition(), pending.resolutionSequence()));
        Map<MessageJobId, SkillCommandState> reversed = new LinkedHashMap<>();
        reversed.put(other, first.get(other));
        reversed.put(COMMAND, pending);

        String fingerprint = new ContextHistoryView(new ConversationHistory(List.of(), first)).eligibilityFingerprint();
        assertThat(new ContextHistoryView(new ConversationHistory(List.of(), reversed)).eligibilityFingerprint()).isEqualTo(fingerprint);
        assertThat(new ContextHistoryView(new ConversationHistory(List.of(), Map.of(COMMAND, rejected))).eligibilityFingerprint())
                .isNotEqualTo(new ContextHistoryView(new ConversationHistory(List.of(), Map.of(COMMAND, pending))).eligibilityFingerprint());
        assertThat(new ContextHistoryView(new ConversationHistory(List.of(), Map.of(COMMAND,
                state(SkillCommandState.Disposition.REJECTED, Optional.of(new SessionSequence(5)))))).eligibilityFingerprint())
                .isNotEqualTo(new ContextHistoryView(new ConversationHistory(List.of(), Map.of(COMMAND, rejected))).eligibilityFingerprint());
    }

    private static ContextHistoryView accepted(List<SessionMessage> history) {
        return new ContextHistoryView(new ConversationHistory(history,
                Map.of(COMMAND, state(SkillCommandState.Disposition.ACCEPTED, Optional.of(new SessionSequence(4))))));
    }

    private static SkillCommandState state(SkillCommandState.Disposition disposition, Optional<SessionSequence> sequence) {
        return new SkillCommandState(COMMAND, SkillCommand.parse("/skill:review actual question").orElseThrow(), disposition, sequence);
    }

    private static UserMessage user(long sequence, MessageJobId job, String text) {
        return new UserMessage(SESSION, new SessionSequence(sequence), Optional.of(job), Instant.EPOCH, MessageRole.USER, "alice", text);
    }

    private static AssistantMessage answer(long sequence) {
        return new AssistantMessage(SESSION, new SessionSequence(sequence), Optional.of(ORDINARY), Instant.EPOCH, MessageRole.ASSISTANT, "answer");
    }

    private static SkillMessage skill(long sequence) {
        return new SkillMessage(SESSION, new SessionSequence(sequence), Optional.of(COMMAND), Instant.EPOCH, MessageRole.SKILL,
                new SkillResource("skill://review/SKILL.md", "a".repeat(64), "review instructions"));
    }
}
