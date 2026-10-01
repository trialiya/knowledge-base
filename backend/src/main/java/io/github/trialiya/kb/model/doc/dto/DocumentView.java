package io.github.trialiya.kb.model.doc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.List;
import java.util.Map;

/**
 * Документ, как его читает модель ({@code getDocument}): все поля {@link DocumentNode}, но дети —
 * {@link DocumentSkeletonNode}, без пустых содержимого, дат и вложенных {@code children}, которые у
 * ребёнка в {@link DocumentNode} всё равно не заполнены.
 *
 * <p>Детей нет — нет и ключа; у ребёнка не печатаются {@code parentId} (это id самого документа) и
 * {@code version}.
 *
 * <p>REST ({@code GET /api/documents/{id}}) отдаёт {@link DocumentNode} как есть: дерево UI читает
 * {@code children} у каждого узла.
 */
public record DocumentView(
        @JsonUnwrapped @JsonIgnoreProperties("children") DocumentNode document,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonIgnoreProperties({"parentId", "version"})
        List<DocumentSkeletonNode> children)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    public static DocumentView of(DocumentNode node) {
        List<DocumentNode> kids = node.children() == null ? List.of() : node.children();
        return new DocumentView(
                node, kids.stream().map(DocumentSkeletonNode::of).toList());
    }

    @Override
    public String getFormattedResponse() {
        return document.getFormattedResponse();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return document.getResultMeta();
    }
}
