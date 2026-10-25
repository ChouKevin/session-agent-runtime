package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.tool.port.ToolCapability;
import java.util.Set;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ModelRequest(
        List<SessionMessage> history,
        Map<SessionSequence, ModelContinuation> continuations,
        ToolSnapshot toolSnapshot,
        Optional<ContextSummary> contextSummary,
        Purpose purpose) {

    public enum Purpose {
        ANSWER, WRAP_UP
    }

    public static String directive(Purpose purpose) {
        return purpose == Purpose.WRAP_UP
                ? "This investigation stage has ended. Briefly describe confirmed progress and unresolved gaps. "
                    + "Ask whether to continue investigating or organize the existing evidence. Do not produce a partial report."
                : "";
    }
    public static String answerDirective(ToolSnapshot tools) {
        return tools.allowedCapabilities().equals(Set.of(ToolCapability.REPORT_GENERATION))
                ? "Organize only the retained evidence. Disclose unknown and uncovered areas. "
                    + "Do not perform further investigation or load new skills. Only report-generation tools are permitted."
                : "";
    }

    public String transientDirective() {
        return purpose == Purpose.WRAP_UP ? directive(purpose) : answerDirective(toolSnapshot);
    }


    public ModelRequest {
        Assert.notNull(history, "Model history must not be null");
        history = List.copyOf(history);
        Assert.notNull(continuations, "Model continuations must not be null");
        continuations = Map.copyOf(continuations);
        Assert.notNull(toolSnapshot, "Tool snapshot must not be null");
        Assert.notNull(contextSummary, "Context summary must not be null");
        Assert.notNull(purpose, "Model request purpose must not be null");
    }

    public ModelRequest(List<SessionMessage> history, Map<SessionSequence, ModelContinuation> continuations,
                        ToolSnapshot toolSnapshot, Purpose purpose) {
        this(history, continuations, toolSnapshot, Optional.empty(), purpose);
    }

    public ModelRequest(List<SessionMessage> history, ToolSnapshot toolSnapshot, Purpose purpose) {
        this(history, Map.of(), toolSnapshot, purpose);
    }
}
