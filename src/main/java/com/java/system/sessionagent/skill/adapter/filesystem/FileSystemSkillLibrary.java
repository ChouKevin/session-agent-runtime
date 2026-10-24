package com.java.system.sessionagent.skill.adapter.filesystem;

import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Current-file library for operator-managed local roots. Requires a filesystem provider
 * supporting SecureDirectoryStream (Linux local filesystems); unsupported providers fail
 * closed. All path components, including configured root ancestors, are opened relative
 * to directory handles without following links. No file bodies or open handles are cached.
 */
public final class FileSystemSkillLibrary implements SkillLibrary {
    private final SkillProperties properties;
    private final Map<String, Location> locations;
    private final List<SkillMetadata> metadata;
    private boolean closed;

    public FileSystemSkillLibrary(SkillProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        LinkedHashMap<String, Location> discovered = new LinkedHashMap<>();
        ArrayList<SkillMetadata> entries = new ArrayList<>();
        try {
            for (Path configured : properties.roots()) {
                Path root = configured.toAbsolutePath();
                if (!root.equals(root.normalize())) {
                    throw new IOException("Invalid root");
                }
                try (SecureDirectoryStream<Path> directory = openRoot(root)) {
                    for (Path child : directory) {
                        Path name = child.getFileName();
                        BasicFileAttributes attributes = attributes(directory, name);
                        if (attributes.isSymbolicLink()) {
                            throw new IOException("Symbolic skill entry");
                        }
                        if (!attributes.isDirectory()) {
                            continue;
                        }
                        try (SecureDirectoryStream<Path> skill = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                            try {
                                attributes(skill, Path.of("SKILL.md"));
                            } catch (java.nio.file.NoSuchFileException missing) {
                                continue;
                            }
                            String skillName = name.toString();
                            SkillMetadata entry = parseDefinition(decode(readFile(skill, Path.of("SKILL.md"))), skillName);
                            if (discovered.putIfAbsent(skillName, new Location(root, skillName)) instanceof Location) {
                                throw new IOException("Duplicate skill");
                            }
                            entries.add(entry);
                            if (entries.size() > properties.maxSkills()) {
                                throw new IOException("Too many skills");
                            }
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid skill roots or definitions", exception);
        }
        locations = Map.copyOf(discovered);
        metadata = entries.stream().sorted(Comparator.comparing(SkillMetadata::name)).toList();
    }

    @Override
    public List<SkillMetadata> metadata() {
        return metadata;
    }

    @Override
    public synchronized SkillReadResult read(String uri) {
        if (closed) {
            return new SkillReadResult.Failure("SKILL_UNAVAILABLE");
        }
        if (Objects.isNull(uri) || !uri.startsWith("skill://")) {
            return new SkillReadResult.Failure("SKILL_INVALID_URI");
        }
        String[] segments = uri.substring(8).split("/", -1);
        if (!validName(segments[0])) {
            return new SkillReadResult.Failure("SKILL_INVALID_URI");
        }
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(character -> character <= 32 || character == 127
                    || "\\%?#:".indexOf(character) >= 0)) {
                return new SkillReadResult.Failure("SKILL_INVALID_URI");
            }
        }
        Location location = locations.get(segments[0]);
        if (Objects.isNull(location)) {
            return new SkillReadResult.Failure("SKILL_NOT_FOUND");
        }
        List<String> relative = segments.length == 1 ? List.of("SKILL.md")
                : List.of(segments).subList(1, segments.length);
        boolean definition = relative.equals(List.of("SKILL.md"));
        try (SecureDirectoryStream<Path> root = openRoot(location.root());
             SecureDirectoryStream<Path> skill = root.newDirectoryStream(Path.of(location.name()), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = readRelative(skill, relative, 0);
            String content = decode(bytes);
            if (definition) {
                parseDefinition(content, location.name());
            }
            String canonical = "skill://" + location.name() + (definition ? "" : "/" + String.join("/", relative));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new SkillReadResult.Success(new SkillResource(canonical, hash, content));
        } catch (TooLargeException exception) {
            return new SkillReadResult.Failure("SKILL_TOO_LARGE");
        } catch (CharacterCodingException | IllegalArgumentException exception) {
            return new SkillReadResult.Failure("SKILL_INVALID_CONTENT");
        } catch (IOException | SecurityException exception) {
            return new SkillReadResult.Failure("SKILL_UNAVAILABLE");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private byte[] readRelative(SecureDirectoryStream<Path> directory, List<String> segments, int index) throws IOException {
        Path name = Path.of(segments.get(index));
        if (index == segments.size() - 1) {
            return readFile(directory, name);
        }
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            return readRelative(child, segments, index + 1);
        }
    }

    private byte[] readFile(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributes before = attributes(directory, name);
        if (!before.isRegularFile()) {
            throw new IOException("Not a regular file");
        }
        try (SeekableByteChannel channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            BasicFileAttributes after = attributes(directory, name);
            if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IOException("File replaced during open");
            }
            byte[] bytes = input.readNBytes(properties.maxFileBytes() + 1);
            if (bytes.length > properties.maxFileBytes()) {
                throw new TooLargeException();
            }
            return bytes;
        }
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path path) throws IOException {
        return directory.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
    }

    private static SecureDirectoryStream<Path> openRoot(Path root) throws IOException {
        SecureDirectoryStream<Path> current = secure(Files.newDirectoryStream(root.getRoot()));
        try {
            for (Path component : root) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                try {
                    current.close();
                } catch (IOException exception) {
                    next.close();
                    throw exception;
                }
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException exception) {
            try {
                current.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static SecureDirectoryStream<Path> secure(DirectoryStream<Path> directory) throws IOException {
        if (directory instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        directory.close();
        throw new IOException("Secure directory traversal unavailable");
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL text");
        }
        return content;
    }

    private SkillMetadata parseDefinition(String content, String expectedName) {
        String[] lines = content.split("\n", -1);
        if (!lines[0].equals("---") && !lines[0].equals("---\r")) {
            throw new IllegalArgumentException("Missing frontmatter");
        }
        StringBuilder header = new StringBuilder();
        int index = 1;
        for (; index < lines.length; index++) {
            if (lines[index].equals("---") || lines[index].equals("---\r")) {
                break;
            }
            header.append(lines[index]).append('\n');
        }
        if (index == lines.length) {
            throw new IllegalArgumentException("Unclosed frontmatter");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(properties.maxFileBytes());
        options.setNestingDepthLimit(20);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            for (Event event : yaml.parse(new StringReader(header.toString()))) {
                if (event instanceof AliasEvent || event instanceof NodeEvent node && Objects.nonNull(node.getAnchor())) {
                    throw new IllegalArgumentException("YAML aliases and anchors are forbidden");
                }
            }
            Object loaded = yaml.load(header.toString());
            if (!(loaded instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)
                    || !(map.get("description") instanceof String description) || !validName(name)
                    || !name.equals(expectedName) || description.isBlank() || description.length() > 1024) {
                throw new IllegalArgumentException("Invalid skill metadata");
            }
            return new SkillMetadata(name, description, "skill://" + name);
        } catch (org.yaml.snakeyaml.error.YAMLException exception) {
            throw new IllegalArgumentException("Invalid YAML", exception);
        }
    }

    private static boolean validName(String name) {
        return name.length() <= 64 && name.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private record Location(Path root, String name) { }
    private static final class TooLargeException extends IOException { }
}
