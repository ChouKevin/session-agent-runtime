package com.java.system.sessionagent.tool.port;

import java.util.Set;
import java.util.Objects;
import org.springframework.util.Assert;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Trusted skill-read outcome awaiting atomic association with its history event. */
public record SkillReadReceipt(
        UUID attemptId,
        String skillName,
        String resourceUri,
        ResourceKind resourceKind,
        Outcome outcome,
        Optional<String> sha256,
        Optional<String> errorCode) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Resource UNKNOWN = new Resource("unknown", "skill://unknown", ResourceKind.DEFINITION);
    private static final Set<String> SAFE_CODES = Set.of("SKILL_NOT_FOUND", "SKILL_INVALID_URI", "SKILL_INVALID_ARGUMENTS",
            "SKILL_TOO_LARGE", "SKILL_INVALID_CONTENT", "SKILL_UNAVAILABLE");

    public SkillReadReceipt {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(resourceKind);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(errorCode);
        Objects.requireNonNull(resourceUri);
        Assert.isTrue(Objects.nonNull(skillName) && skillName.length() <= 64 && NAME.matcher(skillName).matches(),
                "Receipt requires a safe skill name");
        Assert.isTrue(outcome == Outcome.SUCCEEDED ? sha256.isPresent() && errorCode.isEmpty()
                : sha256.isEmpty() && errorCode.isPresent(), "Receipt outcome metadata must agree");
    }

    public static SkillReadReceipt succeeded(UUID attemptId, String requestedUri, String actualUri, String sha256) {
        Resource resource = resource(requestedUri);
        Assert.isTrue(resource(actualUri).equals(resource), "Read resource must match requested resource");
        return new SkillReadReceipt(attemptId, resource.name(), resource.uri(), resource.kind(),
                Outcome.SUCCEEDED, Optional.of(sha256), Optional.empty());
    }

    public static SkillReadReceipt failed(UUID attemptId, String uri, String code) {
        Resource resource = resource(uri);
        String safeCode = SAFE_CODES.contains(code) ? code : "SKILL_UNAVAILABLE";
        return new SkillReadReceipt(attemptId, resource.name(), resource.uri(), resource.kind(),
                Outcome.FAILED, Optional.empty(), Optional.of(safeCode));
    }

    public static Resource resource(String uri) {
        if (Objects.isNull(uri) || !uri.startsWith("skill://")) {
            return UNKNOWN;
        }
        String[] segments = uri.substring(8).split("/", -1);
        if (segments[0].length() > 64 || !NAME.matcher(segments[0]).matches()) {
            return UNKNOWN;
        }
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(character -> character <= 32 || character == 127
                    || "\\%?#:".indexOf(character) >= 0)) {
                return UNKNOWN;
            }
        }
        boolean definition = segments.length == 1 || (segments.length == 2 && segments[1].equals("SKILL.md"));
        return new Resource(segments[0], definition ? "skill://" + segments[0] : uri,
                definition ? ResourceKind.DEFINITION : ResourceKind.REFERENCE);
    }

    public record Resource(String name, String uri, ResourceKind kind) { }

    public enum ResourceKind { DEFINITION, REFERENCE }
    public enum Outcome { SUCCEEDED, FAILED }
}
