package com.java.system.sessionagent.bootstrap;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void configured_appenders_emit_the_same_one_line_safe_json_and_enforce_the_rolling_contract() throws Exception {
        PrintStream previousOutput = System.out;
        ByteArrayOutputStream consoleBytes = new ByteArrayOutputStream();
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            String productionConfiguration;
            try (InputStream configuration = Objects.requireNonNull(
                    getClass().getResourceAsStream("/logback-spring.xml"), "Logback configuration must exist")) {
                productionConfiguration = new String(configuration.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(productionConfiguration)
                    .contains("<file>/app/logs/session-agent-runtime.log</file>")
                    .contains("<fileNamePattern>/app/logs/session-agent-runtime.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>")
                    .doesNotContain("SESSION_AGENT_RUNTIME_LOG_DIR");
            String isolatedConfiguration = productionConfiguration.replace("/app/logs", temporaryDirectory.toString());
            Path isolatedConfigurationFile = temporaryDirectory.resolve("logback-test.xml");
            Files.writeString(isolatedConfigurationFile, isolatedConfiguration, StandardCharsets.UTF_8);
            System.setOut(new PrintStream(consoleBytes, true, StandardCharsets.UTF_8));
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(isolatedConfigurationFile.toFile());

            ch.qos.logback.classic.Logger logger = context.getLogger("logging-contract");
            context.getMDCAdapter().put("unsafeMdc", "forbidden-mdc");
            logger.atInfo()
                    .addKeyValue("event", "slack_delivery_sent")
                    .addKeyValue("sessionId", "session-safe-id")
                    .addKeyValue("messageJobId", "job-safe-id")
                    .addKeyValue("deliveryId", "delivery-safe-id")
                    .addKeyValue("outcome", "SENT")
                    .addArgument("forbidden-argument")
                    .setCause(new IllegalStateException("forbidden-throwable"))
                    .log("runtime_lifecycle");
            context.getMDCAdapter().clear();
            RollingFileAppender<?> rollingFile = (RollingFileAppender<?>) context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    .getAppender("ROLLING_FILE");
            context.stop();

            Path activeFile = temporaryDirectory.resolve("session-agent-runtime.log");
            String consoleLine = onlyLine(consoleBytes.toString(StandardCharsets.UTF_8));
            String fileLine = onlyLine(Files.readString(activeFile, StandardCharsets.UTF_8));
            JsonNode consoleJson = OBJECT_MAPPER.readTree(consoleLine);
            JsonNode fileJson = OBJECT_MAPPER.readTree(fileLine);

            assertThat(consoleJson).isEqualTo(fileJson);
            assertThat(consoleJson.path("level").asText()).isEqualTo("INFO");
            assertThat(consoleJson.path("loggerName").asText()).isEqualTo("logging-contract");
            assertThat(consoleJson.path("message").asText()).isEqualTo("runtime_lifecycle");
            assertThat(consoleJson.path("kvpList").toString()).contains(
                    "slack_delivery_sent", "session-safe-id", "job-safe-id", "delivery-safe-id", "SENT");
            assertThat(consoleLine).doesNotContain("forbidden-argument", "forbidden-throwable", "forbidden-mdc");

            assertThat(rollingFile.getFile()).isEqualTo(activeFile.toString());
            assertThat(rollingFile.getRollingPolicy()).isInstanceOf(SizeAndTimeBasedRollingPolicy.class);
            SizeAndTimeBasedRollingPolicy<?> rollingPolicy = (SizeAndTimeBasedRollingPolicy<?>) rollingFile.getRollingPolicy();
            assertThat(rollingPolicy.getFileNamePattern()).isEqualTo(
                    temporaryDirectory.resolve("session-agent-runtime.%d{yyyy-MM-dd}.%i.log.gz").toString());
            FileSize maxFileSize = (FileSize) ReflectionTestUtils.getField(rollingPolicy, "maxFileSize");
            FileSize totalSizeCap = (FileSize) ReflectionTestUtils.getField(rollingPolicy, "totalSizeCap");
            assertThat(maxFileSize).isNotNull();
            assertThat(maxFileSize.getSize()).isEqualTo(100L * 1024L * 1024L);
            assertThat(rollingPolicy.getMaxHistory()).isEqualTo(7);
            assertThat(totalSizeCap).isNotNull();
            assertThat(totalSizeCap.getSize()).isEqualTo(500L * 1024L * 1024L);
        } finally {
            context.stop();
            System.setOut(previousOutput);
        }
    }

    private static String onlyLine(String output) {
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }
}
