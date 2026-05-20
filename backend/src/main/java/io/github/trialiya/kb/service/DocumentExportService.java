package io.github.trialiya.kb.service;

import com.google.common.collect.Streams;
import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exports every document/folder to the configured {@code kb.documents.export-path}.
 *
 * <p>Layout on disk mirrors the document tree. Each node is prefixed with a zero-padded ordinal
 * that reflects its {@code position} among siblings, preserving the user-defined order.
 *
 * <p>Content and metadata are written as separate files:
 *
 * <ul>
 *   <li>{@code .md} — document/folder description (content)
 *   <li>{@code .yaml} — structured metadata (id, title, type, timestamps, …)
 * </ul>
 *
 * <pre>
 *   &lt;exportPath&gt;/
 *     001.my-folder/
 *       .meta.yaml            ← metadata of "my-folder"
 *       .content.md           ← description of "my-folder" (if any)
 *       001.some-document.md
 *       001.some-document.yaml
 *       002.nested-folder/
 *         .meta.yaml
 *         .content.md
 *         001.child-doc.md
 *         001.child-doc.yaml
 *     002.another-doc.md
 *     002.another-doc.yaml
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportService {

    /** Hidden file that stores a folder's own content (description). */
    static final String FOLDER_CONTENT_FILE = ".content.md";

    /** Hidden file that stores a folder's metadata. */
    static final String FOLDER_META_FILE = ".meta.yaml";

    /** Width of the zero-padded ordinal prefix (e.g. 3 → 001, 002, …). */
    private static final int ORDINAL_PAD = 3;

    private final DocumentRepository repo;
    private final DocumentsConfiguration config;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Exports the entire document tree to {@code exportPath}. Existing files are overwritten;
     * directories are created as needed.
     *
     * @return the number of files written
     */
    public int exportAll() {
        Path root = Paths.get(config.exportPath());

        // Build parent → children index, sorted by position
        Map<Long, List<DocumentEntity>> byParent =
                Streams.stream(repo.findAll())
                        .filter(e -> e.getParentId() != null)
                        .collect(Collectors.groupingBy(DocumentEntity::getParentId));

        // Sort each group by position
        byParent.values()
                .forEach(list -> list.sort(Comparator.comparingInt(DocumentEntity::getPosition)));

        List<DocumentEntity> roots = repo.findRoots();
        roots.sort(Comparator.comparingInt(DocumentEntity::getPosition));

        int count = 0;
        int ordinal = 1;
        for (DocumentEntity rootDoc : roots) {
            count += exportNode(rootDoc, root, byParent, ordinal++);
        }

        log.info("Export complete: {} file(s) written to {}", count, root.toAbsolutePath());
        return count;
    }

    // ── Recursive tree walk ──────────────────────────────────────────────────

    private int exportNode(
            DocumentEntity entity,
            Path parentDir,
            Map<Long, List<DocumentEntity>> byParent,
            int ordinal) {

        boolean isFolder = "folder".equalsIgnoreCase(entity.getType());
        String prefix = padOrdinal(ordinal);
        int written = 0;

        if (isFolder) {
            // Folder → directory with ordinal prefix
            Path folderDir = parentDir.resolve(prefix + "." + safeName(entity.getTitle()));
            createDirectories(folderDir);

            // Meta (always written for folders)
            writeFile(folderDir.resolve(FOLDER_META_FILE), renderMeta(entity));
            written++;

            // Content (only if folder has a description)
            if (hasContent(entity.getDescription())) {
                writeFile(folderDir.resolve(FOLDER_CONTENT_FILE), renderContent(entity));
                written++;
                log.debug("Written folder content: {}", folderDir.resolve(FOLDER_CONTENT_FILE));
            }

            // Recurse into children (already sorted by position)
            List<DocumentEntity> children = childrenOf(entity.getId(), byParent);
            int childOrdinal = 1;
            for (DocumentEntity child : children) {
                written += exportNode(child, folderDir, byParent, childOrdinal++);
            }

        } else {
            // Regular document → .md (content) + .yaml (meta)
            String baseName = prefix + "." + safeName(entity.getTitle());
            createDirectories(parentDir);

            // Content
            Path contentFile = uniquePath(parentDir, baseName + ".md");
            writeFile(contentFile, renderContent(entity));
            written++;
            log.debug("Written document content: {}", contentFile);

            // Meta — derive yaml name from the actual content file name (respects uniquePath
            // suffix)
            String contentFileName = contentFile.getFileName().toString();
            String metaFileName =
                    contentFileName.substring(0, contentFileName.lastIndexOf('.')) + ".yaml";
            Path metaFile = parentDir.resolve(metaFileName);
            writeFile(metaFile, renderMeta(entity));
            written++;
            log.debug("Written document meta: {}", metaFile);
        }

        return written;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /** Renders the document/folder description as Markdown content. */
    private String renderContent(DocumentEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.getTitle()).append("\n\n");
        if (hasContent(e.getDescription())) {
            sb.append(e.getDescription().trim()).append("\n");
        }
        return sb.toString();
    }

    /** Renders structured metadata as YAML. */
    private String renderMeta(DocumentEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(e.getId()).append("\n");
        sb.append("title: \"").append(escapeYaml(e.getTitle())).append("\"\n");
        sb.append("type: ").append(e.getType()).append("\n");
        if (e.getParentId() != null) {
            sb.append("parentId: ").append(e.getParentId()).append("\n");
        }
        sb.append("position: ").append(e.getPosition()).append("\n");
        sb.append("updatedAt: ").append(e.getUpdatedAt()).append("\n");
        return sb.toString();
    }

    // ── File-system helpers ──────────────────────────────────────────────────

    /**
     * Converts a document title into a filesystem-safe name: lower-case, spaces/special chars
     * replaced with hyphens, no leading/trailing hyphens.
     */
    static String safeName(String title) {
        if (title == null || title.isBlank()) {
            return "untitled";
        }
        return title.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9а-яёa-z]+", "-") // allow Cyrillic too
                .replaceAll("^-+|-+$", ""); // trim leading/trailing hyphens
    }

    /** Zero-pads an ordinal to {@link #ORDINAL_PAD} digits (e.g. 1 → "001"). */
    private static String padOrdinal(int ordinal) {
        return String.format("%0" + ORDINAL_PAD + "d", ordinal);
    }

    /**
     * Returns a path that does not collide with existing siblings. E.g. {@code 001.report.md} →
     * {@code 001.report-1.md} → {@code 001.report-2.md} …
     */
    private Path uniquePath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        } else if (!config.replace()) {
            int dot = fileName.lastIndexOf('.');
            String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot >= 0 ? fileName.substring(dot) : "";
            int suffix = 1;
            do {
                candidate = dir.resolve(base + "-" + suffix + ext);
                suffix++;
            } while (Files.exists(candidate));
            return candidate;
        } else {
            return candidate;
        }
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
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write file: " + path, e);
        }
    }

    /** Escapes double quotes for safe YAML string values. */
    private static String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Misc helpers ─────────────────────────────────────────────────────────

    private List<DocumentEntity> childrenOf(Long id, Map<Long, List<DocumentEntity>> byParent) {
        return byParent.getOrDefault(id, Collections.emptyList());
    }

    private boolean hasContent(String s) {
        return s != null && !s.isBlank();
    }
}
