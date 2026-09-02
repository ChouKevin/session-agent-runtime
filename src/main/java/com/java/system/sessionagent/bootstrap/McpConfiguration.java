package com.java.system.sessionagent.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.mcp.McpConnectionManager;
import com.java.system.sessionagent.mcp.McpConnectionProperties;
import com.java.system.sessionagent.mcp.McpConnectionsEndpoint;
import com.java.system.sessionagent.mcp.McpToolCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpConnectionProperties.class)
public class McpConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    McpConnectionManager mcpConnectionManager(
            McpConnectionProperties properties,
            TaskScheduler taskScheduler,
            Clock runtimeClock,
            ObjectMapper objectMapper) {
        return new McpConnectionManager(properties, taskScheduler, runtimeClock, objectMapper);
    }

    @Bean
    McpToolCatalog mcpToolCatalog(McpConnectionManager connectionManager, ObjectMapper objectMapper) {
        return new McpToolCatalog(connectionManager, objectMapper);
    }

    @Bean
    McpConnectionsEndpoint mcpConnectionsEndpoint(McpConnectionManager connectionManager) {
        return new McpConnectionsEndpoint(connectionManager);
    }
}
