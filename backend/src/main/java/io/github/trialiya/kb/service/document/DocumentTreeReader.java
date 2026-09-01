package io.github.trialiya.kb.service.document;

import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Depth-first walk of the document tree in export order, <b>one level per query and one body at a
 * time</b>.
 *
 * <p>This is the shared spine of export, subtree download and disk↔DB sync. All three used to be
 * written as "load the whole {@code documents} table, group it by parent, recurse over the map",
 * which puts every document body in the heap before the first byte is written. Here the walk only
 * ever holds the current level's {@link DocumentTreeRow}s (structure, no bodies) plus the path
 * bookkeeping, and a body is fetched by {@link #description(long)} at the moment it is needed and
 * dropped right after.
 *
 * <h2>Paths</h2>
 *
 * A node's <b>path</b> is the {@code /}-joined chain of {@link #safeName(String) safe names} from
 * the walk root down to the node, with no extension — {@code modeli-dannykh/dokumenty}. It is the
 * identity used everywhere: the export writes files at it, the ZIP download names entries after it,
 * and sync matches a directory entry to a database node by it. The concrete files around a node are
 * derived from it ({@link TreeNode#contentFile()}, {@link TreeNode#metaFile()}, {@link
 * TreeNode#indexFile()}).
 *
 * <p>Two siblings can normalise to the same safe name (different titles, same punctuation-stripped
 * form). Disambiguation with a {@code -1}, {@code -2} … suffix happens <b>once</b>, here, so every
 * consumer of the walk derives the same path for the same node — a link map built in one pass and
 * the files written in another cannot drift apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTreeReader {

    /** Hidden file that stores a folder's own content (description). */
    public static final String FOLDER_CONTENT_FILE = ".content.md";

    /** Hidden file that stores a folder's metadata. */
    public static final String FOLDER_META_FILE = ".meta.yaml";

    /** File written in every directory listing its children in their defined order. */
    public static final String INDEX_FILE = ".index.md";

    /** Extension of a document's body file. */
    public static final String MD_EXTENSION = ".md";

    /**
     * Hard stop for the recursion. {@code parent_id} is not constrained against cycles at the
     * database level, and a cycle would otherwise walk until the stack gives out. No real knowledge
     * base nests this deep.
     */
    private static final int MAX_DEPTH = 64;

    private final DocumentRepository repo;

    /**
     * A node together with the path the export gives it.
     *
     * @param row structural row — no body
     * @param path {@code /}-joined safe-name chain, no extension, relative to the walk root
     * @param depth 0 for the direct children of the walk root
     */
    public record TreeNode(DocumentTreeRow row, String path, int depth) {

        public boolean isFolder() {
            return row.isFolder();
        }

        /** Where this node's own description lives. */
        public String contentFile() {
            return isFolder() ? path + "/" + FOLDER_CONTENT_FILE : path + MD_EXTENSION;
        }

        /** Where this node's metadata sidecar lives. */
        public String metaFile() {
            return isFolder() ? path + "/" + FOLDER_META_FILE : path + ".yaml";
        }

        /** Where the ordered list of this folder's children lives ({@code null} for documents). */
        public @Nullable String indexFile() {
            return isFolder() ? path + "/" + INDEX_FILE : null;
        }

        /** Directory holding this node's files — {@code ""} at the walk root. */
        public String parentDir() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    /**
     * Decides the file-name segment for a node. The default ({@link #dedupingNamer()}) only avoids
     * collisions within the level being walked; the export composes it with a check against what is
     * already on disk when {@code kb.documents.replace} is off.
     */
    @FunctionalInterface
    public interface SegmentNamer {
        /**
         * @param parentDir directory the node lands in, {@code ""} at the walk root
         * @param row the node being named
         * @param takenInLevel segments already handed out for this level; the namer must add to it
         * @return the segment to use
         */
        String name(String parentDir, DocumentTreeRow row, Set<String> takenInLevel);
    }

    /** Receives the walk. {@link #levelDone} is what lets a caller write a directory index. */
    public interface Visitor {

        /** One node, parents before children. */
        void node(TreeNode node);

        /**
         * Called once per directory, after every node of that level <em>and all their subtrees</em>
         * have been visited — so a caller can emit the level's {@code .index.md} at the one moment
         * the level is both complete and still in hand. Only levels of the current branch are held
         * at a time, which is what keeps a directory listing from accumulating tree-wide.
         *
         * @param parentDir directory the level lives in, {@code ""} at the walk root
         * @param level the level's nodes in export order (empty levels are not reported)
         */
        default void levelDone(String parentDir, List<TreeNode> level) {}
    }

    // ── Walking ──────────────────────────────────────────────────────────────

    /** {@link #walk(Long, SegmentNamer, Visitor)} with the default in-level deduping namer. */
    public void walk(@Nullable Long rootId, Visitor visitor) {
        walk(rootId, dedupingNamer(), visitor);
    }

    /**
     * Visits every descendant of {@code rootId} (or of the tree root when {@code null})
     * depth-first, parents before children, siblings in {@code position} order — the order the
     * export writes them in.
     *
     * <p>The walk root itself is not visited: callers that need it hold it already (subtree
     * download) or are exporting the whole tree (where no root node exists).
     */
    public void walk(@Nullable Long rootId, SegmentNamer namer, Visitor visitor) {
        walkLevel(rootId, "", 0, namer, visitor);
    }

    private void walkLevel(
            @Nullable Long parentId,
            String parentDir,
            int depth,
            SegmentNamer namer,
            Visitor visitor) {

        if (depth >= MAX_DEPTH) {
            log.warn("Tree walk stopped at depth {} under parentId={} — cycle?", depth, parentId);
            return;
        }
        List<DocumentTreeRow> rows = repo.findTreeRowsByParent(parentId);
        if (rows.isEmpty()) {
            return;
        }
        Set<String> taken = new HashSet<>();
        List<TreeNode> level = new ArrayList<>(rows.size());
        for (DocumentTreeRow row : rows) {
            String segment = namer.name(parentDir, row, taken);
            String path = parentDir.isEmpty() ? segment : parentDir + "/" + segment;
            TreeNode node = new TreeNode(row, path, depth);
            level.add(node);
            visitor.node(node);
            if (row.isFolder()) {
                walkLevel(row.id(), path, depth + 1, namer, visitor);
            }
        }
        visitor.levelDone(parentDir, level);
    }

    /**
     * Ordered children of one level, structure only. Used by sync, which walks the disk and the
     * database side by side and therefore drives the recursion itself.
     */
    public List<DocumentTreeRow> children(@Nullable Long parentId) {
        return repo.findTreeRowsByParent(parentId);
    }

    /** One node's body, fetched on its own and meant to be dropped as soon as it is used. */
    public Optional<String> description(long id) {
        return repo.findDescriptionById(id);
    }

    public Optional<DocumentTreeRow> row(long id) {
        return repo.findTreeRowById(id);
    }

    // ── Naming ───────────────────────────────────────────────────────────────

    /** Suffixes a repeated segment with {@code -1}, {@code -2}, … within the level. */
    public static SegmentNamer dedupingNamer() {
        return (parentDir, row, taken) -> claim(safeName(row.title()), taken, segment -> false);
    }

    /**
     * Claims {@code base} in {@code taken}, appending {@code -1}, {@code -2}, … until the segment
     * is free both there and by {@code externallyTaken}.
     */
    public static String claim(String base, Set<String> taken, Predicate<String> externallyTaken) {
        String candidate = base;
        int suffix = 1;
        while (taken.contains(candidate) || externallyTaken.test(candidate)) {
            candidate = base + "-" + suffix++;
        }
        taken.add(candidate);
        return candidate;
    }

    /**
     * Converts a document title into a filesystem-safe name: lower-case, runs of anything that is
     * not a Latin/Cyrillic letter or a digit collapsed to a single hyphen, no leading or trailing
     * hyphens. Cyrillic is kept as-is rather than transliterated.
     */
    public static String safeName(@Nullable String title) {
        if (title == null || title.isBlank()) {
            return "untitled";
        }
        String name =
                title.trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9а-яё]+", "-")
                        .replaceAll("^-+|-+$", "");
        return name.isEmpty() ? "untitled" : name;
    }

    /** Strips the extension a document body file carries, leaving the node path. */
    public static String stripMdExtension(String fileName) {
        return fileName.endsWith(MD_EXTENSION)
                ? fileName.substring(0, fileName.length() - MD_EXTENSION.length())
                : fileName;
    }
}
