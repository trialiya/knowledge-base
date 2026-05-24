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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p>Content and (optionally) metadata are written as separate files:
 *
 * <ul>
 *   <li>{@code .md} — document/folder description (content)
 *   <li>{@code .yaml} — structured metadata (id, title, type, timestamps, …), only when {@code
 *       includeMeta=true}
 * </ul>
 *
 * <p>Internal KB links ({@code /?doc=ID}) are rewritten to relative file paths so the exported
 * Markdown is navigable in any file browser or static site.
 *
 * <pre>
 *   &lt;exportPath&gt;/
 *     001.my-folder/
 *       .meta.yaml            ← metadata of "my-folder" (only with includeMeta)
 *       .content.md           ← description of "my-folder" (if any)
 *       001.some-document.md
 *       001.some-document.yaml  ← only with includeMeta
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

    /** Matches internal KB doc links inside Markdown link targets: {@code (/?doc=123)}. */
    private static final Pattern DOC_LINK_PATTERN = Pattern.compile("\\(/\\?doc=(\\d+)\\)");

    private final DocumentRepository repo;
    private final DocumentsConfiguration config;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Exports the entire document tree to {@code exportPath} with metadata files included.
     * Convenience overload — equivalent to {@code exportAll(true)}.
     *
     * @return the number of files written
     */
    public int exportAll() {
        return exportAll(true);
    }

    /**
     * Exports the entire document tree to {@code exportPath}. Existing files are overwritten;
     * directories are created as needed.
     *
     * <p>Internal {@code /?doc=ID} links in document content are rewritten to relative file paths
     * so the exported Markdown is self-contained.
     *
     * @param includeMeta whether to write {@code .yaml} / {@code .meta.yaml} sidecar files
     * @return the number of files written
     */
    public int exportAll(boolean includeMeta) {
        Path root = Paths.get(config.exportPath());

        List<DocumentEntity> all = Streams.stream(repo.findAll()).collect(Collectors.toList());

        // Build parent → children index
        Map<Long, List<DocumentEntity>> byParent =
                all.stream()
                        .filter(e -> e.getParentId() != null)
                        .collect(Collectors.groupingBy(DocumentEntity::getParentId));

        byParent.values()
                .forEach(list -> list.sort(Comparator.comparingInt(DocumentEntity::getPosition)));

        List<DocumentEntity> roots = repo.findRoots();
        roots.sort(Comparator.comparingInt(DocumentEntity::getPosition));

        // Pass 1: build id → absolute .md path map (needed for link rewriting)
        Map<Long, Path> idToPath = new HashMap<>();
        int ordinal = 1;
        for (DocumentEntity rootDoc : roots) {
            collectPaths(rootDoc, root, byParent, ordinal++, idToPath);
        }

        // Pass 2: write files
        int count = 0;
        ordinal = 1;
        for (DocumentEntity rootDoc : roots) {
            count += exportNode(rootDoc, root, byParent, ordinal++, idToPath, includeMeta);
        }

        log.info("Export complete: {} file(s) written to {}", count, root.toAbsolutePath());
        return count;
    }

    // ── Pass 1: collect id → path ────────────────────────────────────────────

    /**
     * Dry-run walk that populates {@code idToPath} with the absolute {@code .md} path each entity
     * will be written to. Folder content maps to the {@code .content.md} hidden file inside the
     * folder directory.
     */
    private void collectPaths(
            DocumentEntity entity,
            Path parentDir,
            Map<Long, List<DocumentEntity>> byParent,
            int ordinal,
            Map<Long, Path> idToPath) {

        boolean isFolder = "folder".equalsIgnoreCase(entity.getType());
        String prefix = padOrdinal(ordinal);

        if (isFolder) {
            Path folderDir = parentDir.resolve(prefix + "." + safeName(entity.getTitle()));
            idToPath.put(entity.getId(), folderDir.resolve(FOLDER_CONTENT_FILE));

            List<DocumentEntity> children = childrenOf(entity.getId(), byParent);
            int childOrdinal = 1;
            for (DocumentEntity child : children) {
                collectPaths(child, folderDir, byParent, childOrdinal++, idToPath);
            }
        } else {
            Path candidate = parentDir.resolve(prefix + "." + safeName(entity.getTitle()) + ".md");
            if (Files.exists(candidate) && !config.replace()) {
                int suffix = 1;
                do {
                    candidate =
                            parentDir.resolve(
                                    prefix
                                            + "."
                                            + safeName(entity.getTitle())
                                            + "-"
                                            + suffix
                                            + ".md");
                    suffix++;
                } while (Files.exists(candidate));
            }
            idToPath.put(entity.getId(), candidate);
        }
    }

    // ── Pass 2: recursive tree walk ──────────────────────────────────────────

    private int exportNode(
            DocumentEntity entity,
            Path parentDir,
            Map<Long, List<DocumentEntity>> byParent,
            int ordinal,
            Map<Long, Path> idToPath,
            boolean includeMeta) {

        boolean isFolder = "folder".equalsIgnoreCase(entity.getType());
        String prefix = padOrdinal(ordinal);
        int written = 0;

        if (isFolder) {
            Path folderDir = parentDir.resolve(prefix + "." + safeName(entity.getTitle()));
            createDirectories(folderDir);

            if (includeMeta) {
                writeFile(folderDir.resolve(FOLDER_META_FILE), renderMeta(entity));
                written++;
            }

            if (hasContent(entity.getDescription())) {
                Path contentFile = folderDir.resolve(FOLDER_CONTENT_FILE);
                writeFile(contentFile, renderContent(entity, contentFile, idToPath));
                written++;
                log.debug("Written folder content: {}", contentFile);
            }

            List<DocumentEntity> children = childrenOf(entity.getId(), byParent);
            int childOrdinal = 1;
            for (DocumentEntity child : children) {
                written +=
                        exportNode(
                                child, folderDir, byParent, childOrdinal++, idToPath, includeMeta);
            }

        } else {
            String baseName = prefix + "." + safeName(entity.getTitle());
            createDirectories(parentDir);

            Path contentFile = uniquePath(parentDir, baseName + ".md");
            writeFile(contentFile, renderContent(entity, contentFile, idToPath));
            written++;
            log.debug("Written document content: {}", contentFile);

            if (includeMeta) {
                String contentFileName = contentFile.getFileName().toString();
                String metaFileName =
                        contentFileName.substring(0, contentFileName.lastIndexOf('.')) + ".yaml";
                writeFile(parentDir.resolve(metaFileName), renderMeta(entity));
                written++;
                log.debug("Written document meta: {}", parentDir.resolve(metaFileName));
            }
        }

        return written;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Renders the document/folder description as Markdown content, rewriting any {@code /?doc=ID}
     * links to relative file paths based on {@code idToPath}.
     *
     * @param e entity to render
     * @param thisFile absolute path of the file being written (used to compute relative links)
     * @param idToPath map of document id → absolute export path
     */
    private String renderContent(DocumentEntity e, Path thisFile, Map<Long, Path> idToPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.getTitle()).append("\n\n");
        if (hasContent(e.getDescription())) {
            sb.append(rewriteDocLinks(e.getDescription().trim(), thisFile, idToPath)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Replaces every {@code (/?doc=ID)} occurrence in {@code text} with a relative Markdown path
     * pointing from {@code sourceFile} to the target document's exported {@code .md} file.
     *
     * <p>If the ID is not found in {@code idToPath} the original link is left unchanged.
     */
    private String rewriteDocLinks(String text, Path sourceFile, Map<Long, Path> idToPath) {
        Matcher m = DOC_LINK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            long targetId = Long.parseLong(m.group(1));
            Path targetPath = idToPath.get(targetId);
            if (targetPath == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                String rel =
                        sourceFile.getParent().relativize(targetPath).toString().replace('\\', '/');
                m.appendReplacement(out, Matcher.quoteReplacement("(" + rel + ")"));
            }
        }
        m.appendTail(out);
        return out.toString();
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
