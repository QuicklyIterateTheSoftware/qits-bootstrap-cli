package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Reading the platform's JSON answers, and writing the small bodies it is sent. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** A JSON object from alternating key and value strings. */
    public static String object(String... keysAndValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(keysAndValues[i])).append(':').append(quote(keysAndValues[i + 1]));
        }
        return json.append('}').toString();
    }

    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
