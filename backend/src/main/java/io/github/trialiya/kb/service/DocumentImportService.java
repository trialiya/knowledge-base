package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.DocumentType;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports (synchronises) the on-disk export folder ({@code kb.documents.export-path}) back into the
 * database. It is the inverse of {@link DocumentExportService}: it walks the directory tree, recreates
 * the documents/folders preserving order and titles, and rewrites the relative Markdown links back to
 * internal {@code /?doc=ID} links.
 *
 * <p>Layout understood (produced by the export):
 *
 * <ul>
 *   <li>sub-directory → folder; its body comes from {@code .content.md}, title from {@code .index.md}
 *       link text (or the directory name as a fallback);
 *   <li>{@code <name>.md} (non-hidden) → document; body is the file content, title from
 *       {@code .index.md} (or the file name as a fallback);
 *   <li>{@code .index.md} → defines the sibling order; entries not listed there are appended in name
 *       order.
 * </ul>
 *
 * <p>This is an additive import: it always creates new nodes under {@code parentId} (or root). It does
 * not delete or merge with existing rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentImportService {

    /** Markdown list link: {@code - [Title](relative/path.md)}. */
    private static final Pattern INDEX_ENTRY = Pattern.compile("- \\[(.*?)]\\((.*?)\\)");

    /** Any Markdown link target: {@code [text](target)} — used for reverse link rewriting. */
    private static final Pattern MD_LINK = Pattern.compile("\\]\\((.*?)\\)");

    static final String FOLDER_CONTENT_FILE = ".content.md";
    static final String INDEX_FILE = ".index.md";

    private final DocumentService documentService;
    private final DocumentsConfiguration config;

    /** Result of an import run. */
    public record ImportResult(int created, int folders, int documents) {}

    /**
     * Imports the configured export folder under {@code parentId} (or root when {@code null}).
     *
     * @throws IllegalStateException if the export folder is not configured or does not exist
     */
    @Transactional
    public ImportResult importFromFolder(Long parentId) {
        if (config.exportPath() == null || config.exportPath().isBlank()) {
            throw new IllegalStateException("Export path is not configured (kb.documents.export-path)");
        }
        Path base = Paths.get(config.exportPath());
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("Export folder does not exist: " + base.toAbsolutePath());
        }

        Counters counters = new Counters();
        // file (absolute, normalized) → created document id — for reverse link rewriting.
        Map<Path, Long> fileToId = new HashMap<>();
        // created id → (own file path, raw description) — pass 2 input.
        Map<Long, Pending> pending = new LinkedHashMap<>();

        importDir(base, parentId, counters, fileToId, pending);
        rewriteLinks(pending, fileToId);

        log.info(
                "Import complete: {} node(s) created ({} folders, {} documents) from {}",
                counters.folders + counters.documents,
                counters.folders,
                counters.documents,
                base.toAbsolutePath());
        return new ImportResult(counters.folders + counters.documents, counters.folders, counters.documents);
    }

    // ── Pass 1: walk the tree and create nodes ───────────────────────────────

    private void importDir(
            Path dir,
            Long parentId,
            Counters counters,
            Map<Path, Long> fileToId,
            Map<Long, Pending> pending) {

        for (Child child : orderedChildren(dir)) {
            if (child.isFolder()) {
                Path contentFile = child.path().resolve(FOLDER_CONTENT_FILE).normalize();
                String body = readIfExists(contentFile);
                long id = create(child.title(), DocumentType.FOLDER, parentId, body, counters);
                fileToId.put(contentFile, id);
                pending.put(id, new Pending(contentFile, body));
                importDir(child.path(), id, counters, fileToId, pending);
            } else {
                Path file = child.path().normalize();
                String body = readIfExists(file);
                long id = create(child.title(), DocumentType.DOCUMENT, parentId, body, counters);
                fileToId.put(file, id);
                pending.put(id, new Pending(file, body));
            }
        }
    }

    /**
     * Returns the ordered children of {@code dir}: order and titles come from {@code .index.md} when
     * present; anything not listed there is appended in directory name order.
     */
    private List<Child> orderedChildren(Path dir) {
        List<Child> result = new ArrayList<>();
        Map<Path, Child> remaining = new LinkedHashMap<>();

        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(
                            p -> {
                                String name = p.getFileName().toString();
                                if (Files.isDirectory(p)) {
                                    remaining.put(p.normalize(), new Child(p, name, true));
                                } else if (name.endsWith(".md") && !name.startsWith(".")) {
                                    String title = name.substring(0, name.length() - ".md".length());
                                    remaining.put(p.normalize(), new Child(p, title, false));
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list directory: " + dir, e);
        }

        // Apply order/titles from .index.md, if any.
        for (IndexEntry idx : parseIndex(dir)) {
            Path target = dir.resolve(idx.target()).normalize();
            Path key = idx.target().endsWith(FOLDER_CONTENT_FILE) ? target.getParent() : target;
            if (key == null) continue;
            Child c = remaining.remove(key);
            if (c != null) {
                result.add(new Child(c.path(), idx.title(), c.isFolder()));
            }
        }
        // Append everything not referenced by the index.
        result.addAll(remaining.values());
        return result;
    }

    private List<IndexEntry> parseIndex(Path dir) {
        Path index = dir.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        List<IndexEntry> entries = new ArrayList<>();
        Matcher m = INDEX_ENTRY.matcher(readIfExists(index));
        while (m.find()) {
            entries.add(new IndexEntry(m.group(1).trim(), m.group(2).trim()));
        }
        return entries;
    }

    private long create(
            String title, DocumentType type, Long parentId, String description, Counters counters) {
        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(title);
        req.setType(type.getValue());
        req.setParentId(parentId);
        req.setDescription(description == null || description.isBlank() ? null : description);
        Document created = documentService.create(req);
        if (type.isFolder()) {
            counters.folders++;
        } else {
            counters.documents++;
        }
        return created.id();
    }

    // ── Pass 2: rewrite relative links back to /?doc=ID ──────────────────────

    private void rewriteLinks(Map<Long, Pending> pending, Map<Path, Long> fileToId) {
        for (Map.Entry<Long, Pending> e : pending.entrySet()) {
            Pending p = e.getValue();
            if (p.description() == null || p.description().isBlank()) {
                continue;
            }
            Path ownDir = p.file().getParent();
            Matcher m = MD_LINK.matcher(p.description());
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String targetRaw = m.group(1);
                Long targetId = resolveTarget(ownDir, targetRaw, fileToId);
                if (targetId != null) {
                    m.appendReplacement(out, Matcher.quoteReplacement("](/?doc=" + targetId + ")"));
                    changed = true;
                } else {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(out);
            if (changed) {
                UpdateDocumentRequest req = new UpdateDocumentRequest();
                req.setDescription(out.toString());
                documentService.update(e.getKey(), req);
            }
        }
    }

    /** Resolves a relative Markdown link target to a created document id, or {@code null}. */
    private Long resolveTarget(Path ownDir, String targetRaw, Map<Path, Long> fileToId) {
        if (ownDir == null || targetRaw.isBlank() || targetRaw.startsWith("/") || targetRaw.contains("://")) {
            return null; // absolute / external / already an internal link
        }
        try {
            Path resolved = ownDir.resolve(targetRaw).normalize();
            return fileToId.get(resolved);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String readIfExists(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file: " + file, e);
        }
    }

    // ── Small carriers ───────────────────────────────────────────────────────

    private static final class Counters {
        int folders;
        int documents;
    }

    private record Child(Path path, String title, boolean isFolder) {}

    private record IndexEntry(String title, String target) {}

    private record Pending(Path file, String description) {}
}
