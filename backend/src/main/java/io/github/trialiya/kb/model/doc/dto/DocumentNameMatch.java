package io.github.trialiya.kb.model.doc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Документ, найденный по названию ({@code findDocumentsByName}): чем его назвать и куда идти дальше
 * — {@code getDocument} по {@code id}.
 *
 * <p>Отдельный record, а не {@link DocumentNode}: у найденного по имени нет ни детей, ни
 * содержимого, и полный узел нёс бы пустой {@code children}, даты и поля сводки на каждой записи —
 * то же, от чего {@link DocumentSkeletonNode} избавил скелет дерева.
 *
 * @param parentId id родителя; у узла верхнего уровня ключа нет вовсе
 * @param snippet начало описания, чтобы отличить тёзок; нет ключа — описание пустое
 */
public record DocumentNameMatch(
        long id,
        String title,
        String type,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long parentId,

        @Nullable @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String snippet)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    public static DocumentNameMatch of(DocumentNode node) {
        return new DocumentNameMatch(node.id(), node.title(), node.type(), node.parentId(), node.description());
    }

    @Override
    public String getFormattedResponse() {
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("type", type)
                .add("parent", parentId)
                .body(Compact.truncate(snippet, 50))
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("type", type);
        meta.put("title", title);
        meta.put("parent", parentId);
        return meta;
    }
}
