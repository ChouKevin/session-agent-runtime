package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.MethodTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

public record DiscoverTypeMembersInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                       @JsonProperty(required = true) @Valid MethodTarget.SourceType sourceType,
                                       @JsonProperty(required = true) @Size(min = 1) List<MemberKind> memberKinds,
                                       @JsonProperty(required = false) String namePrefix,
                                       @JsonProperty(required = false) @Min(0) Integer offset,
                                       @JsonProperty(required = false) @Min(1) @Max(100) Integer limit) {
    public DiscoverTypeMembersInput { Objects.requireNonNull(sourceType, "Source type must not be null"); memberKinds = SemanticInputRules.distinct(memberKinds, "Member kinds"); namePrefix = SemanticInputRules.optionalText(namePrefix, "Name prefix"); offset = Objects.requireNonNullElse(offset, 0); limit = Objects.requireNonNullElse(limit, 50); }
}
