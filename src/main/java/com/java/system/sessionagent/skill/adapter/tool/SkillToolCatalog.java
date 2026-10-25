package com.java.system.sessionagent.skill.adapter.tool;

import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.tool.port.ToolBinding;
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

    public SkillToolCatalog(SkillLibrary library) {
        this.library = Objects.requireNonNull(library);
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
        return new ToolSnapshot(List.of(new ToolBinding(definition, (invocation, arguments) -> read(arguments))));
    }

    private ToolOutput read(Map<String, Object> arguments) {
        if (arguments.size() != 1 || !(arguments.get("uri") instanceof String uri)) {
            return new ToolOutput(true, Map.of("code", "SKILL_INVALID_ARGUMENTS"));
        }
        return switch (library.read(uri)) {
            case SkillReadResult.Success success -> {
                SkillResource resource = success.resource();
                yield new ToolOutput(false, Map.of("uri", resource.uri(), "sha256", resource.sha256(), "content", resource.content()));
            }
            case SkillReadResult.Failure failure -> new ToolOutput(true, Map.of("code", failure.code()));
        };
    }
}
