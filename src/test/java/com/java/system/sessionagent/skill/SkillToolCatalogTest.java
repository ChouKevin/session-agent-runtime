package com.java.system.sessionagent.skill;

import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.adapter.tool.SkillToolCatalog;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SkillToolCatalogTest {
    @TempDir Path root;

    @Test
    void empty_registry_has_no_tool() {
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties());
             ToolSnapshot snapshot = new SkillToolCatalog(library).snapshot()) {
            assertThat(snapshot.definitions()).isEmpty();
        }
    }

    @Test
    void exposes_metadata_only_and_maps_current_reads_and_safe_failures() throws Exception {
        Path directory = Files.createDirectory(root.resolve("demo"));
        String body = "---\nname: demo\ndescription: Example instructions\n---\nPRIVATE_BODY";
        Files.writeString(directory.resolve("SKILL.md"), body);
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 128));
             ToolSnapshot snapshot = new SkillToolCatalog(library).snapshot()) {
            ToolDefinition definition = snapshot.definitions().getFirst();
            assertThat(definition.name().value()).isEqualTo("readSkill");
            assertThat(definition.description()).contains("demo", "Example instructions", "skill://demo")
                    .doesNotContain("PRIVATE_BODY", root.toString());
            assertThat(definition.inputSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of("uri", Map.of("type", "string")), "required", List.of("uri"), "additionalProperties", false));
            ToolOutput output = snapshot.invoke(new ToolName("readSkill"), Map.of("uri", "skill://demo"));
            assertThat(output.isError()).isFalse();
            Map<?, ?> result = (Map<?, ?>) output.result();
            assertThat(result.get("content")).isEqualTo(body);
            assertThat(result).hasSize(3);
            ToolOutput failure = snapshot.invoke(new ToolName("readSkill"), Map.of("uri", "skill://missing"));
            assertThat(failure.isError()).isTrue();
            assertThat(failure.result()).isEqualTo(Map.of("code", "SKILL_NOT_FOUND"));
            for (Map<String, Object> arguments : List.of(Map.<String, Object>of(), Map.<String, Object>of("uri", 42), Map.<String, Object>of("uri", "skill://demo", "extra", true))) {
                assertThat(snapshot.invoke(new ToolName("readSkill"), arguments).isError()).isTrue();
            }
        }
    }
}
