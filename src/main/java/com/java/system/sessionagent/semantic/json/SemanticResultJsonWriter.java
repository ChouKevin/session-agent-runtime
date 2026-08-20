package com.java.system.sessionagent.semantic.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import java.util.List;

public final class SemanticResultJsonWriter {

    private static final String PROVIDER_CONTROL_FIELD = "availableFollowUps";
    private final JsonMapper mapper;

    public SemanticResultJsonWriter() {
        SimpleModule module = new SimpleModule("semantic-result-control-filter");
        module.setSerializerModifier(new ProviderControlFieldFilter());
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
                .addModule(module)
                .build();
    }

    public <T> String write(T response) {
        Assert.notNull(response, "Semantic response must not be null");
        try {
            return mapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Semantic response cannot be serialized", exception);
        }
    }

    private static final class ProviderControlFieldFilter extends ValueSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(
                SerializationConfig config,
                BeanDescription.Supplier bean,
                List<BeanPropertyWriter> properties) {
            return properties.stream()
                    .filter(property -> !PROVIDER_CONTROL_FIELD.equals(property.getName()))
                    .toList();
        }
    }
}
