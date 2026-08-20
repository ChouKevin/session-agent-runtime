package com.java.system.sessionagent.tool.json;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import org.springframework.util.Assert;

public final class ToolSchemaFactory {

    private final SchemaGenerator schemaGenerator;

    public ToolSchemaFactory() {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(
                        JacksonOption.RESPECT_JSONPROPERTY_ORDER,
                        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED))
                .with(new JakartaValidationModule())
                .without(Option.FLATTENED_OPTIONALS)
                .with(Option.SIMPLIFIED_OPTIONALS)
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                .with(Option.INLINE_ALL_SCHEMAS);
        SchemaGeneratorConfig config = builder.build();
        this.schemaGenerator = new SchemaGenerator(config);
    }

    public String schemaFor(Class<?> inputType) {
        Assert.notNull(inputType, "Tool input type must not be null");
        return schemaGenerator.generateSchema(inputType).toString();
    }
}
