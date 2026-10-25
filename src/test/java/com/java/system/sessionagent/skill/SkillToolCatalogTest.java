package com.java.system.sessionagent.skill;

import static com.java.system.sessionagent.tool.TestToolInvocation.invocation;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.adapter.tool.SkillToolCatalog;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import static org.mockito.Mockito.*;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class SkillToolCatalogTest {
    @TempDir Path root;

    @Test
    void empty_registry_has_no_tool() {
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties());
             ToolSnapshot snapshot = new SkillToolCatalog(library, (call, uri) -> UUID.randomUUID()).snapshot()) {
            assertThat(snapshot.definitions()).isEmpty();
        }
    }

    @Test
    void exposes_metadata_only_and_maps_current_reads_and_safe_failures() throws Exception {
        Path directory = Files.createDirectory(root.resolve("demo"));
        String body = "---\nname: demo\ndescription: Example instructions\n---\nPRIVATE_BODY";
        Files.writeString(directory.resolve("SKILL.md"), body);
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 128));
             ToolSnapshot snapshot = new SkillToolCatalog(library, (call, uri) -> UUID.randomUUID()).snapshot()) {
            ToolDefinition definition = snapshot.definitions().getFirst();
            assertThat(definition.name().value()).isEqualTo("readSkill");
            assertThat(definition.description()).contains("demo", "Example instructions", "skill://demo")
                    .doesNotContain("PRIVATE_BODY", root.toString());
            assertThat(definition.inputSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of("uri", Map.of("type", "string")), "required", List.of("uri"), "additionalProperties", false));
            ToolOutput output = snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://demo"));
            assertThat(output.isError()).isFalse();
            Map<?, ?> result = (Map<?, ?>) output.result();
            assertThat(result.get("content")).isEqualTo(body);
            assertThat(result).hasSize(3);
            ToolOutput failure = snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://missing"));
            assertThat(failure.isError()).isTrue();
            assertThat(failure.result()).isEqualTo(Map.of("code", "SKILL_NOT_FOUND"));
            for (Map<String, Object> arguments : List.of(Map.<String, Object>of(), Map.<String, Object>of("uri", 42), Map.<String, Object>of("uri", "skill://demo", "extra", true))) {
                assertThat(snapshot.invoke(invocation(), new ToolName("readSkill"), arguments).isError()).isTrue();
            }
        }
    }

    @Test
    void successful_definition_and_reference_reads_carry_distinct_internal_receipts_without_changing_model_payload() throws Exception {
        Path directory = Files.createDirectory(root.resolve("demo"));
        String definition = "---\nname: demo\ndescription: Example instructions\n---\nPRIVATE_BODY";
        Files.writeString(directory.resolve("SKILL.md"), definition);
        Files.createDirectory(directory.resolve("references"));
        Files.writeString(directory.resolve("references/guide.md"), "REFERENCE_BODY");
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 128));
             ToolSnapshot snapshot = new SkillToolCatalog(library, (call, uri) -> UUID.randomUUID()).snapshot()) {
            ToolOutput main = snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://demo/SKILL.md"));
            ToolOutput reference = snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://demo/references/guide.md"));

            assertThat(main.skillReadReceipt()).hasValueSatisfying(receipt -> {
                assertThat(receipt.attemptId()).isNotNull();
                assertThat(receipt.skillName()).isEqualTo("demo");
                assertThat(receipt.resourceUri()).isEqualTo("skill://demo");
                assertThat(receipt.resourceKind()).isEqualTo(SkillReadReceipt.ResourceKind.DEFINITION);
                assertThat(receipt.outcome()).isEqualTo(SkillReadReceipt.Outcome.SUCCEEDED);
                assertThat(receipt.sha256()).contains((String) ((Map<?, ?>) main.result()).get("sha256"));
                assertThat(receipt.errorCode()).isEmpty();
            });
            assertThat(reference.skillReadReceipt()).hasValueSatisfying(receipt -> {
                assertThat(receipt.attemptId()).isNotEqualTo(main.skillReadReceipt().orElseThrow().attemptId());
                assertThat(receipt.skillName()).isEqualTo("demo");
                assertThat(receipt.resourceUri()).isEqualTo("skill://demo/references/guide.md");
                assertThat(receipt.resourceKind()).isEqualTo(SkillReadReceipt.ResourceKind.REFERENCE);
                assertThat(receipt.outcome()).isEqualTo(SkillReadReceipt.Outcome.SUCCEEDED);
            });
            assertThat(main.asStructuredValue()).isEqualTo(Map.of("isError", false, "result", main.result()));
            assertThat(reference.asStructuredValue()).isEqualTo(Map.of("isError", false, "result", reference.result()));
            assertThat((Map<?, ?>) main.result()).hasSize(3);
            assertThat((Map<?, ?>) reference.result()).hasSize(3);
        }
    }

    @Test
    void unavailable_reads_carry_failed_receipts_and_invalid_uris_never_supply_an_unsafe_skill_label() throws Exception {
        Path directory = Files.createDirectory(root.resolve("demo"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: demo\ndescription: Example instructions\n---\nBODY");
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 128));
             ToolSnapshot snapshot = new SkillToolCatalog(library, (call, uri) -> UUID.randomUUID()).snapshot()) {
            ToolOutput missing = snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://missing"));
            assertThat(missing.isError()).isTrue();
            assertThat(missing.skillReadReceipt()).hasValueSatisfying(receipt -> {
                assertThat(receipt.skillName()).isEqualTo("missing");
                assertThat(receipt.outcome()).isEqualTo(SkillReadReceipt.Outcome.FAILED);
                assertThat(receipt.sha256()).isEmpty();
                assertThat(receipt.errorCode()).contains("SKILL_NOT_FOUND");
            });
            ToolOutput invalid = snapshot.invoke(invocation(), new ToolName("readSkill"),
                    Map.of("uri", "skill://../PRIVATE_TOKEN/<@U1>"));
            assertThat(invalid.isError()).isTrue();
            assertThat(invalid.skillReadReceipt()).hasValueSatisfying(receipt -> {
                assertThat(receipt.skillName()).doesNotContain("PRIVATE_TOKEN", "..", "<", ">", "/");
                assertThat(receipt.resourceUri()).doesNotContain("PRIVATE_TOKEN", "<@U1>");
                assertThat(receipt.outcome()).isEqualTo(SkillReadReceipt.Outcome.FAILED);
            });
            assertThat(invalid.asStructuredValue()).isEqualTo(Map.of("isError", true, "result", Map.of("code", "SKILL_INVALID_URI")));
        }
    }

    @Test
    void rejected_start_authority_prevents_the_filesystem_read() {
        SkillLibrary library = mock(SkillLibrary.class);
        when(library.metadata()).thenReturn(List.of(new SkillMetadata("demo", "Example", "skill://demo")));
        try (ToolSnapshot snapshot = new SkillToolCatalog(library, (call, uri) -> {
            throw new StaleWorkClaimException();
        }).snapshot()) {
            assertThatThrownBy(() -> snapshot.invoke(invocation(), new ToolName("readSkill"), Map.of("uri", "skill://demo")))
                    .isInstanceOf(StaleWorkClaimException.class);
            verify(library, never()).read(any());
        }
    }
}
