package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.service.GitService;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Git endpoints backing the chat composer's {@code /file} autocomplete and the file
 * browser panel.
 *
 * <p>{@code GET /search} fuzzy-matches tracked file names for the picker; {@code GET /content}
 * returns a file (optionally a line range) so an inserted chip can be previewed and expanded into
 * the outgoing message; {@code GET /tree} lists the direct children of a directory for the file
 * browser tree. All delegate to {@link GitService}, which enforces tracked-files-only access,
 * path-traversal guards and binary/size limits.
 */
@RestController
@RequestMapping("/api/git")
public class GitController {

    private final GitService gitService;

    public GitController(GitService gitService) {
        this.gitService = gitService;
    }

    /** Fuzzy file-name search for the composer picker, e.g. {@code ?q=mgi} → MessageInput. */
    @GetMapping("/files/search")
    public List<GitFileNode> searchFiles(
            @RequestParam("q") String query,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        // Allow only letters (any script incl. Cyrillic), digits, dot, dash, underscore.
        String sanitized = query.replaceAll("[^\\p{L}\\p{N}_.\\-]", "");
        if (sanitized.isBlank()) return List.of();
        return gitService.searchFiles(sanitized, limit);
    }

    /** File content for chip preview/expansion; {@code from}/{@code to} are 1-based inclusive. */
    @GetMapping("/files/content")
    public GitFileContent getFileContent(
            @RequestParam("path") String path,
            @RequestParam(name = "from", required = false) Integer from,
            @RequestParam(name = "to", required = false) Integer to) {
        requireSafePath(path);
        return gitService.getFileContent(path, from, to);
    }

    /**
     * Direct children (files + subdirectories) of {@code path} for the file browser tree; omit
     * {@code path} for the repo root. Directories sort before files, then alphabetically.
     */
    @GetMapping("/tree")
    public List<GitFileNode> getTree(@RequestParam(name = "path", required = false) String path) {
        if (path != null && !path.isBlank()) {
            requireSafePath(path);
        }
        return gitService.getFileTree(path).stream()
                .sorted(
                        Comparator.<GitFileNode, Boolean>comparing(
                                        n -> !"directory".equals(n.type()))
                                .thenComparing(GitFileNode::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void requireSafePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not be blank");
        }
        String s = path.strip();
        if (s.startsWith("/") || s.startsWith("-") || s.contains("..") || s.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
    }
}
