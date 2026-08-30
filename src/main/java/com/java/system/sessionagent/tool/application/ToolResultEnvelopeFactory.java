package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.springframework.util.Assert;

import java.util.Objects;

public final class ToolResultEnvelopeFactory {

    private final StrictJsonCodec jsonCodec;

    public ToolResultEnvelopeFactory() {
        this(new StrictJsonCodec());
    }

    ToolResultEnvelopeFactory(StrictJsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "JSON codec must not be null");
    }

    public ValidatedResult validate(ToolExecution execution) {
        ToolExecution requiredExecution = Objects.requireNonNull(execution, "Tool execution must not be null");
        try {
            return new ValidatedResult(requiredExecution, jsonCodec.decode(requiredExecution.dataJson(), Object.class));
        } catch (JsonContractException exception) {
            throw ToolExecutionFailure.invalidResponse();
        }
    }

    public String envelope(String resultId, ValidatedResult validatedResult) {
        Assert.hasText(resultId, "Result ID must not be blank");
        ValidatedResult requiredResult = Objects.requireNonNull(validatedResult, "Validated result must not be null");
        ToolExecution execution = requiredResult.execution();
        return switch (execution.kind()) {
            case CATALOG -> jsonCodec.canonicalize(new CatalogEnvelope(
                    resultId, execution.name().value(), requiredResult.data()));
            case SOURCE -> jsonCodec.canonicalize(new SourceEnvelope(
                    resultId, execution.name().value(), execution.repositoryId().orElseThrow(),
                    execution.revision().orElseThrow(), requiredResult.data()));
        };
    }

    public record ValidatedResult(ToolExecution execution, Object data) {
        public ValidatedResult {
            Objects.requireNonNull(execution, "Tool execution must not be null");
            Objects.requireNonNull(data, "Tool result data must not be null");
        }
    }

    private record CatalogEnvelope(String resultId, String toolName, Object data) {
    }

    private record SourceEnvelope(String resultId, String toolName, String repositoryId, String revision, Object data) {
    }
}
