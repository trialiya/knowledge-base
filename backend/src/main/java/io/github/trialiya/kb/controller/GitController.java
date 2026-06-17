package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.service.GitService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Git endpoints backing the chat composer's {@code /file} autocomplete.
 *
 * <p>{@code GET /search} fuzzy-matches tracked file names for the picker; {@code GET /content}
 * returns a file (optionally a line range) so an inserted chip can be previewed and expanded into
 * the outgoing message. Both delegate to {@link GitService}, which enforces tracked-files-only
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
        return gitService.searchFiles(query, limit);
    }

    /** File content for chip preview/expansion; {@code from}/{@code to} are 1-based inclusive. */
    @GetMapping("/files/content")
    public GitFileContent getFileContent(
            @RequestParam("path") String path,
            @RequestParam(name = "from", required = false) Integer from,
            @RequestParam(name = "to", required = false) Integer to) {
        return gitService.getFileContent(path, from, to);
    }
}
