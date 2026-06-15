package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.DocumentType;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DocumentExportService#exportAll(boolean)} — the on-disk export. The
 * repository is mocked and serves children one level at a time via {@code findRoots} / {@code
 * findAllByParentIdOrderByPosition}, so the test also pins down that {@code exportAll} never relies
 * on a full-table {@code findAll()} scan.
 *
 * <pre>
 *   &lt;root&gt;/
 *     docs/ (id=1, folder)   .content.md links to id=2
 *       Intro (id=2)
 *       API   (id=3)
 *     readme (id=4)          description links to id=2
 * </pre>
 */
class DocumentExportAllTest {

    @TempDir Path exportDir;

    private DocumentRepository repo;
    private DocumentExportService service;

    private DocumentEntity docs;
    private DocumentEntity intro;
    private DocumentEntity api;
    private DocumentEntity readme;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        service =
                new DocumentExportService(
                        repo, new DocumentsConfiguration(exportDir.toString(), true));

        docs =
                entity(
                        1,
                        "Docs",
                        DocumentType.FOLDER,
                        null,
                        0,
                        "Folder root, see [intro](/?doc=2).");
        intro = entity(2, "Intro", DocumentType.DOCUMENT, 1L, 0, "Intro body");
        api = entity(3, "API", DocumentType.DOCUMENT, 1L, 1, "API body");
        readme = entity(4, "Readme", DocumentType.DOCUMENT, null, 1, "Start at [intro](/?doc=2).");

        when(repo.findRoots()).thenReturn(new ArrayList<>(List.of(docs, readme)));
        // Each call yields a fresh (single-use) stream — the tree is walked twice (paths + write).
        when(repo.findAllByParentIdOrderByPosition(1L)).thenAnswer(inv -> Stream.of(intro, api));
        when(repo.findAllByParentIdOrderByPosition(2L)).thenAnswer(inv -> Stream.empty());
        when(repo.findAllByParentIdOrderByPosition(3L)).thenAnswer(inv -> Stream.empty());
        when(repo.findAllByParentIdOrderByPosition(4L)).thenAnswer(inv -> Stream.empty());
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
    void writesFullTreeLayoutToDisk() {
        int count = service.exportAll(true);

        assertThat(exportDir.resolve(".index.md")).exists();
        assertThat(exportDir.resolve("docs/.meta.yaml")).exists();
        assertThat(exportDir.resolve("docs/.content.md")).exists();
        assertThat(exportDir.resolve("docs/.index.md")).exists();
        assertThat(exportDir.resolve("docs/intro.md")).exists();
        assertThat(exportDir.resolve("docs/intro.yaml")).exists();
        assertThat(exportDir.resolve("docs/api.md")).exists();
        assertThat(exportDir.resolve("docs/api.yaml")).exists();
        assertThat(exportDir.resolve("readme.md")).exists();
        assertThat(exportDir.resolve("readme.yaml")).exists();
        // Return value counts every written file.
        assertThat(count).isPositive();
    }

    @Test
    void rewritesInternalLinksToRelativePaths() throws Exception {
        service.exportAll(false);

        // Root-level readme → intro lives under docs/.
        assertThat(Files.readString(exportDir.resolve("readme.md")))
                .contains("[intro](docs/intro.md)")
                .doesNotContain("/?doc=2");
        // Folder body → its own child.
        assertThat(Files.readString(exportDir.resolve("docs/.content.md")))
                .contains("[intro](intro.md)");
    }

    @Test
    void skipsMetaWhenDisabled() {
        service.exportAll(false);

        assertThat(exportDir.resolve("docs/.meta.yaml")).doesNotExist();
        assertThat(exportDir.resolve("docs/intro.yaml")).doesNotExist();
        assertThat(exportDir.resolve("readme.yaml")).doesNotExist();
        // Content files are still written.
        assertThat(exportDir.resolve("docs/.content.md")).exists();
        assertThat(exportDir.resolve("readme.md")).exists();
    }
}
