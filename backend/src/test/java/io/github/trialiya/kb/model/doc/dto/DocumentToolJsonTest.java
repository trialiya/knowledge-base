package io.github.trialiya.kb.model.doc.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/** Что из документов уходит модели: без пустых полей, но с {@code parentId} и у корня. */
class DocumentToolJsonTest {

    private final DefaultToolCallResultConverter converter = new DefaultToolCallResultConverter();

    private static DocumentNode node(
            long id, @Nullable Long parentId, List<DocumentNode> children) {
        return new DocumentNode(
                id,
                "Doc " + id,
                "document",
                parentId,
                3,
                id == 1 ? "# Body" : "",
                2,
                id == 1 ? LocalDateTime.of(2026, 9, 1, 12, 0) : null,
                id == 1 ? LocalDateTime.of(2026, 9, 2, 12, 0) : null,
                children,
                !children.isEmpty(),
                false,
                null,
                false,
                null);
    }

    @Test
    void skeletonNodeKeepsRootParentIdAndNothingEmpty() {
        String json =
                converter.convert(List.of(DocumentSkeletonNode.of(node(5, null, List.of()))), null);

        assertThat(json)
                .isEqualTo(
                        "[{\"id\":5,\"title\":\"Doc 5\",\"type\":\"document\",\"parentId\":null,"
                                + "\"version\":3,\"descriptionVersion\":2,"
                                + "\"hasChildren\":false,\"system\":false}]");
    }

    @Test
    void documentViewListsChildrenAsSkeletonNodes() {
        DocumentNode doc = node(1, null, List.of(node(2, 1L, List.of())));

        String json = converter.convert(DocumentView.of(doc), null);

        assertThat(json)
                .contains("\"description\":\"# Body\"")
                .contains("\"updatedAt\":")
                .doesNotContain("\"summary\"")
                .doesNotContain("\"summarySourceVersion\"")
                .endsWith(
                        "\"children\":[{\"id\":2,\"title\":\"Doc 2\",\"type\":\"document\","
                                + "\"parentId\":1,\"version\":3,\"descriptionVersion\":2,"
                                + "\"hasChildren\":false,\"system\":false}]}");
        assertThat(json.indexOf("\"children\"")).isEqualTo(json.lastIndexOf("\"children\""));
    }
}
