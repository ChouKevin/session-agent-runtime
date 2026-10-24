package com.java.system.sessionagent.skill;

import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FileSystemSkillLibraryTest {
    @TempDir Path root;

    @Test
    void rereads_current_content_without_changing_old_result() throws Exception {
        Path definition = definition("demo", "first", "A");
        try (FileSystemSkillLibrary library = library(65536)) {
            SkillResource first = success(library.read("skill://demo/SKILL.md"));
            String secondText = text("demo", "first", "B");
            Files.writeString(definition, secondText);
            SkillResource second = success(library.read("skill://demo"));
            assertThat(first.content()).endsWith("A");
            assertThat(first.uri()).isEqualTo("skill://demo");
            assertThat(second.content()).isEqualTo(secondText);
            assertThat(first.sha256()).isEqualTo(hash(first.content()));
            assertThat(second.sha256()).isEqualTo(hash(secondText)).isNotEqualTo(first.sha256());
        }
    }

    @Test
    void keeps_metadata_until_restart() throws Exception {
        Path definition = definition("demo", "first", "A");
        try (FileSystemSkillLibrary library = library(65536)) {
            Files.writeString(definition, text("demo", "second", "B"));
            assertThat(success(library.read("skill://demo")).content()).contains("second");
            assertThat(library.metadata().getFirst().description()).isEqualTo("first");
        }
        try (FileSystemSkillLibrary library = library(65536)) {
            assertThat(library.metadata().getFirst().description()).isEqualTo("second");
        }
    }

    @Test
    void rejects_outside_reference_after_directory_replacement() throws Exception {
        definition("demo", "first", "A");
        Path references = Files.createDirectory(root.resolve("demo/references"));
        Files.writeString(references.resolve("guide.md"), "inside");
        Path outside = Files.createTempDirectory(root, "outside-");
        Files.writeString(outside.resolve("guide.md"), "OUTSIDE_SENTINEL");
        try (FileSystemSkillLibrary library = library(65536)) {
            assertThat(success(library.read("skill://demo/references/guide.md")).content()).isEqualTo("inside");
            Files.move(references, root.resolve("old-references"));
            Files.createSymbolicLink(references, outside);
            assertThat(library.read("skill://demo/references/guide.md")).isInstanceOf(SkillReadResult.Failure.class);
            Files.move(root.resolve("demo"), root.resolve("old-demo"));
            Files.createSymbolicLink(root.resolve("demo"), outside);
            assertThat(library.read("skill://demo/guide.md")).isInstanceOf(SkillReadResult.Failure.class);
        }
    }

    @Test
    void handles_folded_yaml_and_exact_byte_limit() throws Exception {
        Path definition = definition("demo", ">-\n  folded\n  description", "A");
        int size = Files.readAllBytes(definition).length;
        try (FileSystemSkillLibrary library = library(size)) {
            assertThat(library.metadata().getFirst().description()).isEqualTo("folded description");
            assertThat(library.read("skill://demo")).isInstanceOf(SkillReadResult.Success.class);
            Files.writeString(root.resolve("demo/reference"), "x".repeat(size + 1));
            assertThat(library.read("skill://demo/reference")).isEqualTo(new SkillReadResult.Failure("SKILL_TOO_LARGE"));
            Files.write(root.resolve("demo/reference"), new byte[]{(byte) 0xc3, 0x28});
            assertThat(library.read("skill://demo/reference")).isEqualTo(new SkillReadResult.Failure("SKILL_INVALID_CONTENT"));
            Files.writeString(root.resolve("demo/reference"), "nul\u0000");
            assertThat(library.read("skill://demo/reference")).isInstanceOf(SkillReadResult.Failure.class);
        }
    }

    @Test
    void rejects_unsafe_uris_and_changed_identity() throws Exception {
        Path definition = definition("demo", "first", "A");
        try (FileSystemSkillLibrary library = library(65536)) {
            for (String uri : List.of("skill://demo/../secret", "skill://demo/./file", "skill://demo//file", "skill://demo/%2e", "skill://demo/a\\b", "skill://demo?a", "skill://demo#x", "skill://demo/", "file:///tmp/x")) {
                assertThat(library.read(uri)).as(uri).isInstanceOf(SkillReadResult.Failure.class);
            }
            Files.writeString(definition, text("other", "first", "A"));
            assertThat(library.read("skill://demo")).isEqualTo(new SkillReadResult.Failure("SKILL_INVALID_CONTENT"));
        }
    }

    @Test
    void rejects_invalid_startup_definitions_and_roots() throws Exception {
        Path definition = definition("demo", "first", "A");
        for (String yaml : List.of("name: demo\nname: demo\ndescription: duplicate", "name: demo\ndescription: &a alias\nextra: *a", "name: demo\ndescription: !!java.lang.String unsafe", "name: demo\ndescription: [list]", "name: other\ndescription: wrong", "name: demo\ndescription: " + "x".repeat(1025))) {
            Files.writeString(definition, "---\n" + yaml + "\n---\nbody");
            assertThatThrownBy(() -> library(65536)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new FileSystemSkillLibrary(new SkillProperties(List.of(root.resolve("missing")), 65536, 128))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(definition, text("demo", "ok", "A"));
        assertThatThrownBy(() -> new FileSystemSkillLibrary(new SkillProperties(List.of(root, root), 65536, 128))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> library(10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discovers_only_direct_children_enforces_count_and_rejects_nonregular_targets() throws Exception {
        definition("demo", "ok", "body");
        Path nested = Files.createDirectories(root.resolve("container/nested"));
        Files.writeString(nested.resolve("SKILL.md"), text("nested", "hidden", "body"));
        try (FileSystemSkillLibrary library = library(65536)) {
            assertThat(library.metadata()).extracting(entry -> entry.name()).containsExactly("demo");
            Files.createDirectory(root.resolve("demo/directory"));
            assertThat(library.read("skill://demo/directory")).isInstanceOf(SkillReadResult.Failure.class);
            Files.createSymbolicLink(root.resolve("demo/link"), root.resolve("demo/SKILL.md"));
            assertThat(library.read("skill://demo/link")).isInstanceOf(SkillReadResult.Failure.class);
        }
        definition("second", "ok", "body");
        assertThatThrownBy(() -> new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fails_closed_for_symbolic_roots_definition_links_and_unsupported_filesystems() throws Exception {
        Path definition = definition("demo", "ok", "body");
        Path actual = Files.move(definition, root.resolve("actual"));
        Files.createSymbolicLink(definition, actual);
        assertThatThrownBy(() -> library(65536)).isInstanceOf(IllegalArgumentException.class);
        Path symbolicRoot = Files.createSymbolicLink(root.resolve("linked-root"), root.resolve("demo"));
        assertThatThrownBy(() -> new FileSystemSkillLibrary(new SkillProperties(List.of(symbolicRoot), 65536, 128)))
                .isInstanceOf(IllegalArgumentException.class);
        URI archive = URI.create("jar:" + root.resolve("unsupported.zip").toUri());
        try (FileSystem filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            assertThatThrownBy(() -> new FileSystemSkillLibrary(new SkillProperties(List.of(filesystem.getPath("/")), 65536, 128)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void close_disables_reads_without_retaining_any_filesystem_handles() throws Exception {
        definition("demo", "ok", "body");
        FileSystemSkillLibrary library = library(65536);
        library.close();
        library.close();
        assertThat(library.read("skill://demo")).isEqualTo(new SkillReadResult.Failure("SKILL_UNAVAILABLE"));
    }

    private Path definition(String name, String description, String body) throws Exception {
        Path directory = Files.createDirectories(root.resolve(name));
        return Files.writeString(directory.resolve("SKILL.md"), text(name, description, body));
    }

    private String text(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
    }

    private FileSystemSkillLibrary library(int limit) {
        return new FileSystemSkillLibrary(new SkillProperties(List.of(root), limit, 128));
    }

    private SkillResource success(SkillReadResult result) {
        assertThat(result).isInstanceOf(SkillReadResult.Success.class);
        return ((SkillReadResult.Success) result).resource();
    }

    private String hash(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
