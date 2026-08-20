package io.github.trialiya.kb.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link DocumentExportService}.
 *
 * <p>The repository is mocked at the level the export actually uses — one structural level per
 * query, one body fetched by id — and the export target is a JUnit {@code @TempDir}, so the tests
 * exercise the real on-disk layout (folder dirs, {@code .content.md}, {@code .index.md}, sidecar
 * {@code .yaml}) and the {@code /?doc=ID} link rewriting between documents.
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
        service = new DocumentExportService(new DocumentTreeReader(repo), config);
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    /** A node plus its body — the two halves the export fetches separately. */
    private record Node(DocumentTreeRow row, String description) {}

    private static Node doc(
            long id, String title, Long parentId, int position, String description) {
        return node(id, title, parentId, position, description, DocumentType.DOCUMENT);
    }

    private static Node folder(
            long id, String title, Long parentId, int position, String description) {
        return node(id, title, parentId, position, description, DocumentType.FOLDER);
    }

    private static Node node(
            long id,
            String title,
            Long parentId,
            int position,
            String description,
            DocumentType type) {
        return new Node(
                new DocumentTreeRow(
                        id,
                        parentId,
                        title,
                        type,
                        position,
                        false,
                        LocalDateTime.of(2026, 6, 14, 12, 0)),
                description);
    }

    /**
     * Wires the tree into the mocked repository the way the database serves it: children of one
     * parent ordered by position, and bodies only ever by id.
     */
    private void stubTree(List<Node> nodes) {
        Map<Long, String> bodies = new HashMap<>();
        Map<Long, DocumentTreeRow> rows = new LinkedHashMap<>();
        Map<Long, List<DocumentTreeRow>> byParent = new HashMap<>();

        for (Node node : nodes) {
            rows.put(node.row().id(), node.row());
            bodies.put(node.row().id(), node.description());
            byParent.computeIfAbsent(node.row().parentId(), k -> new ArrayList<>()).add(node.row());
        }
        byParent.values()
                .forEach(
                        level ->
                                level.sort(
                                        Comparator.comparingInt(DocumentTreeRow::position)
                                                .thenComparing(DocumentTreeRow::title)));

        when(repo.findTreeRowsByParent(any()))
                .thenAnswer(
                        invocation -> byParent.getOrDefault(invocation.getArgument(0), List.of()));
        when(repo.findTreeRowById(anyLong()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(rows.get(invocation.<Long>getArgument(0))));
        when(repo.findDescriptionById(anyLong()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(bodies.get(invocation.<Long>getArgument(0))));
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(exportDir.resolve(relativePath));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    @Nested
    class Layout {

        @Test
        void writesFolderAndDocumentFilesWithMeta() {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, "Folder body"),
                            doc(2, "Intro", 1L, 0, "Intro body"),
                            doc(3, "API", 1L, 1, "API body"),
                            doc(4, "Root Doc", null, 1, "Top body")));

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
        void omitsSidecarYamlWhenMetaDisabled() {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, "Folder body"),
                            doc(2, "Intro", 1L, 0, "b")));

            service.exportAll(false);

            assertThat(exportDir.resolve("docs/intro.md")).exists();
            assertThat(exportDir.resolve("docs/intro.yaml")).doesNotExist();
            assertThat(exportDir.resolve("docs/.meta.yaml")).doesNotExist();
            // .content.md is always created (may be empty)
            assertThat(exportDir.resolve("docs/.content.md")).exists();
        }

        @Test
        void rootIndexListsChildrenInPositionOrder() throws Exception {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, ""),
                            doc(4, "Root Doc", null, 1, "Top body")));

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

        @Test
        void writesRootIndexForAnEmptyTree() {
            stubTree(List.of());

            int count = service.exportAll(true);

            assertThat(exportDir.resolve(".index.md")).exists();
            assertThat(count).isEqualTo(1);
        }

        @Test
        void disambiguatesSiblingsThatNormaliseToTheSameName() {
            stubTree(
                    List.of(
                            doc(1, "Intro!", null, 0, "first"),
                            doc(2, "Intro?", null, 1, "second")));

            service.exportAll(false);

            assertThat(exportDir.resolve("intro.md")).exists();
            assertThat(exportDir.resolve("intro-1.md")).exists();
        }
    }

    // ── Link rewriting ──────────────────────────────────────────────────────────

    @Nested
    class LinkRewriting {

        @Test
        void rewritesInternalDocLinkToRelativePath() throws Exception {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, ""),
                            doc(2, "Intro", 1L, 0, "See [API doc](/?doc=3) for details."),
                            doc(3, "API", 1L, 1, "API body")));

            service.exportAll(true);

            // intro.md and api.md are siblings, so the rewritten link is just the file name.
            assertThat(read("docs/intro.md"))
                    .contains("[API doc](api.md)")
                    .doesNotContain("/?doc=3");
        }

        @Test
        void rewritesCrossFolderLinkToRelativePath() throws Exception {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, ""),
                            doc(2, "Intro", 1L, 0, "Jump to [top](/?doc=4)."),
                            doc(4, "Root Doc", null, 1, "Top body")));

            service.exportAll(true);

            // From docs/intro.md up to root-doc.md → "../root-doc.md".
            assertThat(read("docs/intro.md")).contains("[top](../root-doc.md)");
        }

        @Test
        void leavesUnresolvedDocLinkUnchanged() throws Exception {
            stubTree(List.of(doc(2, "Intro", null, 0, "Broken [missing](/?doc=999) link.")));

            service.exportAll(true);

            assertThat(read("intro.md")).contains("[missing](/?doc=999)");
        }

        @Test
        void rewritesLinkPointingToFolderContent() throws Exception {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, "Folder body"),
                            doc(4, "Root Doc", null, 1, "Go to [folder](/?doc=1).")));

            service.exportAll(true);

            // Folder targets resolve to the folder's .content.md.
            assertThat(read("root-doc.md")).contains("[folder](docs/.content.md)");
        }

        @Test
        void flattensRepoFileLinks() throws Exception {
            stubTree(
                    List.of(
                            doc(
                                    2,
                                    "Intro",
                                    null,
                                    0,
                                    "See [Git.java](/files?path=backend/Git.java#L1-L10).")));

            service.exportAll(false);

            assertThat(read("intro.md")).contains("Git.java (backend/Git.java)");
        }
    }

    // ── Subtree stream (folder download) ──────────────────────────────────────

    @Nested
    class SubtreeStream {

        @Test
        void emitsTheFolderItselfAndItsChildrenUnderTheFolderName() {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, "Folder body"),
                            doc(2, "Intro", 1L, 0, "Intro body"),
                            doc(4, "Root Doc", null, 1, "outside the subtree")));

            Map<String, String> entries = collectSubtree(1L, false);

            assertThat(entries)
                    .containsOnlyKeys("docs/.content.md", "docs/.index.md", "docs/intro.md");
            assertThat(entries.get("docs/.content.md")).isEqualTo("Folder body\n");
            assertThat(entries.get("docs/.index.md")).isEqualTo("- [Intro](intro.md)\n");
        }

        @Test
        void addsMetadataSidecarsOnlyWhenAsked() {
            stubTree(List.of(folder(1, "Docs", null, 0, ""), doc(2, "Intro", 1L, 0, "body")));

            assertThat(collectSubtree(1L, true)).containsKeys("docs/.meta.yaml", "docs/intro.yaml");
            assertThat(collectSubtree(1L, false))
                    .doesNotContainKeys("docs/.meta.yaml", "docs/intro.yaml");
        }

        @Test
        void keepsLinksThatLeaveTheSubtreeAsAppLinks() {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, ""),
                            doc(2, "Intro", 1L, 0, "Out: [top](/?doc=4)"),
                            doc(4, "Root Doc", null, 1, "Top body")));

            assertThat(collectSubtree(1L, false).get("docs/intro.md")).contains("[top](/?doc=4)");
        }

        @Test
        void refusesToStreamADocument() {
            stubTree(List.of(doc(2, "Intro", null, 0, "body")));

            assertThat(
                            org.junit.jupiter.api.Assertions.assertThrows(
                                    ResponseStatusException.class, () -> collectSubtree(2L, false)))
                    .hasMessageContaining("folder");
        }

        @Test
        void rendersASingleDocumentWithoutSurroundingExport() {
            stubTree(List.of(doc(2, "Intro", null, 0, "Body with [link](/?doc=9)")));

            // Nothing to be relative to, so the app link survives untouched.
            assertThat(service.renderSingleDocument(2)).isEqualTo("Body with [link](/?doc=9)\n");
        }

        private Map<String, String> collectSubtree(long rootId, boolean meta) {
            Map<String, String> entries = new LinkedHashMap<>();
            service.streamSubtree(rootId, meta, e -> entries.put(e.path(), e.content()));
            return entries;
        }
    }

    // ── Whole-tree stream (archive download) ──────────────────────────────────

    /**
     * The archive has to be the export folder, not merely something like it: what a user unpacks,
     * edits and puts back is read by the very comparison that expects the export's own layout.
     */
    @Nested
    class ArchiveStream {

        @Test
        void producesTheSameFilesTheFolderExportWouldWrite() throws Exception {
            stubTree(
                    List.of(
                            folder(1, "Docs", null, 0, "Folder body"),
                            doc(2, "Intro", 1L, 0, "Intro body"),
                            doc(4, "Root Doc", null, 1, "Root body")));

            Map<String, String> archive = collectAll(true);
            service.exportAll(true);

            assertThat(archive).isNotEmpty();
            for (Map.Entry<String, String> entry : archive.entrySet()) {
                assertThat(read(entry.getKey()))
                        .as("archive entry %s", entry.getKey())
                        .isEqualTo(entry.getValue());
            }
            // Nothing landed on disk that the archive left out either.
            assertThat(archive).containsOnlyKeys(filesUnder(exportDir));
        }

        @Test
        void carriesNoWrappingDirectory() {
            stubTree(List.of(folder(1, "Docs", null, 0, ""), doc(2, "Intro", 1L, 0, "body")));

            assertThat(collectAll(false))
                    .containsOnlyKeys(
                            "docs/.content.md", "docs/.index.md", "docs/intro.md", ".index.md");
        }

        @Test
        void stillCarriesTheRootIndexForAnEmptyTree() {
            stubTree(List.of());

            assertThat(collectAll(false)).containsExactly(entry(".index.md", ""));
        }

        private Map<String, String> collectAll(boolean meta) {
            Map<String, String> entries = new LinkedHashMap<>();
            service.streamAll(meta, e -> entries.put(e.path(), e.content()));
            return entries;
        }
    }

    /** Every file under {@code dir}, as {@code dir}-relative {@code /}-joined paths. */
    private static String[] filesUnder(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .toArray(String[]::new);
        }
    }

    // ── Metadata ────────────────────────────────────────────────────────────────

    @Test
    void metaYamlContainsCoreFields() throws Exception {
        stubTree(List.of(doc(2, "Intro \"quoted\"", null, 0, "body")));

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
        stubTree(List.of(doc(2, "Intro", null, 0, "body")));

        // intro.md + intro.yaml + root .index.md = 3 files.
        int count = service.exportAll(true);

        assertThat(count).isEqualTo(3);
    }

    @Test
    void reportsOneProgressEventPerNode() {
        stubTree(
                List.of(
                        folder(1, "Docs", null, 0, ""),
                        doc(2, "Intro", 1L, 0, "body"),
                        doc(4, "Root Doc", null, 1, "body")));

        List<String> paths = new ArrayList<>();
        service.exportAll(false, event -> paths.add(Objects.requireNonNull(event.path())));

        assertThat(paths).containsExactly("docs", "docs/intro", "root-doc");
    }
}
