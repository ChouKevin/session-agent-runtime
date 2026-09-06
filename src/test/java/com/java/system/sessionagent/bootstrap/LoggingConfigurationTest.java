package com.java.system.sessionagent.bootstrap;

import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void emits_equivalent_one_line_safe_json_for_console_and_rolling_file_contract() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("logging-contract");
        LoggingEvent event = new LoggingEvent(getClass().getName(), logger, ch.qos.logback.classic.Level.INFO,
                "runtime_lifecycle", null, null);
        event.setKeyValuePairs(java.util.List.of(
                new org.slf4j.event.KeyValuePair("event", "slack_delivery_sent"),
                new org.slf4j.event.KeyValuePair("sessionId", "session-safe-id"),
                new org.slf4j.event.KeyValuePair("messageJobId", "job-safe-id"),
                new org.slf4j.event.KeyValuePair("deliveryId", "delivery-safe-id"),
                new org.slf4j.event.KeyValuePair("outcome", "SENT")));

        JsonEncoder consoleEncoder = safeJsonEncoder();
        JsonEncoder fileEncoder = safeJsonEncoder();
        String consoleLine = new String(consoleEncoder.encode(event), StandardCharsets.UTF_8);
        String fileLine = new String(fileEncoder.encode(event), StandardCharsets.UTF_8);

        assertThat(consoleLine).endsWith("\n");
        assertThat(consoleLine.stripTrailing()).doesNotContain("\n");
        assertThat(fileLine).endsWith("\n");
        assertThat(fileLine.stripTrailing()).doesNotContain("\n");
        JsonNode consoleJson = OBJECT_MAPPER.readTree(consoleLine);
        JsonNode fileJson = OBJECT_MAPPER.readTree(fileLine);
        assertThat(consoleJson).isEqualTo(fileJson);
        assertThat(consoleJson.path("level").asText()).isEqualTo("INFO");
        assertThat(consoleJson.path("loggerName").asText()).isEqualTo("logging-contract");
        assertThat(consoleJson.path("kvpList").toString()).contains("slack_delivery_sent", "session-safe-id", "delivery-safe-id");
        assertThat(consoleLine).doesNotContain("conversation secret", "xoxb-", "Authorization", "raw provider failure");

        String configuration = new String(getClass().getResourceAsStream("/logback-spring.xml").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(configuration).contains("/app/logs/session-agent-runtime.log", "SizeAndTimeBasedRollingPolicy",
                "100MB", "7", "500MB");
        assertThat(configuration).containsSubsequence("appender name=\"CONSOLE\"", "JsonEncoder", "appender name=\"ROLLING_FILE\"", "JsonEncoder");
        assertThat(configuration).contains("<withThrowable>false</withThrowable>");
    }

    private static JsonEncoder safeJsonEncoder() {
        JsonEncoder encoder = new JsonEncoder();
        encoder.setWithSequenceNumber(false);
        encoder.setWithNanoseconds(false);
        encoder.setWithThreadName(false);
        encoder.setWithContext(false);
        encoder.setWithMarkers(false);
        encoder.setWithMDC(false);
        encoder.setWithArguments(false);
        encoder.setWithThrowable(false);
        return encoder;
    }

}
