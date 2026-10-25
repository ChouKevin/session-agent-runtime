package com.java.system.sessionagent.artifact.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.java.system.sessionagent.artifact.port.ArtifactStore.MAX_BYTES;

/** Bounded UTF-8 exports; no Markdown interpretation or CSV type inference. */
public final class ArtifactEncoder {
    public static final String CSV_DISCLOSURE = "Spreadsheet-safe export prefixes an apostrophe to formula-leading cells and cells beginning with tab/CR/LF. This is not a universal guarantee across spreadsheet import behaviors. Source data remains in the tool audit.";

    private ArtifactEncoder() {}

    public record Encoded(String title, String extension, String mediaType, byte[] content,
                          Map<String, Object> disclosures) {}

    public static Encoded markdown(Map<String, Object> arguments) {
        require(arguments.keySet().equals(Set.of("title", "content")), "Expected title and content only");
        String title = title(arguments.get("title"));
        String content = string(arguments.get("content"));
        return new Encoded(title, "md", "text/markdown; charset=utf-8", bytes(content), Map.of());
    }

    public static Encoded csv(Map<String, Object> arguments) {
        require(arguments.keySet().equals(Set.of("title", "columns", "rows")), "Expected title, columns and rows only");
        String title = title(arguments.get("title"));
        List<?> columns = list(arguments.get("columns"));
        List<?> rows = list(arguments.get("rows"));
        require(!columns.isEmpty() && columns.size() <= 100, "CSV requires 1 to 100 columns");
        require(rows.size() <= 10_000, "CSV exceeds 10000 rows");
        StringBuilder output = new StringBuilder("\ufeff");
        appendRow(output, columns);
        for (Object value : rows) {
            List<?> row = list(value);
            require(row.size() == columns.size(), "CSV rows must be rectangular");
            appendRow(output, row);
        }
        return new Encoded(title, "csv", "text/csv; charset=utf-8", bytes(output.toString()),
                Map.of("spreadsheetSafeTransformation", CSV_DISCLOSURE));
    }

    private static void appendRow(StringBuilder output, List<?> fields) {
        for (int index = 0; index < fields.size(); index++) {
            String field = string(fields.get(index));
            // Bound allocation before escaping; every UTF-16 unit needs at least one output byte.
            require(field.length() <= MAX_BYTES && output.length() <= MAX_BYTES - field.length(), "Artifact exceeds 1 MiB");
            if (index > 0) {
                output.append(',');
            }
            output.append('"');
            if (dangerous(field)) {
                output.append('\'');
            }
            for (int offset = 0; offset < field.length(); offset++) {
                char character = field.charAt(offset);
                if (character == '"') {
                    output.append('"');
                }
                output.append(character);
                require(output.length() <= MAX_BYTES, "Artifact exceeds 1 MiB");
            }
            output.append('"');
        }
        output.append("\r\n");
        require(output.length() <= MAX_BYTES, "Artifact exceeds 1 MiB");
    }

    private static boolean dangerous(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') {
            return true;
        }
        int offset = 0;
        while (offset < value.length()) {
            int point = value.codePointAt(offset);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) {
                return point == '=' || point == '+' || point == '-' || point == '@';
            }
            offset += Character.charCount(point);
        }
        return false;
    }

    private static String title(Object value) {
        String title = string(value);
        require(title.codePointCount(0, title.length()) <= 120, "Title exceeds 120 code points");
        return title;
    }

    private static String string(Object value) {
        require(value instanceof String, "Expected a string");
        String text = (String) value;
        // Reject malformed UTF-16 rather than silently replacing accepted source bytes.
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                require(index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)), "Invalid Unicode");
                index++;
            } else {
                require(!Character.isLowSurrogate(character), "Invalid Unicode");
            }
        }
        return text;
    }

    private static List<?> list(Object value) {
        require(value instanceof List<?>, "Expected an array");
        return (List<?>) value;
    }

    private static byte[] bytes(String content) {
        require(content.length() <= MAX_BYTES, "Artifact exceeds 1 MiB");
        int byteLength = 0;
        for (int offset = 0; offset < content.length();) {
            int point = content.codePointAt(offset);
            byteLength += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            require(byteLength <= MAX_BYTES, "Artifact exceeds 1 MiB");
            offset += Character.charCount(point);
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
