package com.java.system.sessionagent.tool.json;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class StrictJsonCodec {

    private static final int UTF8_COUNT_BUFFER_BYTES = 1_024;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final ThreadLocal<ByteBuffer> UTF8_COUNT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocate(UTF8_COUNT_BUFFER_BYTES));

    private final Validator validator;

    public StrictJsonCodec() {
        this.validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    public <T> T decode(String raw, Class<T> type) {
        return decodeInternal(raw, type);
    }

    public <T> T decodeBounded(String raw, Class<T> type, int maxUtf8Bytes) {
        Assert.isTrue(maxUtf8Bytes >= 0, "Maximum UTF-8 byte count must not be negative");
        Assert.notNull(raw, "JSON input must not be null");
        if (exceedsUtf8ByteLimit(raw, maxUtf8Bytes)) {
            throw JsonContractException.inputTooLarge();
        }
        return decodeInternal(raw, type);
    }

    public <T> String canonicalize(T value) {
        Assert.notNull(value, "Value must not be null");
        try {
            return MAPPER.writeValueAsString(sortProperties(MAPPER.valueToTree(value)));
        } catch (JacksonException exception) {
            throw new JsonContractException();
        }
    }

    private <T> T decodeInternal(String raw, Class<T> type) {
        Assert.notNull(raw, "JSON input must not be null");
        Assert.notNull(type, "JSON type must not be null");
        if (!StringUtils.hasText(raw)) {
            throw new JsonContractException();
        }
        try (JsonParser parser = MAPPER.createParser(raw)) {
            JsonNode document = MAPPER.readTree(parser);
            rejectExplicitNulls(document);
            if (parser.nextToken() != null) { // cs-allow parser signals end of input with null
                throw new JsonContractException();
            }
            T value = MAPPER.treeToValue(document, type);
            validate(value);
            return value;
        } catch (JacksonException exception) {
            throw new JsonContractException();
        }
    }

    private static void rejectExplicitNulls(JsonNode node) {
        if (node.isNull()) {
            throw new JsonContractException();
        }
        Iterator<JsonNode> children = node.iterator();
        while (children.hasNext()) {
            rejectExplicitNulls(children.next());
        }
    }

    private static JsonNode sortProperties(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), sortProperties(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                sorted.add(sortProperties(child));
            }
            return sorted;
        }
        return node;
    }

    private static boolean exceedsUtf8ByteLimit(String raw, int maxUtf8Bytes) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer characters = CharBuffer.wrap(raw);
        ByteBuffer encodedBytes = UTF8_COUNT_BUFFER.get();
        encodedBytes.clear();
        int countedBytes = 0;

        while (true) {
            CoderResult result = encoder.encode(characters, encodedBytes, true);
            if (result.isError()) {
                throw new JsonContractException();
            }
            if (exceedsLimit(encodedBytes, countedBytes, maxUtf8Bytes)) {
                return true;
            }
            countedBytes += encodedBytes.position();
            encodedBytes.clear();
            if (result.isUnderflow()) {
                break;
            }
        }

        while (true) {
            CoderResult result = encoder.flush(encodedBytes);
            if (result.isError()) {
                throw new JsonContractException();
            }
            if (exceedsLimit(encodedBytes, countedBytes, maxUtf8Bytes)) {
                return true;
            }
            countedBytes += encodedBytes.position();
            encodedBytes.clear();
            if (result.isUnderflow()) {
                return false;
            }
        }
    }

    private static boolean exceedsLimit(ByteBuffer encodedBytes, int countedBytes, int maxUtf8Bytes) {
        return encodedBytes.position() > maxUtf8Bytes - countedBytes;
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new JsonContractException();
        }
    }
}
