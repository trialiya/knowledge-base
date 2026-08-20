package io.github.trialiya.kb.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.model.doc.sync.DiffSummary;
import io.github.trialiya.kb.model.doc.sync.ImportRequest;
import io.github.trialiya.kb.model.doc.sync.ImportSummary;
import io.github.trialiya.kb.model.doc.sync.SyncAction;
import io.github.trialiya.kb.model.doc.sync.SyncEntry;
import io.github.trialiya.kb.model.doc.sync.SyncEvent;
import io.github.trialiya.kb.model.doc.sync.SyncStatus;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests for {@link DocumentSyncService}.
 *
 * <p>The fixture is deliberately built by running the real {@link DocumentExportService} into a
 * {@code @TempDir} and then editing files by hand. That is the property that matters most: a
 * comparison run straight after an export must report nothing to do, and every status the tests
 * assert on is the consequence of an edit a person could actually make in the export folder.
 *
 * <p>The database lives in {@link FakeTree} — a small in-memory tree behind the two collaborators
 * the service uses, the repository for reads and {@link DocumentService} for writes. It is a fake
 * rather than a pile of stubs because the import has to see its own creates: a child is only
 * importable once its parent exists.
 */
class DocumentSyncServiceTest {

    @TempDir Path exportDir;

    private FakeTree db;
    private DocumentSyncService sync;
    private DocumentExportService export;

    @BeforeEach
    void setUp() {
        db = new FakeTree();
        DocumentsConfiguration config = new DocumentsConfiguration(exportDir.toString(), true);
        DocumentTreeReader reader = new DocumentTreeReader(db.repo());
        export = new DocumentExportService(reader, config);
        sync = new DocumentSyncService(reader, export, db.documents(), config);
    }

    // ── Compare ──────────────────────────────────────────────────────────────

    @Nested
    class Compare {

        @Test
        void reportsNothingToDoRightAfterAnExport() {
            standardTree();
            export.exportAll(false);

            assertThat(diff()).allMatch(e -> e.status() == SyncStatus.UNCHANGED);
        }

        @Test
        void sidecarMetadataDoesNotAffectTheComparison() {
            standardTree();
            export.exportAll(true);

            assertThat(diff()).allMatch(e -> e.status() == SyncStatus.UNCHANGED);
        }

        @Test
        void seesAFileThatIsNotInTheDatabaseYet() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/newcomer.md"), "brand new\n");

            assertThat(statusOf(diff(), "docs/newcomer")).isEqualTo(SyncStatus.ADDED);
        }

        @Test
        void seesAnEditedBody() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/intro.md"), "edited on disk\n");

