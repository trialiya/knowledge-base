package io.github.trialiya.kb.model.doc.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Что из документов уходит модели: без пустых полей и без того, что нужно только UI. */
class DocumentToolJsonTest {

    private final CompactToolResultConverter converter = new CompactToolResultConverter();

    private static DocumentNode node(long id, @Nullable Long parentId, List<DocumentNode> children) {
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
    void skeletonRootHasNoParentIdKeyAndNothingEmpty() {
        String json = converter.convert(List.of(DocumentSkeletonNode.of(node(5, null, List.of()))), null);

        assertThat(json)
                .isEqualTo("[{\"id\":5,\"title\":\"Doc 5\",\"type\":\"document\","
                        + "\"version\":3,\"descriptionVersion\":2,\"hasChildren\":false}]");
    }

    @Test
    void documentViewListsChildrenWithoutWhatTheDocumentAlreadySays() {
        DocumentNode doc = node(1, null, List.of(node(2, 1L, List.of())));

        String json = converter.convert(DocumentView.of(doc), null);

        assertThat(json)
                .contains("\"description\":\"# Body\"")
                .doesNotContain("\"updatedAt\"", "\"createdAt\"", "\"summaryStale\"")
                .doesNotContain("\"summary\"")
                .doesNotContain("\"summarySourceVersion\"")
                .endsWith("\"children\":[{\"id\":2,\"title\":\"Doc 2\",\"type\":\"document\","
                        + "\"descriptionVersion\":2,\"hasChildren\":false}]}");
        assertThat(json.indexOf("\"children\"")).isEqualTo(json.lastIndexOf("\"children\""));
    }

    @Test
    void aLeafDocumentHasNoChildrenKey() {
        String json = converter.convert(DocumentView.of(node(1, null, List.of())), null);

        assertThat(json).doesNotContain("\"children\"");
    }

    @Test
    void aNameMatchCarriesTheSnippetAndNoEmptyParent() {
        String json = converter.convert(List.of(DocumentNameMatch.of(node(1, null, List.of()))), null);

        assertThat(json).isEqualTo("[{\"id\":1,\"title\":\"Doc 1\",\"type\":\"document\",\"snippet\":\"# Body\"}]");
        assertThat(converter.convert(List.of(DocumentNameMatch.of(node(2, 1L, List.of()))), null))
                .isEqualTo("[{\"id\":2,\"title\":\"Doc 2\",\"type\":\"document\",\"parentId\":1}]");
    }
}
