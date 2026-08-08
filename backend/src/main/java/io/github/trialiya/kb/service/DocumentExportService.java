package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.service.DocumentTreeReader.FOLDER_CONTENT_FILE;
import static io.github.trialiya.kb.service.DocumentTreeReader.FOLDER_META_FILE;
import static io.github.trialiya.kb.service.DocumentTreeReader.INDEX_FILE;
import static io.github.trialiya.kb.service.DocumentTreeReader.MD_EXTENSION;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.model.doc.sync.SyncEvent;
import io.github.trialiya.kb.service.DocumentTreeReader.SegmentNamer;
import io.github.trialiya.kb.service.DocumentTreeReader.TreeNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Renders the document tree as a directory of Markdown files — onto {@code
 * kb.documents.export-path} ({@link #exportAll}) or straight into a response stream ({@link
 * #streamSubtree}).
 *
 * <h2>Layout</h2>
 *
 * The directory structure mirrors the tree. Names carry no ordinal prefixes; sibling order lives in
 * a {@code .index.md} written in every directory, including the export root.
 *
 * <ul>
 *   <li>{@code .md} — document body (description only, no title heading)
 *   <li>{@code .yaml} — document metadata (id, title, type, …), only when {@code includeMeta}
 *   <li>{@code .index.md} — ordered children of this directory; folders link to their {@code
 *       .content.md}
 *   <li>{@code .content.md} — folder description (always created, may be empty)
 *   <li>{@code .meta.yaml} — folder metadata, only when {@code includeMeta}
 * </ul>
 *
 * <pre>
 *   &lt;exportPath&gt;/
 *     .index.md               ← ordered list of root-level items
 *     my-folder/
 *       .meta.yaml            ← metadata of "my-folder" (only with includeMeta)
 *       .content.md           ← description of "my-folder" (always created)
 *       .index.md             ← ordered list of children
 *       some-document.md
 *       some-document.yaml    ← only with includeMeta
 *     another-doc.md
 * </pre>
 *
 * <h2>Two passes, and what each of them holds</h2>
 *
 * Internal {@code /?doc=ID} links become relative file paths so the export is navigable in any
 * Markdown viewer, and a document may link to one the walk has not reached yet. So the tree is
 * walked twice: pass 1 decides every node's file name, pass 2 writes the files.
 *
 * <p>What survives between the passes is the id → file-name map and nothing else — no bodies. Each
 * body is fetched by id at the moment its file is written and dropped immediately after, so the
 * peak memory of an export is one document, not the knowledge base. Pass 2 replays pass 1's names
 * from that map rather than re-deriving them: with {@code kb.documents.replace=false} the naming
 * depends on what is already on disk, and re-deriving it after pass 2 has started writing would
 * hand out different names than the links were built against.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportService {

    private final DocumentTreeReader tree;
    private final DocumentsConfiguration config;

    /** One rendered file: where it goes and what is in it. Held one at a time, never collected. */
    public record ExportEntry(String path, String content) {}

    // ── Export to the server folder ──────────────────────────────────────────

    /** Exports the whole tree with metadata sidecars. */
    public int exportAll() {
        return exportAll(true);
    }

    /** Exports the whole tree, reporting nothing. */
    public int exportAll(boolean includeMeta) {
        return exportAll(includeMeta, event -> {});
    }

    /**
     * Exports the whole tree to {@code kb.documents.export-path}, creating directories as needed
     * and overwriting existing files (unless {@code kb.documents.replace} is off, in which case a
     * colliding name gets a {@code -1}, {@code -2} … suffix).
     *
     * @param includeMeta whether to write {@code .yaml} / {@code .meta.yaml} sidecars
     * @param progress receives one {@link SyncEvent.Type#PROGRESS} per node as it is written
     * @return the number of files written
     */
    public int exportAll(boolean includeMeta, Consumer<SyncEvent> progress) {
        Path root = Paths.get(requireExportPath());
        createDirectories(root);

        Map<Long, String> idToFile = collectFiles(null, namerFor(root));
        Counter written = new Counter();
        Counter nodes = new Counter();

        streamNodes(
                null,
                idToFile,
                includeMeta,
                entry -> {
                    writeFile(root.resolve(entry.path()), entry.content());
                    written.value++;
                },
                node -> {
                    nodes.value++;
                    progress.accept(SyncEvent.progress(nodes.value, node.path()));
                });

        log.info("Export complete: {} file(s) written to {}", written.value, root.toAbsolutePath());
        return written.value;
    }

    // ── Export to a stream (archive download) ────────────────────────────────

    /**
     * The whole tree as a sequence of files, laid out exactly the way {@link #exportAll} would have
     * written it into the export folder — same names, same {@code .index.md}, same link rewriting —
     * but handed to {@code sink} instead of to the disk.
     *
     * <p>That sameness is the point: the archive a user downloads unpacks into a folder {@link
     * DocumentSyncService} can compare and import back. It also needs no {@code
     * kb.documents.export-path} at all, which is the difference that matters to whoever cannot
     * reach the server's file system.
     *
     * <p>Entries carry no wrapping directory — unpacking gives the root level of the knowledge
     * base, the way unpacking a folder's archive gives that folder.
     */
    public void streamAll(boolean includeMeta, Consumer<ExportEntry> sink) {
        streamNodes(null, collectFiles(null), includeMeta, sink, node -> {});
    }

    // ── Export to a stream (subtree download) ────────────────────────────────

    /**
     * Renders the subtree rooted at {@code rootId} as a sequence of files, handing each one to
     * {@code sink} and forgetting it. Nothing is buffered: the caller can zip straight into the
     * response, so a folder download costs the memory of its largest single document rather than of
     * the whole subtree.
     *
     * <p>Entry paths are prefixed with the root folder's own name, so unpacking the archive
     * reproduces the folder rather than scattering its children. Links pointing outside the subtree
     * stay as {@code /?doc=ID} — there is no file to point them at.
     *
     * @throws ResponseStatusException 404 when the node does not exist
     * @throws ResponseStatusException 422 when the node is not a folder
     */
    public void streamSubtree(long rootId, boolean includeMeta, Consumer<ExportEntry> sink) {
        DocumentTreeRow root = requireRow(rootId);
        if (!root.isFolder()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Subtree download requires a folder");
        }
        String base = DocumentTreeReader.safeName(root.title());

        // The root's own body sits at ".content.md", exactly where a folder one level up would put
        // it — so relative links computed inside the subtree stay correct once everything is
        // prefixed with `base/`.
        Map<Long, String> idToFile = collectFiles(rootId, DocumentTreeReader.dedupingNamer());
        idToFile.put(rootId, FOLDER_CONTENT_FILE);

        Consumer<ExportEntry> prefixed =
                entry -> sink.accept(new ExportEntry(base + "/" + entry.path(), entry.content()));

        prefixed.accept(new ExportEntry(FOLDER_CONTENT_FILE, renderBody(root, "", idToFile)));
        if (includeMeta) {
            prefixed.accept(new ExportEntry(FOLDER_META_FILE, renderMeta(root)));
        }

        streamNodes(rootId, idToFile, includeMeta, prefixed, node -> {});
    }

    /**
     * Walks a subtree and hands over every file it consists of, in the order an export writes them:
     * a node's body and metadata sidecar as it is reached, then a directory's {@code .index.md}
     * once that whole level is known.
     *
     * <p>The one spine under all three renderings — folder export, archive download, subtree
     * download — so what a user unpacks is what the server would have written, and either can be
     * read back by the import.
     *
     * @param onNode called once per node, after its files; the export reports progress through it
     */
    private void streamNodes(
            @Nullable Long rootId,
            Map<Long, String> idToFile,
            boolean includeMeta,
            Consumer<ExportEntry> sink,
            Consumer<TreeNode> onNode) {

        Counter rootIndex = new Counter();
        tree.walk(
                rootId,
                replayNamer(idToFile),
                new DocumentTreeReader.Visitor() {
                    @Override
                    public void node(TreeNode node) {
                        renderNode(node, idToFile, includeMeta).forEach(sink);
                        onNode.accept(node);
                    }

                    @Override
                    public void levelDone(String parentDir, List<TreeNode> level) {
                        sink.accept(renderIndex(parentDir, level));
                        if (parentDir.isEmpty()) {
                            rootIndex.value++;
                        }
                    }
                });

        // An empty tree produces no levels at all, so the top-level index needs its own guarantee:
        // whoever reads the result back has to find the file there, listing nothing.
        if (rootIndex.value == 0) {
            sink.accept(new ExportEntry(INDEX_FILE, ""));
        }
    }

    /**
     * The Markdown body of a single document, as its {@code .md} file would look. Used by the
     * single-document download, where there is no surrounding export: internal links have nowhere
     * relative to point and are left as {@code /?doc=ID}.
     *
     * @throws ResponseStatusException 404 when the document does not exist
     */
    public String renderSingleDocument(long id) {
        return renderBody(requireRow(id), "", Map.of());
    }

    /** File name a download should offer for a node. */
    public String downloadName(DocumentTreeRow row) {
        return DocumentTreeReader.safeName(row.title()) + (row.isFolder() ? ".zip" : MD_EXTENSION);
    }

    public DocumentTreeRow requireRow(long id) {
        return tree.row(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such node"));
    }

    // ── Pass 1 ───────────────────────────────────────────────────────────────

    /**
     * The id → body-file map for a subtree, as the export would lay it out. {@link
     * DocumentSyncService} needs the very same map: comparing disk against database means rendering
     * the database side exactly as an export would have written it, links included.
     */
    Map<Long, String> collectFiles(@Nullable Long rootId) {
        return collectFiles(rootId, DocumentTreeReader.dedupingNamer());
    }

    /** Walks the tree for names only, producing the id → body-file map the links are built on. */
    private Map<Long, String> collectFiles(@Nullable Long rootId, SegmentNamer namer) {
        Map<Long, String> idToFile = new HashMap<>();
        tree.walk(rootId, namer, node -> idToFile.put(node.row().id(), node.contentFile()));
        return idToFile;
    }

    /**
     * Names as usual, but when {@code kb.documents.replace} is off also steps around files a
     * previous export left behind.
     */
    private SegmentNamer namerFor(Path root) {
        if (config.replace()) {
            return DocumentTreeReader.dedupingNamer();
        }
        return (parentDir, row, taken) ->
                DocumentTreeReader.claim(
                        DocumentTreeReader.safeName(row.title()),
                        taken,
                        segment -> {
                            String candidate =
                                    prefix(
                                            parentDir,
                                            row.isFolder() ? segment : segment + MD_EXTENSION);
                            return Files.exists(root.resolve(candidate));
                        });
    }

    /**
     * Hands back the names pass 1 already decided. Falls back to the plain safe name for a node
     * that appeared between the passes — it gets a file, just possibly not a unique one.
     */
    private static SegmentNamer replayNamer(Map<Long, String> idToFile) {
        return (parentDir, row, taken) -> {
            String file = idToFile.get(row.id());
            String path = file == null ? DocumentTreeReader.safeName(row.title()) : pathOf(file);
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        };
    }

    /** Inverse of {@link TreeNode#contentFile()} — the node path a body file belongs to. */
    private static String pathOf(String contentFile) {
        String folderSuffix = "/" + FOLDER_CONTENT_FILE;
        return contentFile.endsWith(folderSuffix)
                ? contentFile.substring(0, contentFile.length() - folderSuffix.length())
                : DocumentTreeReader.stripMdExtension(contentFile);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /** The files one node owns: its body, and its metadata sidecar when asked for. */
    private List<ExportEntry> renderNode(
            TreeNode node, Map<Long, String> idToFile, boolean includeMeta) {
        ExportEntry body =
                new ExportEntry(
                        node.contentFile(), renderBody(node.row(), node.contentFile(), idToFile));
        return includeMeta
                ? List.of(body, new ExportEntry(node.metaFile(), renderMeta(node.row())))
                : List.of(body);
    }

    /**
     * The node's description with its links translated for the file system. The body is fetched
     * here and nowhere else — one at a time, by id.
     *
     * <p>The title is intentionally absent: it lives in {@code .index.md} and in the metadata
     * sidecar. An empty string comes back for a node with no description (an empty folder).
     */
    String renderBody(DocumentTreeRow row, String ownFile, Map<Long, String> idToFile) {
        String description = tree.description(row.id()).orElse(null);
        if (description == null || description.isBlank()) {
            return "";
        }
        String text = DocumentLinkRewriter.toRelativeLinks(description.trim(), ownFile, idToFile);
        return DocumentLinkRewriter.flattenFileLinks(text) + "\n";
    }

    /**
     * A directory's {@code .index.md}: the ordered children as a Markdown list. This is where
     * sibling order and the human title survive the round trip — the file names carry neither.
     */
    private ExportEntry renderIndex(String parentDir, List<TreeNode> level) {
        String indexFile = prefix(parentDir, INDEX_FILE);
        StringBuilder sb = new StringBuilder();
        for (TreeNode child : level) {
            sb.append("- [")
                    .append(child.row().title())
                    .append("](")
                    .append(DocumentLinkRewriter.relativize(indexFile, child.contentFile()))
                    .append(")\n");
        }
        return new ExportEntry(indexFile, sb.toString());
    }

    /** Structured metadata as YAML. */
    private String renderMeta(DocumentTreeRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(row.id()).append("\n");
        sb.append("title: \"").append(escapeYaml(row.title())).append("\"\n");
        sb.append("type: ").append(row.type().getValue()).append("\n");
        if (row.parentId() != null) {
            sb.append("parentId: ").append(row.parentId()).append("\n");
        }
        sb.append("position: ").append(row.position()).append("\n");
        sb.append("updatedAt: ").append(row.updatedAt()).append("\n");
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String requireExportPath() {
        String path = config.exportPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Export path is not configured (kb.documents.export-path)");
        }
        return path;
    }

    /** Joins a directory and a name, tolerating an empty directory (the export root). */
    static String prefix(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directory: " + dir, e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write file: " + path, e);
        }
    }

    /** Escapes double quotes for safe YAML string values. */
    private static String escapeYaml(@Nullable String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Mutable int the lambdas above can bump — a local would have to be effectively final. */
    private static final class Counter {
        int value;
    }
}
