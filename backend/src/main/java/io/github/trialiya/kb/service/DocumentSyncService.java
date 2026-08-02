package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.service.DocumentExportService.prefix;
import static io.github.trialiya.kb.service.DocumentTreeReader.FOLDER_CONTENT_FILE;
import static io.github.trialiya.kb.service.DocumentTreeReader.INDEX_FILE;
import static io.github.trialiya.kb.service.DocumentTreeReader.MD_EXTENSION;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The way back in: compares the export folder against the database, and imports the part of it the
 * caller picked.
 *
 * <h2>Compare first, import second</h2>
 *
 * {@link #diff} answers "what would importing change?" without touching a row — one {@link
 * SyncEntry} per node, streamed as it is decided. {@link #apply} then acts only on the paths the
 * caller ticked. That split is what makes a re-import safe: the previous take on this feature
 * always created new nodes, so running it twice duplicated the whole knowledge base.
 *
 * <h2>Identity is the path</h2>
 *
 * A disk entry and a database node are the same thing when their export paths match — the {@code
 * /}-joined chain of safe names ({@code DocumentTreeReader}). Nothing is read out of the {@code
 * .yaml} sidecars, so a folder exported without metadata imports exactly like one exported with it.
 * The cost is that a rename in the app reads as a delete plus an add, which the comparison shows
 * plainly rather than guessing at.
 *
 * <h2>What "modified" means</h2>
 *
 * The database side is rendered through {@link DocumentExportService#renderBody} — the very code
 * the export writes with — and compared against the bytes on disk. So a document compares equal
 * exactly when a fresh export would have produced the file that is there, and a folder that has
 * just been exported shows no changes at all. Line endings and surrounding blank lines are
 * normalised away.
 *
 * <h2>Memory</h2>
 *
 * One directory listing and one database level at a time, one body in hand at a time. What is held
 * for the whole run is path bookkeeping: the id ↔ file map the link rewriting needs, and the ids of
 * nodes an import has written so the second pass can find them again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSyncService {

    /** A {@code .index.md} line: {@code - [Title](relative/path.md)}. */
    private static final Pattern INDEX_ENTRY = Pattern.compile("^- \\[(.*?)]\\((.*?)\\)\\s*$");

    /**
     * Hard stop for the directory recursion. A symlink pointing at one of its own ancestors would
     * otherwise walk forever; the export never nests anywhere near this deep.
     */
    private static final int MAX_DEPTH = 64;

    private final DocumentTreeReader tree;
    private final DocumentExportService exportService;
    private final DocumentService documents;
    private final DocumentsConfiguration config;

    // ── Compare ──────────────────────────────────────────────────────────────

    /**
     * Walks the export folder and the database subtree side by side, emitting one {@link SyncEntry}
     * per node.
     *
     * @param parentId subtree the export folder maps onto, {@code null} for the tree root
     * @param sink receives an {@link SyncEvent.Type#ENTRY} per node, in tree order
     * @return the tally, also sent as the stream's final frame by the controller
     */
    public DiffSummary diff(@Nullable Long parentId, Consumer<SyncEvent> sink) {
        Path base = requireExportDir();
        requireFolder(parentId);

        Map<Long, String> idToFile = exportService.collectFiles(parentId);
        Tally tally = new Tally();
        compareDir(base, parentId, "", 0, idToFile, entry -> emit(sink, tally, entry));
        return tally.diffSummary();
    }

    private void compareDir(
            Path dir,
            @Nullable Long parentId,
            String pathPrefix,
            int depth,
            Map<Long, String> idToFile,
            Consumer<SyncEntry> sink) {

        if (tooDeep(depth, dir)) {
            return;
        }
        Map<String, DocumentTreeRow> unmatched = dbChildrenBySegment(parentId);

        for (DiskEntry disk : orderedDiskChildren(dir)) {
            String path = prefix(pathPrefix, disk.segment());
            DocumentTreeRow row = unmatched.remove(disk.segment());

            if (row == null) {
                sink.accept(
                        new SyncEntry(
                                path, disk.title(), disk.type(), SyncStatus.ADDED, null, depth));
                if (disk.folder()) {
                    markDiskSubtree(disk.dir(), path, depth + 1, sink);
                }
                continue;
            }
            boolean same =
                    row.title().equals(disk.title())
                            && row.isFolder() == disk.folder()
                            && normalize(readBody(disk.bodyFile()))
                                    .equals(
                                            normalize(
                                                    exportService.renderBody(
                                                            row, path(row, path), idToFile)));
            sink.accept(
                    new SyncEntry(
                            path,
                            disk.title(),
                            disk.type(),
                            same ? SyncStatus.UNCHANGED : SyncStatus.MODIFIED,
                            row.id(),
                            depth));
            if (disk.folder() && row.isFolder()) {
                compareDir(disk.dir(), row.id(), path, depth + 1, idToFile, sink);
            }
        }

        // Whatever the database still has at this level has no file behind it any more.
        for (DocumentTreeRow row : unmatched.values()) {
            String path = prefix(pathPrefix, DocumentTreeReader.safeName(row.title()));
            sink.accept(
                    new SyncEntry(
                            path, row.title(), row.type(), SyncStatus.MISSING, row.id(), depth));
            markDbSubtree(row.id(), path, depth + 1, sink);
        }
    }

    /** A folder that exists only on disk: everything under it is new too. */
    private void markDiskSubtree(Path dir, String pathPrefix, int depth, Consumer<SyncEntry> sink) {
        if (tooDeep(depth, dir)) {
            return;
        }
        for (DiskEntry disk : orderedDiskChildren(dir)) {
            String path = prefix(pathPrefix, disk.segment());
            sink.accept(
                    new SyncEntry(path, disk.title(), disk.type(), SyncStatus.ADDED, null, depth));
            if (disk.folder()) {
                markDiskSubtree(disk.dir(), path, depth + 1, sink);
            }
        }
    }

    /** A folder that exists only in the database: everything under it is missing too. */
    private void markDbSubtree(
            long parentId, String pathPrefix, int depth, Consumer<SyncEntry> sink) {
        for (DocumentTreeRow row : tree.children(parentId)) {
            String path = prefix(pathPrefix, DocumentTreeReader.safeName(row.title()));
            sink.accept(
                    new SyncEntry(
                            path, row.title(), row.type(), SyncStatus.MISSING, row.id(), depth));
            if (row.isFolder()) {
                markDbSubtree(row.id(), path, depth + 1, sink);
            }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * Applies the selected part of the export folder to the database.
     *
     * <p>Two passes again, for the same reason the export needs two: a document can link to one
     * that does not exist yet. Pass A creates and updates nodes with the file's text as it stands,
     * and records the path → id map. Pass B revisits only the nodes it wrote whose text actually
     * contained a link into the export, re-reads them from disk and turns those links back into
     * {@code /?doc=ID}. Everything else — the overwhelming majority of documents — is written once.
     *
     * <p>Each node is its own transaction: a title that cannot be applied (a system node) costs
     * that one node, not the run. Deletions happen last and only for {@link SyncStatus#MISSING}
     * paths that were both selected and allowed by {@link ImportRequest#deleteMissing()}.
     *
     * <p>A folder that is new on disk and <em>not</em> selected takes its whole subtree with it —
     * there would be no parent to attach the children to. Clients should tick the ancestors of
     * anything they tick.
     *
     * <p>Every node that is touched — or refused — emits a {@link SyncEvent.Type#PROGRESS} frame
     * naming its {@link SyncAction}, so the caller can show what the run did node by node instead
     * of only the four numbers at the end.
     */
    public ImportSummary apply(ImportRequest request, Consumer<SyncEvent> sink) {
        Path base = requireExportDir();
        requireFolder(request.parentId());

        Set<String> selection = request.selection();
        Tally tally = new Tally();
        List<Written> written = new ArrayList<>();
        List<Missing> toDelete = new ArrayList<>();

        importDir(base, request.parentId(), "", 0, selection, tally, written, toDelete, sink);

        Map<String, Long> fileToId = new HashMap<>();
        for (Map.Entry<Long, String> e :
                exportService.collectFiles(request.parentId()).entrySet()) {
            fileToId.put(e.getValue(), e.getKey());
        }
        relink(written, fileToId, tally, sink);

        if (request.deleteMissing()) {
            deleteAll(toDelete, tally, sink);
        }
        ImportSummary summary = tally.importSummary();
        log.info("Import from {} complete: {}", base.toAbsolutePath(), summary);
        return summary;
    }

    /** Pass A — create and update, top-down so a parent always exists before its children. */
    private void importDir(
            Path dir,
            @Nullable Long parentId,
            String pathPrefix,
            int depth,
            @Nullable Set<String> selection,
            Tally tally,
            List<Written> written,
            List<Missing> toDelete,
            Consumer<SyncEvent> sink) {

        if (tooDeep(depth, dir)) {
            return;
        }
        Map<String, DocumentTreeRow> unmatched = dbChildrenBySegment(parentId);

        for (DiskEntry disk : orderedDiskChildren(dir)) {
            String path = prefix(pathPrefix, disk.segment());
            DocumentTreeRow row = unmatched.remove(disk.segment());
            boolean selected = selection == null || selection.contains(path);

            Long id = row == null ? null : row.id();
            if (row == null) {
                if (!selected) {
                    // Nothing to attach a subtree to — skip it whole, see the method's contract.
                    continue;
                }
                id = create(disk, parentId, path, tally, written, sink);
                if (id == null) {
                    continue;
                }
            } else if (row.isFolder() != disk.folder()) {
                // A folder cannot become a document in place, and descending into the mismatch
                // would hang a subtree off a document. Report it and leave the node alone.
                failed(tally, path, new IllegalStateException("type changed on disk"), sink);
                continue;
            } else if (selected) {
                update(disk, row, path, tally, written, sink);
            }
            if (disk.folder()) {
                importDir(
                        disk.dir(), id, path, depth + 1, selection, tally, written, toDelete, sink);
            }
        }

        for (DocumentTreeRow row : unmatched.values()) {
            String path = prefix(pathPrefix, DocumentTreeReader.safeName(row.title()));
            queueMissing(row, path, selection, toDelete);
        }
    }

    /**
     * A database node with no disk entry behind it: queued for deletion if selected, then the same
     * for every child. Without this recursion a selected leaf deep in a missing subtree would never
     * be visited at all — nothing walks into a folder that only exists in the database, the way
     * {@link #compareDir} does for the same case via {@link #markDbSubtree}.
     */
    private void queueMissing(
            DocumentTreeRow row,
            String path,
            @Nullable Set<String> selection,
            List<Missing> toDelete) {
        if (selection == null || selection.contains(path)) {
            toDelete.add(new Missing(row.id(), path));
        }
        if (row.isFolder()) {
            for (DocumentTreeRow child : tree.children(row.id())) {
                queueMissing(
                        child,
                        prefix(path, DocumentTreeReader.safeName(child.title())),
                        selection,
                        toDelete);
            }
        }
    }

    private @Nullable Long create(
            DiskEntry disk,
            @Nullable Long parentId,
            String path,
            Tally tally,
            List<Written> written,
            Consumer<SyncEvent> sink) {

        String body = readBody(disk.bodyFile());
        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(disk.title());
        req.setType(disk.type());
        req.setParentId(parentId);
        req.setDescription(body.isBlank() ? null : body);
        try {
            long id = documents.create(req).id();
            tally.created++;
            record(written, disk, path, id, body);
            sink.accept(SyncEvent.progress(tally.processed(), path, SyncAction.CREATED));
            return id;
        } catch (RuntimeException e) {
            return failed(tally, path, e, sink);
        }
    }

    private void update(
            DiskEntry disk,
            DocumentTreeRow row,
            String path,
            Tally tally,
            List<Written> written,
            Consumer<SyncEvent> sink) {

        String body = readBody(disk.bodyFile());
        UpdateDocumentRequest req = new UpdateDocumentRequest();
        req.setTitle(row.title().equals(disk.title()) ? null : disk.title());
        req.setDescription(body);
        try {
            documents.update(row.id(), req);
            tally.updated++;
            record(written, disk, path, row.id(), body);
            sink.accept(SyncEvent.progress(tally.processed(), path, SyncAction.UPDATED));
        } catch (RuntimeException e) {
            failed(tally, path, e, sink);
        }
    }

    /** Only bodies that can actually resolve to another export file are worth a second visit. */
    private void record(List<Written> written, DiskEntry disk, String path, long id, String body) {
        if (DocumentLinkRewriter.hasRelativeLinks(body)) {
            written.add(new Written(id, path, disk.exportFile(path)));
        }
    }

    /** Pass B — turn relative links back into {@code /?doc=ID} now that every id is known. */
    private void relink(
            List<Written> written,
            Map<String, Long> fileToId,
            Tally tally,
            Consumer<SyncEvent> sink) {

        for (Written node : written) {
            String body = readBodyByExportPath(node.exportFile());
            String rewritten =
                    DocumentLinkRewriter.toDocLinks(body, node.exportFile(), fileToId::get);
            if (rewritten == null) {
                continue; // nothing resolved — the single write pass A did was enough
            }
            UpdateDocumentRequest req = new UpdateDocumentRequest();
            req.setDescription(rewritten);
            try {
                documents.update(node.id(), req);
                tally.relinked++;
                sink.accept(
                        SyncEvent.progress(tally.processed(), node.path(), SyncAction.RELINKED));
            } catch (RuntimeException e) {
                failed(tally, node.path(), e, sink);
            }
        }
    }

    private void deleteAll(List<Missing> missing, Tally tally, Consumer<SyncEvent> sink) {
        Set<Long> gone = new HashSet<>();
        for (Missing node : missing) {
            if (!gone.add(node.id())) {
                continue;
            }
            try {
                // Delete cascades, so a node already removed with its ancestor is simply absent.
                if (tree.row(node.id()).isPresent()) {
                    documents.delete(node.id());
                    tally.deleted++;
                    sink.accept(
                            SyncEvent.progress(tally.processed(), node.path(), SyncAction.DELETED));
                }
            } catch (RuntimeException e) {
                failed(tally, node.path(), e, sink);
            }
        }
    }

    /**
     * The exception text goes into the frame verbatim. It is the one place raw technical wording is
     * right: the row's own status line stays translated, and "три узла пропущены" without saying
     * which, or why, is exactly the report this feature was asked to stop producing.
     */
    private @Nullable Long failed(
            Tally tally, String path, RuntimeException e, Consumer<SyncEvent> sink) {
        tally.failed++;
        log.warn("Import skipped {}: {}", path, e.getMessage());
        sink.accept(SyncEvent.failure(tally.processed(), path, e.getMessage()));
        return null;
    }

    // ── The database side of a level ─────────────────────────────────────────

    /**
     * One database level keyed by the file-name segment the export would give each node, in tree
     * order. The dedup suffixes have to be handed out here exactly as the export hands them out, or
     * two siblings with the same safe name would match the wrong files.
     */
    private Map<String, DocumentTreeRow> dbChildrenBySegment(@Nullable Long parentId) {
        Map<String, DocumentTreeRow> bySegment = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (DocumentTreeRow row : tree.children(parentId)) {
            bySegment.put(
                    DocumentTreeReader.claim(
                            DocumentTreeReader.safeName(row.title()), taken, segment -> false),
                    row);
        }
        return bySegment;
    }

    /** Where the export puts this node's body, given the path it matched at. */
    private static String path(DocumentTreeRow row, String nodePath) {
        return row.isFolder() ? nodePath + "/" + FOLDER_CONTENT_FILE : nodePath + MD_EXTENSION;
    }

    // ── The disk side of a level ─────────────────────────────────────────────

    /**
     * Children of one directory in the order {@code .index.md} lists them, carrying the titles it
     * records. Files the index does not mention come after, in name order — an export always writes
     * the index, so that only happens for entries a human added by hand.
     */
    private List<DiskEntry> orderedDiskChildren(Path dir) {
        Map<String, DiskEntry> bySegment = new LinkedHashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            List<Path> sorted = new ArrayList<>();
            entries.forEach(sorted::add);
            sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path entry : sorted) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    bySegment.put(name, DiskEntry.ofFolder(entry, name));
                } else if (name.endsWith(MD_EXTENSION) && !name.startsWith(".")) {
                    String segment = DocumentTreeReader.stripMdExtension(name);
                    bySegment.put(segment, DiskEntry.ofDocument(entry, segment));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list directory: " + dir, e);
        }

        List<DiskEntry> ordered = new ArrayList<>(bySegment.size());
        for (IndexEntry index : parseIndex(dir)) {
            DiskEntry entry = bySegment.remove(index.segment());
            if (entry != null) {
                ordered.add(entry.withTitle(index.title()));
            }
        }
        ordered.addAll(bySegment.values());
        return ordered;
    }

    /** Parses a directory's {@code .index.md} into (title, segment) pairs in listed order. */
    private List<IndexEntry> parseIndex(Path dir) {
        Path index = dir.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        List<IndexEntry> entries = new ArrayList<>();
        for (String line : readBody(index).split("\n")) {
            Matcher m = INDEX_ENTRY.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            String target = m.group(2).trim();
            // A folder is listed through its .content.md; a document through its own .md.
            String segment =
                    target.endsWith(FOLDER_CONTENT_FILE)
                            ? target.substring(
                                    0, target.length() - FOLDER_CONTENT_FILE.length() - 1)
                            : DocumentTreeReader.stripMdExtension(target);
            if (!segment.isEmpty() && !segment.contains("/")) {
                entries.add(new IndexEntry(m.group(1).trim(), segment));
            }
        }
        return entries;
    }

    // ── Files ────────────────────────────────────────────────────────────────

    private String readBody(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file: " + file, e);
        }
    }

    private String readBodyByExportPath(String exportFile) {
        return readBody(Paths.get(requireExportPath()).resolve(exportFile));
    }

    private static boolean tooDeep(int depth, Path dir) {
        if (depth < MAX_DEPTH) {
            return false;
        }
        log.warn("Sync stopped at depth {} in {} — symlink loop?", depth, dir);
        return true;
    }

    /** Trailing whitespace and CRLF are formatting, not content. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private String requireExportPath() {
        String path = config.exportPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Export path is not configured (kb.documents.export-path)");
        }
        return path;
    }

    private Path requireExportDir() {
        Path base = Paths.get(requireExportPath());
        if (!Files.isDirectory(base)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Export folder does not exist: " + base.toAbsolutePath());
        }
        return base;
    }

    private void requireFolder(@Nullable Long parentId) {
        if (parentId == null) {
            return;
        }
        DocumentTreeRow row = exportService.requireRow(parentId);
        if (!row.isFolder()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Target parent must be a folder");
        }
    }

    private static void emit(Consumer<SyncEvent> sink, Tally tally, SyncEntry entry) {
        tally.count(entry.status());
        sink.accept(SyncEvent.entry(tally.processed(), entry));
    }

    // ── Carriers ─────────────────────────────────────────────────────────────

    /**
     * One entry of a directory listing.
     *
     * @param dir the directory itself, for folders; unused for documents
     * @param bodyFile file holding the node's description
     * @param segment file-name segment — the last part of the node path
     * @param title from {@code .index.md}, falling back to the segment
     */
    private record DiskEntry(
            Path dir, Path bodyFile, String segment, String title, boolean folder) {

        static DiskEntry ofFolder(Path dir, String segment) {
            return new DiskEntry(dir, dir.resolve(FOLDER_CONTENT_FILE), segment, segment, true);
        }

        static DiskEntry ofDocument(Path file, String segment) {
            // file is always nested under the export root, so it always has a parent.
            return new DiskEntry(
                    Objects.requireNonNull(file.getParent()), file, segment, segment, false);
        }

        DiskEntry withTitle(String title) {
            return new DiskEntry(dir, bodyFile, segment, title, folder);
        }

        DocumentType type() {
            return folder ? DocumentType.FOLDER : DocumentType.DOCUMENT;
        }

        /** Export-relative path of the body file, given the node path it matched at. */
        String exportFile(String nodePath) {
            return folder ? nodePath + "/" + FOLDER_CONTENT_FILE : nodePath + MD_EXTENSION;
        }
    }

    private record IndexEntry(String title, String segment) {}

    /** A node pass A wrote whose body still holds links pass B has to resolve. */
    private record Written(long id, String path, String exportFile) {}

    /**
     * A node queued for deletion. The path travels with the id purely so the log line can name the
     * node rather than its number — by the time the deletion runs, the row is about to be gone.
     */
    private record Missing(long id, String path) {}

    /** Counters shared by both directions; only one half is ever non-zero. */
    private static final class Tally {
        int added;
        int modified;
        int unchanged;
        int missing;
        int created;
        int updated;
        int deleted;
        int relinked;
        int failed;

        void count(SyncStatus status) {
            switch (status) {
                case ADDED -> added++;
                case MODIFIED -> modified++;
                case UNCHANGED -> unchanged++;
                case MISSING -> missing++;
            }
        }

        int processed() {
            return added + modified + unchanged + missing + created + updated + deleted + relinked
                    + failed;
        }

        DiffSummary diffSummary() {
            return new DiffSummary(added, modified, unchanged, missing);
        }

        ImportSummary importSummary() {
            return new ImportSummary(created, updated, deleted, relinked, failed);
        }
    }
}
