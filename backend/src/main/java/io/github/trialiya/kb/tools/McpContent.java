package io.github.trialiya.kb.tools;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an MCP tool answered, as something worth reading: the payload instead of the protocol
 * envelope around it.
 *
 * <p>Spring AI's MCP callbacks answer with the JSON of {@code CallToolResult.content} — an array of
 * blocks, {@code [{"type":"text","text":"…"}]} — and the payload a server means is usually a JSON
 * document written as the text of the one block, encoded twice over. Binary blocks (an image, an
 * audio clip, a blob resource) carry base64, which is no use to a reader and usually larger than
 * everything else in the answer.
 *
 * <p>{@link #normalize} unwraps the first and replaces the second with a note of what was there. It
 * is the one shape a large answer takes everywhere past the model's own copy: the head the model is
 * shown, the copy kept for {@code kb.result}, and the value a script gets back from a call.
 */
public final class McpContent {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The keys {@link #shape} names per object before it says how many more there are. */
    private static final int SHAPE_KEYS = 12;

    private McpContent() {}

    /**
     * @param value the payload: a JSON tree, or a text node when it is not JSON
     * @param json {@code value} written out — the text a reader gets
     * @param omittedBytes base64 characters left out with the binary blocks; 0 when there were none
     */
    public record Normalized(JsonNode value, String json, long omittedBytes) {}

    /**
     * The payload of {@code contentJson}:
     *
     * <ul>
     *   <li>one text block whose text is JSON — that JSON;
     *   <li>one text block — its text;
     *   <li>several blocks, all of them text — an array of their payloads, each the JSON of its text
     *       or the text itself: a server that answers one block per record means a list of records;
     *   <li>anything else — the blocks, each binary one reduced to its type, MIME type and size;
     *   <li>not a block array at all — the text as it came, since a server is free to break the
     *       protocol and the answer is still worth showing.
     * </ul>
     */
    public static Normalized normalize(String contentJson) {
        JsonNode blocks = parse(contentJson);
        if (!(blocks instanceof ArrayNode array)) {
            return text(contentJson);
        }
        if (!array.isEmpty() && array.valueStream().allMatch(McpContent::isText)) {
            if (array.size() == 1) {
                JsonNode payload = payload(array.get(0));
                return new Normalized(payload, MAPPER.writeValueAsString(payload), 0);
            }
            ArrayNode payloads = JsonNodeFactory.instance.arrayNode();
            array.forEach(block -> payloads.add(payload(block)));
            return new Normalized(payloads, MAPPER.writeValueAsString(payloads), 0);
        }
        long omitted = 0;
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (JsonNode block : array) {
            String data = binaryData(block);
            if (data == null) {
                kept.add(block);
            } else {
                omitted += data.length();
                kept.add(withoutData((ObjectNode) block, data.length()));
            }
        }
        return new Normalized(kept, MAPPER.writeValueAsString(kept), omitted);
    }

    /**
     * A sketch of {@code value} three levels deep — the keys of an object with the type of each,
     * the length of an array and the keys of its first element — for a reader deciding how to pull
     * something out of a value it was shown only the head of. Three, because the commonest answer
     * is an object holding a list of records, and the record's keys are what the reader needs.
     */
    public static JsonNode shape(JsonNode value) {
        return shape(value, 3);
    }

    private static JsonNode shape(JsonNode value, int depth) {
        ObjectNode shape = JsonNodeFactory.instance.objectNode();
        if (value.isObject()) {
            shape.put("type", "object");
            if (depth > 0) {
                shape.set("keys", keys(value, depth));
            }
        } else if (value.isArray()) {
            shape.put("type", "array");
            shape.put("length", value.size());
            if (depth > 0 && !value.isEmpty()) {
                shape.set("items", shape(value.get(0), depth - 1));
            }
        } else {
            shape.put("type", typeOf(value));
        }
        return shape;
    }

    private static ObjectNode keys(JsonNode object, int depth) {
        ObjectNode keys = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        int shown = 0;
        while (fields.hasNext() && shown < SHAPE_KEYS) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode child = field.getValue();
            keys.set(
                    field.getKey(),
                    child.isObject() || child.isArray()
                            ? shape(child, depth - 1)
                            : JsonNodeFactory.instance.stringNode(typeOf(child)));
            shown++;
        }
        if (object.size() > shown) {
            keys.put("…", (object.size() - shown) + " more");
        }
        return keys;
    }

    private static String typeOf(JsonNode node) {
        if (node.isString()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return node.isNull() ? "null" : node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private static Normalized text(String text) {
        JsonNode node = JsonNodeFactory.instance.stringNode(text);
        return new Normalized(node, MAPPER.writeValueAsString(node), 0);
    }

    /** The JSON a text block's text holds when it is an object or an array, else the text itself. */
    private static JsonNode payload(JsonNode textBlock) {
        String text = textBlock.get("text").asString();
        JsonNode payload = parse(text);
        return payload != null && (payload.isObject() || payload.isArray())
                ? payload
                : JsonNodeFactory.instance.stringNode(text);
    }

    private static boolean isText(JsonNode block) {
        return "text".equals(block.path("type").asString(null))
                && block.get("text") != null
                && block.get("text").isString();
    }

    /**
     * The base64 a block carries: {@code data} of an image or an audio block, {@code blob} of an
     * embedded resource. Null for a block with none.
     */
    private static @Nullable String binaryData(JsonNode block) {
        if (!(block instanceof ObjectNode)) {
            return null;
        }
        JsonNode data = block.get("data");
        if (data != null && data.isString()) {
            return data.asString();
        }
        JsonNode blob = block.path("resource").get("blob");
        return blob != null && blob.isString() ? blob.asString() : null;
    }

    private static ObjectNode withoutData(ObjectNode block, int chars) {
        ObjectNode copy = block.deepCopy();
        String omitted = "omitted (" + approximateBytes(chars) + ")";
        if (copy.has("data")) {
            copy.put("data", omitted);
        } else if (copy.get("resource") instanceof ObjectNode resource) {
            resource.put("blob", omitted);
        }
        return copy;
    }

    /** What {@code chars} characters of base64 decode to, for a person to read. */
    private static String approximateBytes(int chars) {
        long bytes = chars / 4L * 3;
        return bytes >= 1024 ? (bytes / 1024) + " KB" : bytes + " B";
    }

    private static @Nullable JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }
}
