package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfiguredModels implements ConversationModels, AutoCloseable {
    private final Map<String, ConfiguredModelBinding> bindings;
    private final String defaultModel;
    private final List<AutoCloseable> ownedResources;

    public ConfiguredModels(Map<String, ConfiguredModelBinding> bindings, String defaultModel, List<AutoCloseable> ownedResources) {
        Assert.notEmpty(bindings, "Configured model bindings must not be empty");
        Assert.hasText(defaultModel, "Default model key must not be blank");
        Assert.isTrue(bindings.containsKey(defaultModel), "Default model key must name a configured binding");
        Assert.notNull(ownedResources, "Configured model resources must not be null");
        this.bindings = Map.copyOf(new LinkedHashMap<>(bindings));
        this.defaultModel = defaultModel;
        this.ownedResources = List.copyOf(ownedResources);
    }

    public Map<String, ConfiguredModelBinding> bindings() { return bindings; }

    public ConfiguredModelBinding defaultBinding() { return bindings.get(defaultModel); }

    public ConversationModel defaultConversationModel() { return defaultBinding().conversationModel(); }

    @Override
    public ModelSelection defaultSelection() {
        return selection(defaultBinding());
    }

    @Override
    public java.util.Optional<ModelSelection> findSelection(String model) {
        return java.util.Optional.ofNullable(bindings.get(model)).map(this::selection);
    }

    @Override
    public java.util.Optional<ConversationModel> resolve(ModelSelection selection) {
        return java.util.Optional.ofNullable(bindings.get(selection.model()))
                .filter(binding -> binding.fingerprint().equals(selection.fingerprint()))
                .map(ConfiguredModelBinding::conversationModel);
    }

    private ModelSelection selection(ConfiguredModelBinding binding) {
        return new ModelSelection(binding.model(), binding.fingerprint());
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (AutoCloseable resource : ownedResources) {
            try { resource.close(); } catch (Exception exception) {
                if (failure == null) { // cs-allow first close failure has not been assigned
                    failure = exception;
                } else { failure.addSuppressed(exception); }
            }
        }
        if (failure != null) { // cs-allow resource close failure is optional
            throw failure;
        }
    }
}
