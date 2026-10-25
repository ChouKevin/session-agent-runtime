package com.java.system.sessionagent.artifact.application;

import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.artifact.port.AttachmentRoute;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Adds first-party durable export operations only to supported job routes. */
public final class ArtifactToolCatalog implements ToolCatalog {
    private final ToolCatalog delegate;
    private final ArtifactStore store;
    private final AttachmentRoute route;

    public ArtifactToolCatalog(ToolCatalog delegate, ArtifactStore store, AttachmentRoute route) {
        this.delegate = delegate;
        this.store = store;
        this.route = route;
    }

    @Override
    public ToolSnapshot snapshot() {
        return delegate.snapshot();
    }

    @Override
    public ToolSnapshot snapshot(String sessionId, String messageJobId) {
        ToolSnapshot child = delegate.snapshot(sessionId, messageJobId);
        try {
            if (!route.eligible(UUID.fromString(sessionId), UUID.fromString(messageJobId))) {
                return child;
            }
            ArrayList<ToolBinding> bindings = new ArrayList<>();
            bindings.addAll(child.bindings());
            Map<String, Object> title = Map.of("type", "string", "maxLength", 120);
            bindings.add(new ToolBinding(ToolDefinition.fromExposedName("createMarkdownReport",
                    "Save a complete Markdown report for delivery as an attachment. Saved means pending delivery, not delivered. Ordinary Markdown replies need no tool.",
                    Map.of("type", "object", "properties", Map.of("title", title, "content", Map.of("type", "string")),
                            "required", List.of("title", "content"), "additionalProperties", false)),
                    (invocation, arguments) -> create(invocation, arguments, false), ToolCapability.REPORT_GENERATION));
            Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));
            bindings.add(new ToolBinding(ToolDefinition.fromExposedName("createCsv",
                    "Save a rectangular string CSV for attachment delivery. UTF-8 BOM, CRLF, quoted fields; formula-leading cells are apostrophe-prefixed. No type inference. Saved means pending delivery.",
                    Map.of("type", "object", "properties", Map.of("title", title,
                                    "columns", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", Map.of("type", "string")),
                                    "rows", Map.of("type", "array", "maxItems", 10000, "items", stringArray)),
                            "required", List.of("title", "columns", "rows"), "additionalProperties", false)),
                    (invocation, arguments) -> create(invocation, arguments, true), ToolCapability.REPORT_GENERATION));
            return new ToolSnapshot(bindings, child::close);
        } catch (RuntimeException | Error exception) {
            child.close();
            throw exception;
        }
    }

    private ToolOutput create(ToolInvocation invocation, Map<String, Object> arguments, boolean csv) {
        try {
            if (!route.eligible(invocation.context().sessionId(), invocation.context().messageJobId())) {
                return ToolOutput.runtimeFailure("TOOL_NOT_AVAILABLE", "Artifact delivery is not configured for this job.");
            }
            ArtifactEncoder.Encoded encoded = csv ? ArtifactEncoder.csv(arguments) : ArtifactEncoder.markdown(arguments);
            ArtifactStore.Saved saved = store.create(invocation, encoded.title(), encoded.extension(), encoded.mediaType(),
                    encoded.content(), csv ? "createCsv" : "createMarkdownReport", arguments, encoded.disclosures());
            return new ToolOutput(false, saved.result(), Optional.of(saved.receipt()));
        } catch (IllegalArgumentException exception) {
            return new ToolOutput(true, Map.of("code", "ARTIFACT_INPUT_INVALID", "message", "Artifact input or invocation exceeds the supported export contract."));
        }
    }
}
