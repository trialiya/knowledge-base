package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.tools.McpContent.Normalized;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shapes an MCP answer arrives in — as Spring AI's callback writes {@code
 * CallToolResult.content} — and the payload each one comes down to.
 */
class McpContentTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void aSingleTextBlockHoldingJsonIsThatJson() {
        Normalized n = McpContent.normalize(
                "[{\"type\":\"text\",\"text\":\"{\\\"issues\\\":[{\\\"key\\\":\\\"KB-1\\\"}]}\"}]");

        assertThat(n.json()).isEqualTo("{\"issues\":[{\"key\":\"KB-1\"}]}");
        assertThat(n.value().get("issues").get(0).get("key").asString()).isEqualTo("KB-1");
        assertThat(n.omittedBytes()).isZero();
    }

    @Test
    void aSingleTextBlockOfProseIsItsText() {
        Normalized n = McpContent.normalize("[{\"type\":\"text\",\"text\":\"line one\\nline two\"}]");

        assertThat(n.value().isString()).isTrue();
        assertThat(n.value().asString()).isEqualTo("line one\nline two");
        assertThat(n.json()).isEqualTo("\"line one\\nline two\"");
    }

    /** A bare number is JSON too, but unwrapping it would turn "42" the text into 42 the number. */
    @Test
    void aTextThatIsAJsonScalarStaysText() {
        Normalized n = McpContent.normalize("[{\"type\":\"text\",\"text\":\"42\"}]");

        assertThat(n.value().isString()).isTrue();
        assertThat(n.value().asString()).isEqualTo("42");
    }

    /** A server that answers one block per record means a list of records. */
    @Test
    void severalTextBlocksAreAnArrayOfTheirPayloads() {
        Normalized n = McpContent.normalize("[{\"type\":\"text\",\"text\":\"{\\\"key\\\":\\\"A-1\\\"}\"},"
                + "{\"type\":\"text\",\"text\":\"{\\\"key\\\":\\\"A-2\\\"}\"},"
                + "{\"type\":\"text\",\"text\":\"done\"}]");

        assertThat(n.json()).isEqualTo("[{\"key\":\"A-1\"},{\"key\":\"A-2\"},\"done\"]");
        assertThat(n.value().get(1).get("key").asString()).isEqualTo("A-2");
    }

    @Test
    void binaryBlocksAreReducedToANoteOfWhatWasThere() {
        String base64 = "A".repeat(4096);
        Normalized n = McpContent.normalize("[{\"type\":\"text\",\"text\":\"chart:\"},"
                + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + base64 + "\"},"
                + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///a.bin\",\"blob\":\"" + base64 + "\"}}]");

        assertThat(n.json()).doesNotContain(base64);
        assertThat(n.value().get(0).get("text").asString()).isEqualTo("chart:");
        assertThat(n.value().get(1).get("mimeType").asString()).isEqualTo("image/png");
        assertThat(n.value().get(1).get("data").asString()).isEqualTo("omitted (3 KB)");
        assertThat(n.value().get(2).get("resource").get("blob").asString()).isEqualTo("omitted (3 KB)");
        assertThat(n.value().get(2).get("resource").get("uri").asString()).isEqualTo("file:///a.bin");
        assertThat(n.omittedBytes()).isEqualTo(2L * base64.length());
    }

    @Test
    void somethingThatIsNotABlockArrayIsKeptAsText() {
        Normalized n = McpContent.normalize("not json at all");

        assertThat(n.value().asString()).isEqualTo("not json at all");
    }

    @Test
    void theShapeReachesTheKeysOfAListedRecord() throws Exception {
        String shape = MAPPER.writeValueAsString(McpContent.shape(
                MAPPER.readTree("{\"total\":2,\"issues\":[{\"key\":\"KB-1\",\"fields\":{\"summary\":\"x\"}},{}]}")));

        assertThat(shape)
                .isEqualTo("{\"type\":\"object\",\"keys\":{\"total\":\"number\","
                        + "\"issues\":{\"type\":\"array\",\"length\":2,\"items\":{\"type\":\"object\","
                        + "\"keys\":{\"key\":\"string\",\"fields\":{\"type\":\"object\"}}}}}}");
    }

    @Test
    void theShapeOfAWideObjectSaysHowManyKeysItLeftOut() throws Exception {
        StringBuilder wide = new StringBuilder("{");
        for (int i = 0; i < 20; i++) {
            wide.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        String shape = MAPPER.writeValueAsString(
                McpContent.shape(MAPPER.readTree(wide.append('}').toString())));

        assertThat(shape)
                .contains("\"k11\":\"number\"")
                .doesNotContain("\"k12\"")
                .contains("\"…\":\"8 more\"");
    }
}
