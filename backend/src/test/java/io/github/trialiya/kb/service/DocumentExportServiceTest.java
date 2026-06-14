package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DocumentExportService}.
 *
 * <p>The repository is mocked and the export target is a JUnit {@code @TempDir}, so the tests
 * exercise the real on-disk layout (folder dirs, {@code .content.md}, {@code .index.md}, sidecar
 * {@code .yaml}) and — most importantly — the {@code /?doc=ID} link rewriting between documents.
 *
 * <pre>
 *   Docs/ (id=1, folder)
 *     Intro (id=2)  description links to id=3 and a missing id=999
 *     API   (id=3)
 *   Root Doc (id=4)
 * </pre>
 */
class DocumentExportServiceTest {

    private DocumentRepository repo;

    @TempDir Path exportDir;

    private DocumentExportService service;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        DocumentsConfiguration config = new DocumentsConfiguration(exportDir.toString(), true);
        service = new DocumentExportService(repo, config);
    }

    private static DocumentEntity doc(long id, String title, Long parentId, int position, String description) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setTitle(title);
        e.setType("document");
        e.setParentId(parentId);
        e.setPosition(position);
        e.setDescription(description);
        e.setUpdatedAt(LocalDateTime.of(2026, 6, 14, 12, 0));
        return e;
    }

    private static DocumentEntity folder(long id, String title, Long parentId, int position, String description) {
        DocumentEntity e = doc(id, title, parentId, position, description);
        e.setType("folder");
        return e;
    }

    /** Wires the standard fixture tree into the mocked repository. */
    private void stubTree(List<DocumentEntity> all, List<DocumentEntity> roots) {
        // Mutable copies — the service sorts the returned lists in place.
        when(repo.findAll()).thenReturn(new java.util.ArrayList<>(all));
        when(repo.findRoots()).thenReturn(new java.util.ArrayList<>(roots));
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(exportDir.resolve(relativePath));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    @Nested
    class Layout {

        @Test
        void writesFolderAndDocumentFilesWithMeta() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "Folder body");
            DocumentEntity intro = doc(2, "Intro", 1L, 0, "Intro body");
            DocumentEntity api = doc(3, "API", 1L, 1, "API body");
            DocumentEntity rootDoc = doc(4, "Root Doc", null, 1, "Top body");
            stubTree(List.of(docs, intro, api, rootDoc), List.of(docs, rootDoc));

            service.exportAll(true);

            assertThat(exportDir.resolve("docs")).isDirectory();
            assertThat(exportDir.resolve("docs/.content.md")).exists();
            assertThat(exportDir.resolve("docs/.meta.yaml")).exists();
            assertThat(exportDir.resolve("docs/.index.md")).exists();
            assertThat(exportDir.resolve("docs/intro.md")).exists();
            assertThat(exportDir.resolve("docs/intro.yaml")).exists();
            assertThat(exportDir.resolve("docs/api.md")).exists();
            assertThat(exportDir.resolve("docs/api.yaml")).exists();
            assertThat(exportDir.resolve("root-doc.md")).exists();
            assertThat(exportDir.resolve("root-doc.yaml")).exists();
            assertThat(exportDir.resolve(".index.md")).exists();
        }

        @Test
        void omitsSidecarYamlWhenMetaDisabled() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "Folder body");
            DocumentEntity intro = doc(2, "Intro", 1L, 0, "Intro body");
            stubTree(List.of(docs, intro), List.of(docs));

            service.exportAll(false);

            assertThat(exportDir.resolve("docs/intro.md")).exists();
            assertThat(exportDir.resolve("docs/intro.yaml")).doesNotExist();
            assertThat(exportDir.resolve("docs/.meta.yaml")).doesNotExist();
            // .content.md is always created (may be empty)
            assertThat(exportDir.resolve("docs/.content.md")).exists();
        }

        @Test
        void rootIndexListsChildrenInPositionOrder() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "");
            DocumentEntity rootDoc = doc(4, "Root Doc", null, 1, "Top body");
            // Provide roots in reverse order to prove the service sorts by position.
            stubTree(List.of(docs, rootDoc), List.of(rootDoc, docs));

            service.exportAll(true);

            String index = read(".index.md");
            int folderIdx = index.indexOf("Docs");
            int docIdx = index.indexOf("Root Doc");
            assertThat(folderIdx).isGreaterThanOrEqualTo(0);
            assertThat(docIdx).isGreaterThan(folderIdx); // position 0 before position 1
            // Folder entries link to the folder's .content.md
            assertThat(index).contains("docs/.content.md");
            assertThat(index).contains("root-doc.md");
        }
    }

    // ── Link rewriting ──────────────────────────────────────────────────────────

    @Nested
    class LinkRewriting {

        @Test
        void rewritesInternalDocLinkToRelativePath() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "");
            DocumentEntity intro = doc(2, "Intro", 1L, 0, "See [API doc](/?doc=3) for details.");
            DocumentEntity api = doc(3, "API", 1L, 1, "API body");
            stubTree(List.of(docs, intro, api), List.of(docs));

            service.exportAll(true);

            // intro.md and api.md are siblings, so the rewritten link is just the file name.
            assertThat(read("docs/intro.md")).contains("[API doc](api.md)").doesNotContain("/?doc=3");
        }

        @Test
        void rewritesCrossFolderLinkToRelativePath() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "");
            DocumentEntity intro = doc(2, "Intro", 1L, 0, "Jump to [top](/?doc=4).");
            DocumentEntity rootDoc = doc(4, "Root Doc", null, 1, "Top body");
            stubTree(List.of(docs, intro, rootDoc), List.of(docs, rootDoc));

            service.exportAll(true);

            // From docs/intro.md up to root-doc.md → "../root-doc.md".
            assertThat(read("docs/intro.md")).contains("[top](../root-doc.md)");
        }

        @Test
        void leavesUnresolvedDocLinkUnchanged() throws Exception {
            DocumentEntity intro = doc(2, "Intro", null, 0, "Broken [missing](/?doc=999) link.");
            stubTree(List.of(intro), List.of(intro));

            service.exportAll(true);

            assertThat(read("intro.md")).contains("[missing](/?doc=999)");
        }

        @Test
        void rewritesLinkPointingToFolderContent() throws Exception {
            DocumentEntity docs = folder(1, "Docs", null, 0, "Folder body");
            DocumentEntity rootDoc = doc(4, "Root Doc", null, 1, "Go to [folder](/?doc=1).");
            stubTree(List.of(docs, rootDoc), List.of(docs, rootDoc));

            service.exportAll(true);

            // Folder targets resolve to the folder's .content.md.
            assertThat(read("root-doc.md")).contains("[folder](docs/.content.md)");
        }
    }

    // ── Metadata ────────────────────────────────────────────────────────────────

    @Test
    void metaYamlContainsCoreFields() throws Exception {
        DocumentEntity intro = doc(2, "Intro \"quoted\"", null, 0, "body");
        stubTree(List.of(intro), List.of(intro));

        service.exportAll(true);

        String meta = read("intro-quoted.yaml");
        assertThat(meta).contains("id: 2");
        assertThat(meta).contains("type: document");
        assertThat(meta).contains("position: 0");
        // Double quotes in the title are escaped for safe YAML.
        assertThat(meta).contains("title: \"Intro \\\"quoted\\\"\"");
    }

    @Test
    void returnsNumberOfFilesWritten() {
        DocumentEntity intro = doc(2, "Intro", null, 0, "body");
        stubTree(List.of(intro), List.of(intro));

        // intro.md + intro.yaml + root .index.md = 3 files.
        int count = service.exportAll(true);

        assertThat(count).isEqualTo(3);
    }
}
