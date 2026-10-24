package com.java.system.sessionagent.tool.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns acquired child snapshots until the composed snapshot is closed. */
public final class CompositeToolCatalog implements ToolCatalog {
    private final List<ToolCatalog> catalogs;

    public CompositeToolCatalog(List<ToolCatalog> catalogs) {
        this.catalogs = List.copyOf(catalogs);
    }

    @Override
    public ToolSnapshot snapshot() {
        ArrayList<ToolSnapshot> children = new ArrayList<>();
        ArrayList<ToolBinding> bindings = new ArrayList<>();
        try {
            for (ToolCatalog catalog : catalogs) {
                ToolSnapshot child = Objects.requireNonNull(catalog.snapshot(), "Child snapshot must not be null");
                children.add(child);
                for (ToolDefinition definition : child.definitions()) {
                    bindings.add(new ToolBinding(definition, arguments -> child.invoke(definition.name(), arguments)));
                }
            }
            return new ToolSnapshot(bindings, () -> closeAll(children));
        } catch (RuntimeException | Error failure) {
            try {
                closeAll(children);
            } catch (RuntimeException | Error closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static void closeAll(List<ToolSnapshot> children) {
        Throwable failure = null;
        for (ToolSnapshot child : children) {
            try {
                child.close();
            } catch (RuntimeException | Error closeFailure) {
                if (Objects.isNull(failure)) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
