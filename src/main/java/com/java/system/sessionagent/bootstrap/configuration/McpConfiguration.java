package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.mcp.adapter.actuator.McpConnectionsEndpoint;
import com.java.system.sessionagent.mcp.adapter.sdk.McpConnectionManager;
import com.java.system.sessionagent.mcp.configuration.McpConnectionProperties;
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
    McpConnectionsEndpoint mcpConnectionsEndpoint(McpConnectionManager connectionManager) {
        return new McpConnectionsEndpoint(connectionManager);
    }
}
