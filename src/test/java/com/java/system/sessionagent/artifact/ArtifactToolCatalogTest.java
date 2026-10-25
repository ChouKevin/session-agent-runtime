package com.java.system.sessionagent.artifact;

import com.java.system.sessionagent.artifact.application.ArtifactToolCatalog;
import com.java.system.sessionagent.tool.port.ArtifactReceipt;
import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.CompositeToolCatalog;
import java.util.Set;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static com.java.system.sessionagent.tool.TestToolInvocation.invocation;
import static org.assertj.core.api.Assertions.assertThat;

class ArtifactToolCatalogTest {
    @Test
    void exposes_creation_only_on_eligible_normal_job_snapshots() {
        RecordingStore store = new RecordingStore();
        ToolInvocation invocation = invocation();
        ToolCatalog base = () -> new ToolSnapshot(List.of());
        ArtifactToolCatalog enabled = new ArtifactToolCatalog(base, store, (session, job) -> true);
        ArtifactToolCatalog disabled = new ArtifactToolCatalog(base, store, (session, job) -> false);
        try (ToolSnapshot ordinary = enabled.snapshot(invocation.context().sessionId().toString(), invocation.context().messageJobId().toString());
             ToolSnapshot query = enabled.snapshot();
             ToolSnapshot unavailable = disabled.snapshot(invocation.context().sessionId().toString(), invocation.context().messageJobId().toString())) {
            assertThat(ordinary.definitions()).extracting(definition -> definition.name().value())
                    .containsExactly("createMarkdownReport", "createCsv");
            assertThat(query.definitions()).isEmpty();
            assertThat(unavailable.definitions()).isEmpty();
        }
    }

    @Test
    void saves_exact_success_with_internal_receipt_not_model_visible_authority_and_rejects_bad_input_without_store() {
        RecordingStore store = new RecordingStore();
        ArtifactToolCatalog catalog = new ArtifactToolCatalog(() -> new ToolSnapshot(List.of()), store, (session, job) -> true);
        try (ToolSnapshot snapshot = catalog.snapshot(invocation().context().sessionId().toString(), invocation().context().messageJobId().toString())) {
            ToolOutput malformed = snapshot.invoke(invocation(), new ToolName("createCsv"),
                    Map.of("title", "x", "columns", List.of("a", "b"), "rows", List.of(List.of("one"))));
            assertThat(malformed.isError()).isTrue();
            assertThat(store.calls).hasValue(0);
            ToolOutput saved = snapshot.invoke(invocation(), new ToolName("createMarkdownReport"), Map.of("title", "../report", "content", "# 報告😀\r\n"));
            assertThat(saved.isError()).isFalse();
            assertThat(store.calls).hasValue(1);
            assertThat(store.content).isEqualTo("# 報告😀\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(saved.artifactReceipt()).isPresent();
            assertThat(saved.asStructuredValue()).containsOnlyKeys("isError", "result");
            assertThat(new ToolOutput(false, saved.result()).artifactReceipt()).isEmpty();
        }
    }

    @Test
    void only_first_party_generation_remains_invokable_through_catalog_wrappers_in_report_only_mode() {
        RecordingStore store = new RecordingStore();
        AtomicInteger sourceCalls = new AtomicInteger();
        ToolBinding source = new ToolBinding(ToolDefinition.fromExposedName("createCsvLookalike", "Opaque external tool", Map.of()),
                (context, arguments) -> {
                    sourceCalls.incrementAndGet();
                    return new ToolOutput(false, Map.of());
                }, ToolCapability.ORDINARY);
        ToolCatalog base = new CompositeToolCatalog(List.of(() -> new ToolSnapshot(List.of(source))));
        ArtifactToolCatalog catalog = new ArtifactToolCatalog(base, store, (session, job) -> true);
        try (ToolSnapshot snapshot = catalog.snapshot(invocation().context().sessionId().toString(),
                invocation().context().messageJobId().toString()).restrictedTo(Set.of(ToolCapability.REPORT_GENERATION))) {
            assertThat(snapshot.definitions()).extracting(definition -> definition.name().value())
                    .containsExactly("createMarkdownReport", "createCsv");
            assertThat(snapshot.invoke(invocation(), new ToolName("createCsvLookalike"), Map.of()).isError()).isTrue();
            assertThat(snapshot.invoke(invocation(), new ToolName("createCsv"),
                    Map.of("title", "Evidence", "columns", List.of("status"), "rows", List.of(List.of("unknown")))).isError()).isFalse();
            assertThat(sourceCalls).hasValue(0);
            assertThat(store.calls).hasValue(1);
        }
    }

    private static final class RecordingStore implements ArtifactStore {
        private final AtomicInteger calls = new AtomicInteger();
        private byte[] content;
        @Override
        public Saved create(ToolInvocation invocation, String title, String extension, String mediaType,
                            byte[] bytes, String toolName, Map<String, Object> arguments, Map<String, Object> disclosures) {
            calls.incrementAndGet();
            content = bytes;
            UUID id = UUID.randomUUID();
            return new Saved(new ArtifactReceipt(id, UUID.randomUUID(), invocation.context()),
                    Map.of("artifactId", id.toString(), "status", "SAVED_PENDING_DELIVERY"));
        }
        @Override
        public List<Artifact> released(UUID sessionId, UUID jobId) {
            return List.of();
        }
    }
}
