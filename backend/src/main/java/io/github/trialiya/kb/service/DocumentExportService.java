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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Exports every document/folder to the configured {@code kb.documents.export-path}.
 *
 * <p>Layout on disk mirrors the document tree:
 *
 * <pre>
 *   &lt;exportPath&gt;/
 *     .folder.md          ← description of the root virtual folder (optional)
 *     my-folder/
 *       .folder.md        ← description of "my-folder"
 *       some-document.md
 *       nested-folder/
 *         .folder.md
 *         child-doc.md
 * </pre>
 *
 * <p>File names are derived from the document title by replacing characters that are unsafe on most
 * filesystems with hyphens and lower-casing the result. Duplicate sibling names get a numeric
 * suffix ({@code -1}, {@code -2}, …).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportService {

    /** Reserved filename used to store a folder's own description. */
    static final String FOLDER_FILE = ".folder.md";

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
        // Build parent → children index
        Map<Long, List<DocumentEntity>> byParent =
                Streams.stream(repo.findAll())
                        .filter(e -> e.getParentId() != null)
                        .collect(Collectors.groupingBy(DocumentEntity::getParentId));

        List<DocumentEntity> roots = repo.findRoots();

        int[] count = {0};
        for (DocumentEntity rootDoc : roots) {
            count[0] += exportNode(rootDoc, root, byParent);
        }

        log.info("Export complete: {} file(s) written to {}", count[0], root.toAbsolutePath());
        return count[0];
    }

    // ── Recursive tree walk ──────────────────────────────────────────────────

    private int exportNode(
            DocumentEntity entity, Path parentDir, Map<Long, List<DocumentEntity>> byParent) {

        boolean isFolder = "folder".equalsIgnoreCase(entity.getType());
        int written = 0;

        if (isFolder) {
            // The folder itself becomes a directory; its description goes into .folder.md
            Path folderDir = parentDir.resolve(safeName(entity.getTitle()));
            createDirectories(folderDir);

            if (hasContent(entity.getDescription())) {
                Path folderMeta = folderDir.resolve(FOLDER_FILE);
                String md = renderFolderMd(entity);
                writeFile(folderMeta, md);
                written++;
                log.debug("Written folder meta: {}", folderMeta);
            }

            // Recurse into children
            for (DocumentEntity child : childrenOf(entity.getId(), byParent)) {
                written += exportNode(child, folderDir, byParent);
            }

        } else {
            // Regular document → single .md file in the current directory
            String fileName = safeName(entity.getTitle()) + ".md";
            Path file = uniquePath(parentDir, fileName);
            String md = renderDocumentMd(entity);
            createDirectories(parentDir);
            writeFile(file, md);
            written++;
            log.debug("Written document: {}", file);
        }

        return written;
    }

    // ── Markdown rendering ───────────────────────────────────────────────────

    private String renderDocumentMd(DocumentEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.getTitle()).append("\n\n");
        if (hasContent(e.getDescription())) {
            sb.append(e.getDescription().trim()).append("\n");
        }
        sb.append("\n---\n");
        sb.append("_Last updated: ").append(e.getUpdatedAt()).append("_\n");
        return sb.toString();
    }

    private String renderFolderMd(DocumentEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.getTitle()).append("\n\n");
        sb.append(e.getDescription().trim()).append("\n");
        sb.append("\n---\n");
        sb.append("_Last updated: ").append(e.getUpdatedAt()).append("_\n");
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

    /**
     * Returns a path that does not collide with existing siblings. E.g. {@code report.md} → {@code
     * report-1.md} → {@code report-2.md} …
     */
    private Path uniquePath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        } else if (!config.replace()) {
            // Split name and extension
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

    // ── Misc helpers ─────────────────────────────────────────────────────────

    private List<DocumentEntity> childrenOf(Long id, Map<Long, List<DocumentEntity>> byParent) {
        return byParent.getOrDefault(id, Collections.emptyList());
    }

    private boolean hasContent(String s) {
        return s != null && !s.isBlank();
    }
}
