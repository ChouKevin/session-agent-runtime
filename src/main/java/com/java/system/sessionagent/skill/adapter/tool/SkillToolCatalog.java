package com.java.system.sessionagent.skill.adapter.tool;

import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.tool.port.SkillLoadObserver;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import java.util.Optional;
import java.util.UUID;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Borrows the library; application composition owns and closes that library. */
public final class SkillToolCatalog implements ToolCatalog {
    private final SkillLibrary library;
    private final SkillLoadObserver observer;

    public SkillToolCatalog(SkillLibrary library, SkillLoadObserver observer) {
        this.library = Objects.requireNonNull(library);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override
    public ToolSnapshot snapshot() {
        List<SkillMetadata> metadata = library.metadata();
        if (metadata.isEmpty()) {
            return new ToolSnapshot(List.of());
        }
        StringBuilder description = new StringBuilder("Read current skill instructions or a relative reference using its skill URI. Available skills:\n");
        metadata.stream().sorted(Comparator.comparing(SkillMetadata::name)).forEach(entry -> description
                .append(entry.name()).append(" | ").append(entry.description()).append(" | ").append(entry.uri()).append('\n'));
        ToolDefinition definition = ToolDefinition.fromExposedName("readSkill", description.toString(), Map.of(
                "type", "object", "properties", Map.of("uri", Map.of("type", "string")),
                "required", List.of("uri"), "additionalProperties", false));
        return new ToolSnapshot(List.of(new ToolBinding(definition, this::read, ToolCapability.ORDINARY)));
    }

    private ToolOutput read(ToolInvocation invocation, Map<String, Object> arguments) {
        String uri = arguments.get("uri") instanceof String value ? value : "";
        UUID attempt = observer.started(invocation, uri);
        SkillReadResult result = arguments.size() == 1 && arguments.get("uri") instanceof String
                ? library.read(uri) : new SkillReadResult.Failure("SKILL_INVALID_ARGUMENTS");
        SkillReadReceipt receipt = result instanceof SkillReadResult.Success success
                ? SkillReadReceipt.succeeded(attempt, uri, success.resource().uri(), success.resource().sha256())
                : SkillReadReceipt.failed(attempt, uri, ((SkillReadResult.Failure) result).code());
        Object value = result instanceof SkillReadResult.Success success
                ? Map.of("uri", success.resource().uri(), "sha256", success.resource().sha256(), "content", success.resource().content())
                : Map.of("code", ((SkillReadResult.Failure) result).code());
        return new ToolOutput(result instanceof SkillReadResult.Failure, value, Optional.empty(), Optional.of(receipt));
    }
}
