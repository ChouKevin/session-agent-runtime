package com.java.system.sessionagent.semantic.tool.input;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.semantic.dto.ProviderDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.Assert;

public record ResolveSourceSymbolInput(@JsonProperty(required = true) @NotBlank @Size(max = 128) String repositoryId,
                                       @JsonProperty(required = true) @NotBlank String symbol,
                                       @JsonProperty(required = true) @Valid ProviderDtos.SourceSymbolContextPayload context,
                                       @JsonProperty(required = false) @Valid ProviderDtos.Position position) {
    public ResolveSourceSymbolInput { symbol = SemanticInputRules.text(symbol, "Source symbol"); Assert.notNull(context, "Source context must not be null"); }
}
