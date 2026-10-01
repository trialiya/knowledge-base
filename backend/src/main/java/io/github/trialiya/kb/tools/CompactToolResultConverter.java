package io.github.trialiya.kb.tools;

import io.github.trialiya.kb.model.tool.ModelView;
import io.github.trialiya.kb.model.tool.ToolJson;
import java.awt.image.RenderedImage;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public class CompactToolResultConverter implements ToolCallResultConverter {

    private static final DefaultToolCallResultConverter FALLBACK = new DefaultToolCallResultConverter();

    /**
     * Spring AI's own mapper ({@link DefaultToolCallResultConverter} writes with the same one),
     * writing through {@link ToolJson.Model}: a {@code UiOnly} property is left out, every property
     * without a view stays in. Jackson 3 drops view-less properties under an active view unless
     * {@code DEFAULT_VIEW_INCLUSION} says otherwise.
     */
    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper()
            .rebuild()
            .enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .build();

    private static final ObjectWriter MODEL_JSON = MAPPER.writerWithView(ToolJson.Model.class);

    /**
     * A {@link ModelView} result is answered with its view; the whole result, serialised with no
     * view at all, goes to {@link RecordingToolCallback} for the call's detail view — only when the
     * view left out something that was there, so an untrimmed result is not kept twice. A view that
     * only dropped empty fields ({@code null}, {@code []}) cut nothing.
     */
    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        RecordingToolCallback.CURRENT_RESULT.set(result);
        if (result instanceof ModelView view) {
            String shown = forModel(view.forModel(), null);
            String full = FALLBACK.convert(result, returnType);
            if (!full.equals(shown) && !withoutEmpty(full).equals(withoutEmpty(shown))) {
                RecordingToolCallback.CURRENT_FULL_TEXT.set(full);
            }
            return shown;
        }
        return forModel(result, returnType);
    }

    /** The JSON with every {@code null} and empty-array property removed, at any depth. */
    private static JsonNode withoutEmpty(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JacksonException e) {
            return MAPPER.getNodeFactory().textNode(json);
        }
        prune(node);
        return node;
    }

    private static void prune(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().removeIf(e -> e.getValue().isNull() || isEmptyArray(e.getValue()));
        }
        node.forEach(CompactToolResultConverter::prune);
    }

    private static boolean isEmptyArray(JsonNode node) {
        return node.isArray() && node.isEmpty();
    }

    /**
     * Strings, images and {@code void} keep Spring AI's handling — a string that already is JSON
     * goes as it is, an image as base64; everything else is written through {@link #MODEL_JSON}.
     */
    private static String forModel(@Nullable Object result, @Nullable Type returnType) {
        if (result == null || returnType == Void.TYPE || result instanceof String || result instanceof RenderedImage) {
            return FALLBACK.convert(result, returnType);
        }
        return MODEL_JSON.writeValueAsString(result);
    }
}