            assertThat(statusOf(diff(), "docs/intro")).isEqualTo(SyncStatus.MODIFIED);
            assertThat(statusOf(diff(), "docs/api")).isEqualTo(SyncStatus.UNCHANGED);
        }

        @Test
        void seesARenameInTheIndex() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/.index.md"), "- [Renamed](intro.md)\n");

            List<SyncEntry> entries = diff();
            assertThat(statusOf(entries, "docs/intro")).isEqualTo(SyncStatus.MODIFIED);
            assertThat(entryOf(entries, "docs/intro").title()).isEqualTo("Renamed");
        }

        @Test
        void seesADeletedFileAsMissing() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.delete(exportDir.resolve("docs/api.md"));

            assertThat(statusOf(diff(), "docs/api")).isEqualTo(SyncStatus.MISSING);
        }

        @Test
        void marksAWholeNewFolderAndItsContents() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.createDirectories(exportDir.resolve("guides"));
            Files.writeString(exportDir.resolve("guides/.content.md"), "");
            Files.writeString(exportDir.resolve("guides/setup.md"), "how to set up\n");

            List<SyncEntry> entries = diff();
            assertThat(statusOf(entries, "guides")).isEqualTo(SyncStatus.ADDED);
            assertThat(statusOf(entries, "guides/setup")).isEqualTo(SyncStatus.ADDED);
        }

        @Test
        void marksAWholeRemovedFolderAndItsContents() throws Exception {
            standardTree();
            export.exportAll(false);
            deleteRecursively(exportDir.resolve("docs"));

            List<SyncEntry> entries = diff();
            assertThat(statusOf(entries, "docs")).isEqualTo(SyncStatus.MISSING);
            assertThat(statusOf(entries, "docs/intro")).isEqualTo(SyncStatus.MISSING);
            assertThat(statusOf(entries, "docs/api")).isEqualTo(SyncStatus.MISSING);
        }

        @Test
        void tallyMatchesTheEntriesItSummarises() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/intro.md"), "edited\n");
            Files.writeString(exportDir.resolve("extra.md"), "new\n");

            List<SyncEvent> events = new ArrayList<>();
            DiffSummary summary = sync.diff(null, events::add);

            assertThat(summary.added()).isEqualTo(1);
            assertThat(summary.modified()).isEqualTo(1);
            assertThat(summary.missing()).isZero();
            assertThat(summary.total()).isEqualTo(events.size());
        }

        @Test
        void refusesWhenTheExportFolderDoesNotExist() {
            DocumentsConfiguration missing =
                    new DocumentsConfiguration(exportDir.resolve("nope").toString(), true);
            DocumentSyncService service =
                    new DocumentSyncService(
                            new DocumentTreeReader(db.repo()), export, db.documents(), missing);

            assertThatThrownBy(() -> service.diff(null, e -> {}))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────

    @Nested
    class Import {

        @Test
        void createsOnlyTheSelectedEntries() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/wanted.md"), "keep me\n");
            Files.writeString(exportDir.resolve("docs/ignored.md"), "not this one\n");

            ImportSummary summary = apply(new ImportRequest(null, List.of("docs/wanted"), false));

            assertThat(summary.created()).isEqualTo(1);
            assertThat(db.titles()).contains("wanted").doesNotContain("ignored");
        }

        @Test
        void overwritesTheBodyOfAModifiedDocument() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/intro.md"), "edited on disk\n");

            ImportSummary summary = apply(new ImportRequest(null, List.of("docs/intro"), false));

            assertThat(summary.updated()).isEqualTo(1);
            assertThat(db.description(2L)).isEqualTo("edited on disk\n");
        }

        @Test
        void appliesARenameFromTheIndex() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/.index.md"), "- [Renamed](intro.md)\n");

            apply(new ImportRequest(null, List.of("docs/intro"), false));

            assertThat(db.title(2L)).isEqualTo("Renamed");
        }

        @Test
        void importingTwiceChangesNothingTheSecondTime() {
            standardTree();
            export.exportAll(false);

            ImportSummary first = apply(new ImportRequest(null, null, false));
            int nodesAfterFirst = db.size();
            ImportSummary second = apply(new ImportRequest(null, null, false));

            // Everything already matches, so a full-selection run is a no-op both times.
            assertThat(first.created()).isZero();
            assertThat(second.created()).isZero();
            assertThat(db.size()).isEqualTo(nodesAfterFirst);
        }

        @Test
        void turnsRelativeLinksBackIntoDocLinks() throws Exception {
            standardTree();
            export.exportAll(false);
            // A brand-new document linking to another brand-new one, the way the export writes it.
            Files.writeString(exportDir.resolve("docs/alpha.md"), "see [beta](beta.md)\n");
            Files.writeString(exportDir.resolve("docs/beta.md"), "the target\n");

            ImportSummary summary =
                    apply(new ImportRequest(null, List.of("docs/alpha", "docs/beta"), false));

            long betaId = db.idOfTitle("beta");
            assertThat(summary.created()).isEqualTo(2);
            assertThat(summary.relinked()).isEqualTo(1);
            assertThat(db.description(db.idOfTitle("alpha")))
                    .contains("[beta](/?doc=" + betaId + ")");
        }

        @Test
        void writesADocumentWithoutLinksExactlyOnce() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/plain.md"), "no links here\n");

            apply(new ImportRequest(null, List.of("docs/plain"), false));

            // One create, no second pass: the whole point of checking for links before revisiting.
            assertThat(db.writeCount(db.idOfTitle("plain"))).isEqualTo(1);
        }

        @Test
        void leavesMissingNodesAloneByDefault() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.delete(exportDir.resolve("docs/api.md"));

            ImportSummary summary = apply(new ImportRequest(null, null, false));

            assertThat(summary.deleted()).isZero();
            assertThat(db.titles()).contains("API");
        }

        @Test
        void deletesMissingNodesWhenExplicitlyAsked() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.delete(exportDir.resolve("docs/api.md"));

            ImportSummary summary = apply(new ImportRequest(null, List.of("docs/api"), true));

            assertThat(summary.deleted()).isEqualTo(1);
            assertThat(db.titles()).doesNotContain("API");
        }

        @Test
        void deletesANestedMissingLeafWithoutSelectingItsMissingParent() throws Exception {
            standardTree();
            export.exportAll(false);
            deleteRecursively(exportDir.resolve("docs"));

            // Only the grandchild is ticked — "docs" itself is never selected.
            ImportSummary summary = apply(new ImportRequest(null, List.of("docs/api"), true));

            assertThat(summary.deleted()).isEqualTo(1);
            assertThat(db.titles()).doesNotContain("API").contains("Docs", "Intro");
        }

        @Test
        void skipsANewSubtreeWhoseFolderWasNotSelected() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.createDirectories(exportDir.resolve("guides"));
            Files.writeString(exportDir.resolve("guides/.content.md"), "");
            Files.writeString(exportDir.resolve("guides/setup.md"), "how to\n");

            // Only the child is ticked — there would be no parent to hang it off.
            ImportSummary summary = apply(new ImportRequest(null, List.of("guides/setup"), false));

            assertThat(summary.created()).isZero();
            assertThat(db.titles()).doesNotContain("setup");
        }

        @Test
        void importsIntoASubtreeWhenGivenAParent() throws Exception {
            standardTree();
            Files.writeString(exportDir.resolve("standalone.md"), "loose page\n");
            Files.writeString(exportDir.resolve(".index.md"), "- [Standalone](standalone.md)\n");

            apply(new ImportRequest(1L, null, false));

            assertThat(db.parentOf(db.idOfTitle("Standalone"))).isEqualTo(1L);
        }

        @Test
        void reportsProgressPerWrittenNode() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/one.md"), "1\n");
            Files.writeString(exportDir.resolve("docs/two.md"), "2\n");

            List<SyncEvent> events = new ArrayList<>();
            sync.apply(
                    new ImportRequest(null, List.of("docs/one", "docs/two"), false), events::add);

            assertThat(events).extracting(SyncEvent::path).containsExactly("docs/one", "docs/two");
        }
    }

    // ── The log a run leaves behind ──────────────────────────────────────────

    /**
     * The counters say three nodes were created and one was skipped. Which ones, and why the fourth
     * — that only the per-node frames can answer, and without it an import that half worked is
     * indistinguishable from one that fully did.
     */
    @Nested
    class Journal {

        @Test
        void namesWhatItDidToEachNode() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/intro.md"), "edited\n");
            Files.writeString(exportDir.resolve("docs/fresh.md"), "brand new\n");

            List<SyncEvent> events =
                    applyCollecting(
                            new ImportRequest(null, List.of("docs/intro", "docs/fresh"), false));

            assertThat(events)
                    .extracting(SyncEvent::path, SyncEvent::action)
                    .containsExactly(
                            tuple("docs/intro", SyncAction.UPDATED),
                            tuple("docs/fresh", SyncAction.CREATED));
        }

        @Test
        void reportsTheSecondPassSeparatelyFromTheWriteThatPrecededIt() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.writeString(exportDir.resolve("docs/alpha.md"), "see [beta](beta.md)\n");
            Files.writeString(exportDir.resolve("docs/beta.md"), "the target\n");

            List<SyncEvent> events =
                    applyCollecting(
                            new ImportRequest(null, List.of("docs/alpha", "docs/beta"), false));

            // alpha is written twice on purpose — the log should say so rather than hide the
            // rewrite behind the create.
            assertThat(events)
                    .extracting(SyncEvent::path, SyncEvent::action)
                    .containsExactly(
                            tuple("docs/alpha", SyncAction.CREATED),
                            tuple("docs/beta", SyncAction.CREATED),
                            tuple("docs/alpha", SyncAction.RELINKED));
        }

        @Test
        void namesADeletedNodeByItsPath() throws Exception {
            standardTree();
            export.exportAll(false);
            Files.delete(exportDir.resolve("docs/api.md"));

            List<SyncEvent> events =
                    applyCollecting(new ImportRequest(null, List.of("docs/api"), true));

            assertThat(events)
                    .extracting(SyncEvent::path, SyncEvent::action)
                    .containsExactly(tuple("docs/api", SyncAction.DELETED));
        }

        @Test
        void carriesTheReasonASkippedNodeWasSkipped() throws Exception {
            standardTree();
            export.exportAll(false);
            // "Intro" turns from a document into a directory — the one edit the import refuses.
            Files.delete(exportDir.resolve("docs/intro.md"));
            Files.createDirectories(exportDir.resolve("docs/intro"));
            Files.writeString(exportDir.resolve("docs/intro/.content.md"), "now a folder\n");

            List<SyncEvent> events =
                    applyCollecting(new ImportRequest(null, List.of("docs/intro"), false));

            assertThat(events)
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.action()).isEqualTo(SyncAction.FAILED);
                                assertThat(event.path()).isEqualTo("docs/intro");
                                assertThat(event.message()).isEqualTo("type changed on disk");
                            });
        }

        /** The export walks the same tree but does one thing to every node — nothing to name. */
        @Test
        void exportProgressCarriesNoAction() {
            standardTree();

            List<SyncEvent> events = new ArrayList<>();
            export.exportAll(false, events::add);

            assertThat(events).isNotEmpty().extracting(SyncEvent::action).containsOnlyNulls();
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     *
     *
     * <pre>
     *   Docs/ (id=1, folder)
     *     Intro (id=2)
     *     API   (id=3)
     * </pre>
     */
    private void standardTree() {
        db.add(1, "Docs", null, 0, DocumentType.FOLDER, "Folder body");
        db.add(2, "Intro", 1L, 0, DocumentType.DOCUMENT, "Intro body");
        db.add(3, "API", 1L, 1, DocumentType.DOCUMENT, "API body");
    }

    private List<SyncEntry> diff() {
        List<SyncEntry> entries = new ArrayList<>();
        sync.diff(null, event -> entries.add(java.util.Objects.requireNonNull(event.entry())));
        return entries;
    }

    private ImportSummary apply(ImportRequest request) {
        return sync.apply(request, event -> {});
    }

    private List<SyncEvent> applyCollecting(ImportRequest request) {
        List<SyncEvent> events = new ArrayList<>();
        sync.apply(request, events::add);
        return events;
    }

    private static SyncEntry entryOf(List<SyncEntry> entries, String path) {
        return entries.stream()
                .filter(e -> e.path().equals(path))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no entry for "
                                                + path
                                                + " in "
                                                + entries.stream()
                                                        .map(SyncEntry::path)
                                                        .collect(Collectors.joining(", "))));
    }

    private static SyncStatus statusOf(List<SyncEntry> entries, String path) {
        return entryOf(entries, path).status();
    }

    private static void deleteRecursively(Path path) throws Exception {
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * An in-memory document tree behind the two collaborators the sync uses. Writes go through the
     * mocked {@link DocumentService} and land here, so the next read sees them — which is what lets
     * the import create a folder and then import into it.
     */
    private static final class FakeTree {

        private final Map<Long, DocumentTreeRow> rows = new LinkedHashMap<>();
        private final Map<Long, String> bodies = new HashMap<>();
        private final Map<Long, Integer> writes = new HashMap<>();
        private final DocumentRepository repo = mock(DocumentRepository.class);
        private final DocumentService documents = mock(DocumentService.class);
        private long nextId = 100;

        FakeTree() {
            when(repo.findTreeRowsByParent(any()))
                    .thenAnswer(invocation -> childrenOf(invocation.getArgument(0)));
            when(repo.findTreeRowById(anyLong()))
                    .thenAnswer(
                            invocation ->
                                    Optional.ofNullable(rows.get(invocation.<Long>getArgument(0))));
            when(repo.findDescriptionById(anyLong()))
                    .thenAnswer(
                            invocation ->
                                    Optional.ofNullable(
                                            bodies.get(invocation.<Long>getArgument(0))));
            when(documents.create(any()))
                    .thenAnswer(invocation -> create(invocation.getArgument(0)));
            when(documents.update(anyLong(), any()))
                    .thenAnswer(
                            invocation ->
                                    update(invocation.getArgument(0), invocation.getArgument(1)));
            org.mockito.Mockito.doAnswer(invocation -> remove(invocation.getArgument(0)))
                    .when(documents)
                    .delete(anyLong());
        }

        DocumentRepository repo() {
            return repo;
        }

        DocumentService documents() {
            return documents;
        }

        void add(
                long id,
                String title,
                Long parentId,
                int position,
                DocumentType type,
                String body) {
            rows.put(
                    id,
                    new DocumentTreeRow(
                            id, parentId, title, type, position, false, LocalDateTime.now()));
            bodies.put(id, body);
        }

        private List<DocumentTreeRow> childrenOf(Long parentId) {
            return rows.values().stream()
                    .filter(r -> java.util.Objects.equals(r.parentId(), parentId))
                    .sorted(
                            Comparator.comparingInt(DocumentTreeRow::position)
                                    .thenComparing(DocumentTreeRow::title))
                    .toList();
        }

        private Document create(CreateDocumentRequest req) {
            long id = nextId++;
            int position = childrenOf(req.getParentId()).size();
            DocumentType type = req.getType() == null ? DocumentType.DOCUMENT : req.getType();
            add(id, req.getTitle(), req.getParentId(), position, type, req.getDescription());
            writes.merge(id, 1, Integer::sum);
            return document(id);
        }

        private Document update(long id, UpdateDocumentRequest req) {
            DocumentTreeRow row = rows.get(id);
            if (row == null) {
                throw new IllegalStateException("no such node: " + id);
            }
            if (req.getTitle() != null) {
                rows.put(
                        id,
                        new DocumentTreeRow(
                                id,
                                row.parentId(),
                                req.getTitle(),
                                row.type(),
                                row.position(),
                                row.isSystem(),
                                LocalDateTime.now()));
            }
            if (req.getDescription() != null) {
                bodies.put(id, req.getDescription());
            }
            writes.merge(id, 1, Integer::sum);
            return document(id);
        }

        private Object remove(long id) {
            for (DocumentTreeRow child : childrenOf(id)) {
                remove(child.id());
            }
            rows.remove(id);
            bodies.remove(id);
            return null;
        }

        private Document document(long id) {
            DocumentTreeRow row = rows.get(id);
            return new Document(
                    id,
                    row.title(),
                    row.type().getValue(),
                    row.parentId(),
                    1,
                    1,
                    null,
                    null,
                    LocalDateTime.now(),
                    List.of(),
                    null,
                    false,
                    null);
        }

        // ── Assertions helpers ───────────────────────────────────────────────

        int size() {
            return rows.size();
        }

        List<String> titles() {
            return rows.values().stream().map(DocumentTreeRow::title).toList();
        }

        String title(long id) {
            return rows.get(id).title();
        }

        Long parentOf(long id) {
            return rows.get(id).parentId();
        }

        String description(long id) {
            return bodies.get(id);
        }

        int writeCount(long id) {
            return writes.getOrDefault(id, 0);
        }

        long idOfTitle(String title) {
            return rows.values().stream()
                    .filter(r -> r.title().equals(title))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no node titled " + title))
                    .id();
        }
    }
}
