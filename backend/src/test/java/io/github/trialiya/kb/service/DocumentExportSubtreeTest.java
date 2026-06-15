package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.DocumentType;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentExportService#renderSubtree} — the in-memory, filesystem-free
 * rendering used by the download endpoint. Repository is mocked.
 *
 * <pre>
 *   Docs/ (id=1, folder)  description links to id=2
 *     Intro (id=2)        description links to id=3
 *     API   (id=3)
 * </pre>
 */
class DocumentExportSubtreeTest {

    private DocumentRepository repo;
    private DocumentExportService service;

    private DocumentEntity docs;
    private DocumentEntity intro;
    private DocumentEntity api;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        service = new DocumentExportService(repo, new DocumentsConfiguration("/unused", true));

        docs = entity(1, "Docs", DocumentType.FOLDER, null, 0, "Folder root, see [intro](/?doc=2).");
        intro = entity(2, "Intro", DocumentType.DOCUMENT, 1L, 0, "See [API doc](/?doc=3).");
        api = entity(3, "API", DocumentType.DOCUMENT, 1L, 1, "API body");

        when(repo.findById(1L)).thenReturn(Optional.of(docs));
        when(repo.findById(2L)).thenReturn(Optional.of(intro));
        when(repo.findById(3L)).thenReturn(Optional.of(api));

        // Children are loaded per level; each call must yield a fresh (single-use) stream because
        // the subtree is walked twice (path collection + content rendering).
        when(repo.findAllByParentIdOrderByPosition(1L)).thenAnswer(inv -> Stream.of(intro, api));
        when(repo.findAllByParentIdOrderByPosition(2L)).thenAnswer(inv -> Stream.empty());
        when(repo.findAllByParentIdOrderByPosition(3L)).thenAnswer(inv -> Stream.empty());
    }

    private static DocumentEntity entity(
            long id, String title, DocumentType type, Long parentId, int pos, String description) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setType(type);
        e.setParentId(parentId);
        e.setPosition(pos);
        e.setDescription(description);
        e.setUpdatedAt(LocalDateTime.of(2026, 6, 14, 12, 0));
        return e;
    }

    @Test
    void folderSubtreeContainsFullLayout() {
        Map<String, String> entries = service.renderSubtree(1, true);

        assertThat(entries).containsKeys(
                "docs/.meta.yaml",
                "docs/.content.md",
                "docs/.index.md",
                "docs/intro.md",
                "docs/intro.yaml",
                "docs/api.md",
                "docs/api.yaml");
    }

    @Test
    void folderSubtreeRewritesLinksRelativeToArchive() {
        Map<String, String> entries = service.renderSubtree(1, false);

        // Intro and API are siblings inside the folder → link becomes a bare file name.
        assertThat(entries.get("docs/intro.md")).contains("[API doc](api.md)").doesNotContain("/?doc=3");
        // The folder body links to the child document.
        assertThat(entries.get("docs/.content.md")).contains("[intro](intro.md)");
        // No meta requested.
        assertThat(entries).doesNotContainKey("docs/intro.yaml");
    }

    @Test
    void documentSubtreeIsSingleMarkdownEntry() {
        Map<String, String> entries = service.renderSubtree(2, false);

        assertThat(entries).containsOnlyKeys("intro.md");
        // The target (id=3) is outside this single-document subtree → link left untouched.
        assertThat(entries.get("intro.md")).contains("[API doc](/?doc=3)");
    }

    @Test
    void missingRootThrows() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.renderSubtree(99, true))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
