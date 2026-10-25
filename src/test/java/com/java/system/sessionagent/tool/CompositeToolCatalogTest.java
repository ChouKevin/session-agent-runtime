package com.java.system.sessionagent.tool;

import static com.java.system.sessionagent.tool.TestToolInvocation.invocation;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.CompositeToolCatalog;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import java.util.Set;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CompositeToolCatalogTest {
    @TempDir Path root;

    @Test
    void retains_real_resource_until_parent_close() throws Exception {
        Path file = Files.writeString(root.resolve("resource"), "retained");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        ToolCatalog child = () -> new ToolSnapshot(List.of(new ToolBinding(definition("read"), (context, arguments) -> {
            try {
                ByteBuffer bytes = ByteBuffer.allocate(8);
                channel.read(bytes, 0);
                return new ToolOutput(false, new String(bytes.array(), StandardCharsets.UTF_8));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }, ToolCapability.ORDINARY)), () -> {
            try { channel.close(); } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        });
        ToolSnapshot parent = new CompositeToolCatalog(List.of(child)).snapshot();
        try {
            assertThat(channel.isOpen()).isTrue();
            assertThat(parent.invoke(invocation(), new ToolName("read"), Map.of()).result()).isEqualTo("retained");
        } finally {
            parent.close();
        }
        assertThat(channel.isOpen()).isFalse();
        parent.close();
    }

    @Test
    void releases_all_acquired_children_on_duplicate_or_acquisition_failure_even_when_close_throws() {
        AtomicInteger releases = new AtomicInteger();
        ToolCatalog first = () -> snapshot("duplicate", () -> { releases.incrementAndGet(); throw new IllegalStateException("close failed"); });
        ToolCatalog second = () -> snapshot("duplicate", releases::incrementAndGet);
        assertThatThrownBy(() -> new CompositeToolCatalog(List.of(first, second)).snapshot())
                .isInstanceOf(IllegalArgumentException.class).hasSuppressedException(new IllegalStateException("close failed"));
        assertThat(releases).hasValue(2);
        ToolCatalog failing = () -> { throw new IllegalArgumentException("acquisition failed"); };
        assertThatThrownBy(() -> new CompositeToolCatalog(List.of(first, failing)).snapshot())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("acquisition failed");
        assertThat(releases).hasValue(3);
    }

    @Test
    void attempts_every_close_and_releases_once() {
        AtomicInteger releases = new AtomicInteger();
        ToolSnapshot parent = new CompositeToolCatalog(List.of(
                () -> snapshot("first", () -> { releases.incrementAndGet(); throw new IllegalStateException("close failed"); }),
                () -> snapshot("second", releases::incrementAndGet))).snapshot();
        assertThatThrownBy(parent::close).isInstanceOf(IllegalStateException.class);
        parent.close();
        assertThat(releases).hasValue(2);
    }

    private ToolDefinition definition(String name) {
        return ToolDefinition.fromExposedName(name, name, Map.of("type", "object"));
    }

    @Test
    void report_capability_survives_composition_and_filtering_without_reclassifying_names() {
        AtomicInteger releases = new AtomicInteger();
        ToolBinding report = new ToolBinding(definition("opaque"), (context, arguments) -> new ToolOutput(false, arguments),
                ToolCapability.REPORT_GENERATION);
        CompositeToolCatalog catalog = new CompositeToolCatalog(List.of(
                () -> new ToolSnapshot(List.of(report), releases::incrementAndGet),
                () -> snapshot("ordinary", releases::incrementAndGet)));
        ToolSnapshot filtered = catalog.snapshot().restrictedTo(Set.of(ToolCapability.REPORT_GENERATION));
        assertThat(filtered.definitions()).containsExactly(report.definition());
        assertThat(filtered.invoke(invocation(), new ToolName("ordinary"), Map.of()).isError()).isTrue();
        assertThat(filtered.invoke(invocation(), new ToolName("opaque"), Map.of()).isError()).isFalse();
        filtered.close();
        filtered.close();
        assertThat(releases).hasValue(2);
    }

    private ToolSnapshot snapshot(String name, Runnable close) {
        return new ToolSnapshot(List.of(new ToolBinding(definition(name), (context, arguments) -> new ToolOutput(false, arguments), ToolCapability.ORDINARY)), close);
    }
}
