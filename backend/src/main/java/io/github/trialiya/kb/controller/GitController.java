package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitPathView;
import io.github.trialiya.kb.service.GitService;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
 * the outgoing message; {@code GET /browse} opens one path in the file browser (content or listing
 * plus the ancestor directories) in a single round trip, while {@code GET /tree} lists the direct
 * children of a single directory (a chevron click in that tree); {@code GET /commits} returns
 * commit history for a path. All delegate to {@link GitService}, which enforces tracked-files-only
 * access, path-traversal guards and binary/size limits.
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
            @RequestParam(name = "from", required = false) @Nullable Integer from,
            @RequestParam(name = "to", required = false) @Nullable Integer to) {
        requireSafePath(path);
        return gitService.getFileContent(path, from, to);
    }

    /**
     * Commit history, newest first — optionally narrowed to one file or directory. The file
     * browser's "Info" panel asks for {@code limit=1} to show who last changed the selected path
     * and when; omit {@code path} for the repository's own history.
     */
    @GetMapping("/commits")
    public List<GitCommit> getCommits(
            @RequestParam(name = "path", required = false) @Nullable String path,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (path != null && !path.isBlank()) {
            requireSafePath(path);
        }
        return gitService.getCommitLog(limit, path);
    }

    /**
     * Commit lookup for the phrase placeholder picker: matches a hash prefix or a substring of the
     * commit message, newest first. History has no index for either, so matching is a bounded walk
     * — see {@link GitService#searchCommits}.
     */
    @GetMapping("/commits/search")
    public List<GitCommit> searchCommits(
            @RequestParam("q") String query,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        String sanitized = query.strip();
        if (sanitized.isBlank()) return List.of();
        return gitService.searchCommits(sanitized, limit);
    }

    /**
     * Opens {@code path} in the file browser in one round trip: what the path is (file / directory
     * / missing), its content or listing, and — unless {@code ancestors=false} — the listings of
     * every directory between the repo root and the path, so the tree can expand to it without a
     * request per level. Omit {@code path} for the repo root.
     *
     * <p>Clients that already have the ancestor listings cached (navigating inside the tree they
     * just loaded) pass {@code ancestors=false} and get only the path itself.
     */
    @GetMapping("/browse")
    public GitPathView browse(
            @RequestParam(name = "path", required = false) @Nullable String path,
            @RequestParam(name = "ancestors", defaultValue = "true") boolean ancestors) {
        if (path != null && !path.isBlank()) {
            requireSafePath(path);
        }
        return gitService.browsePath(path, ancestors);
    }

    /**
     * Direct children (files + subdirectories) of {@code path} for the file browser tree; omit
     * {@code path} for the repo root. Directories sort before files, then alphabetically.
     */
    @GetMapping("/tree")
    public List<GitFileNode> getTree(
            @RequestParam(name = "path", required = false) @Nullable String path) {
        if (path != null && !path.isBlank()) {
            requireSafePath(path);
        }
        return gitService.getFileTree(path);
    }

    private static void requireSafePath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not be blank");
        }
        String s = path.strip();
        if (s.startsWith("/") || s.startsWith("-") || s.contains("..") || s.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
    }
}
