package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Узел дерева базы знаний без содержимого — то, что модель получает списком: скелет всего дерева
 * ({@code getTreeSkeleton}) и дети документа ({@code getDocument}).
 *
 * <p>Отдельный record, а не {@link DocumentNode} с пустыми полями: в скелете на сотни узлов пустые
 * {@code description}, даты, {@code children} и поля сводки занимали половину ответа.
 *
 * <p>{@code parentId} печатается и когда он {@code null}: корень дерева без этого ключа «Обзор»
 * чата не узнал бы как узел иерархии ({@code treeResult.js}).
 *
 * @param parentId id родителя; {@code null} у узла верхнего уровня
 */
public record DocumentSkeletonNode(
        long id,
        String title,
        String type,
        @Nullable Long parentId,
        int version,
        int descriptionVersion,
        boolean hasChildren,
        boolean system)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    public static DocumentSkeletonNode of(DocumentNode node) {
        return new DocumentSkeletonNode(
                node.id(),
                node.title(),
                node.type(),
                node.parentId(),
                node.version(),
                node.descriptionVersion(),
                node.hasChildren(),
                node.system());
    }

    @Override
    public String getFormattedResponse() {
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("type", type)
                .add("sys", system ? "1" : null)
                .add("parent", parentId)
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("type", type);
        meta.put("title", title);
        meta.put("parent", parentId);
        meta.put("version", version);
        meta.put("descriptionVersion", descriptionVersion);
        return meta;
    }
}
