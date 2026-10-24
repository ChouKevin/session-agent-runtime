package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.mcp.adapter.sdk.McpConnectionManager;
import com.java.system.sessionagent.mcp.adapter.sdk.McpToolCatalog;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.adapter.tool.SkillToolCatalog;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.tool.port.CompositeToolCatalog;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfiguration {
    @Bean(destroyMethod = "close")
    SkillLibrary skillLibrary(SkillProperties properties) {
        return new FileSystemSkillLibrary(properties);
    }

    @Bean
    ToolCatalog toolCatalog(SkillLibrary library, McpConnectionManager manager, ObjectMapper objectMapper) {
        return new CompositeToolCatalog(List.of(new McpToolCatalog(manager, objectMapper), new SkillToolCatalog(library)));
    }
}
