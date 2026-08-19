package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.orDefault;
import static io.github.trialiya.kb.tools.ToolArgs.positiveOrDefault;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.GitRegistry;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ProjectContext;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that give the chat model read-only access to a configured Git repository.
 *
 * <p>Which repository is decided per call, not per bean: the project comes from the run's {@link
 * ToolContext} (see {@code ProjectContext}) and is resolved through {@code GitRegistry}, so a call
 * that names no project reads the default one. The {@code ToolContext} parameter is not part of a
 * tool's schema, so the model neither sees nor fills it in.
 *
 * <p>{@link #getFileContent} and {@link #grepContent} additionally take an optional {@code project}
 * argument that overrides the context's project for that one call — the model's way to ask a
 * cross-project question ("how does A do this, versus B") without switching the chat's project.
 * Every response from these two carries a {@code project} field naming the repository it actually
 * came from, so a model that forgets which call used the override still knows what it is looking
 * at.
 *
 * <p><b>Security constraints:</b> all operations are strictly read-only. Only files tracked by Git
 * are accessible — untracked files (including those matching {@code .gitignore}) are refused even
 * if they exist on disk. Binary files and files larger than 512 KB are detected and returned
 * without content so they never bloat the model context.
 */
@Slf4j
@AllArgsConstructor
public class GitFunction {

    private final GitRegistry gitRegistry;

    /** The repository this call works on — the run's project, or the default one. */
    private GitService git(@Nullable ToolContext context) {
        return gitRegistry.forProject(ProjectContext.from(context));
    }

    /**
     * As {@link #git(ToolContext)}, but {@code project} — when the model named one, for a
     * cross-project question — overrides the run's own project for this one call.
     */
    private GitService git(@Nullable ToolContext context, @Nullable String project) {
        return gitRegistry.forProject(ProjectContext.resolve(context, project));
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Lists tracked files and directories under the given sub-path (or repo root). Only shows the
     * immediate level — call again with a deeper path to drill down. Ignored files (.gitignore) are
     * excluded.
     *
     * @param path sub-path relative to repo root; null or empty for root level
     * @return list of file/directory nodes at the requested level
     */
    @Tool(
            description =
                    "Browse repository one level at a time (path, name, type, size). Call again with deeper path to drill down.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitFileNode> getFileTree(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Subdirectory path relative to repo root (e.g., \"src/main/java\"). Empty or null for root.",
                            required = false)
                    @Nullable String path) {
        log.info("getFileTree called: path='{}'", path);
        List<GitFileNode> fileTree = git(context).getFileTree(path);
        log.info("getFileTree called: fileTree={}", fileTree);
        return fileTree;
    }

    // ── Commit history ───────────────────────────────────────────────────────

    /**
     * Returns recent commit history from the repository.
     *
     * @param maxCount maximum number of commits to return (default 20, max 100)
     * @param filePath optional — show only commits that touched this file
     * @return list of commits with hash, author, date, and message
     */
    @Tool(
            description =
                    "Recent commit history (newest first). Commit: hash, shortHash, author, email, date (ISO-8601), message. Use getCommitDiff to see file changes.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitCommit> getCommitLog(
            ToolContext context,
            @ToolParam(
                            description = "Maximum commits to return (1–100, default 20).",
                            required = false)
                    @Nullable Integer maxCount,
            @ToolParam(
                            description =
                                    "Optional: file path (relative to repo root) to filter commits that touched it.",
                            required = false)
                    @Nullable String filePath) {
        final int limit = positiveOrDefault(maxCount, 20);
        log.info("getCommitLog called: maxCount={}, filePath='{}'", limit, filePath);
        List<GitCommit> commitLog = git(context).getCommitLog(limit, filePath);
        log.info("getCommitLog called: commitLog={}", commitLog);
        return commitLog;
    }

    // ── Commit diff ─────────────────────────────────────────────────────────

    /**
     * Returns the list of changed files (and optionally unified diff patches) for one or more
     * commits.
     *
     * @param commitHashes one or more commit hashes, comma-separated
     * @param includePatch if true, include the unified diff text for each file
     * @return list of commits with their changed files
     */
    @Tool(
            description =
                    "Changed files and diffs for one or more commits. Include status (A/M/D/R), path, additions, deletions, and optional unified diff.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitCommit> getCommitDiff(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Commit hash (full or short) or comma-separated list (e.g., \"abc1234\" or \"abc1234,def5678\").")
                    String commitHashes,
            @ToolParam(
                            description =
                                    "Include unified diff for each file (false=list only, true=includes patch text).",
                            required = false)
                    @Nullable Boolean includePatch,
            @ToolParam(
                            description =
                                    "Optional: file path to filter diff output to only that file.",
                            required = false)
                    @Nullable String filePath) {
        requireText(commitHashes, "commitHashes");
        final boolean patch = orDefault(includePatch, false);
        log.info(
                "getCommitDiff called: hashes='{}', includePatch={}, filePath='{}'",
                commitHashes,
                patch,
                filePath);
        List<GitCommit> commitDiff = git(context).getCommitDiff(commitHashes, patch, filePath);
        log.info("getCommitDiff called: commitDiff={}", commitDiff);
        return commitDiff;
    }

    // ── File search ─────────────────────────────────────────────────────────

    /**
     * Fuzzy-searches tracked file names (case-insensitive subsequence match), ranking results by
     * how well characters align to word boundaries. Ignored files are excluded.
     *
     * @param pattern partial file name; matched as a subsequence (e.g. "mgi" → MessageInput)
     * @param maxResults max results to return (default 20, max 50)
     * @return matching file nodes, best match first
     */
    @Tool(
            description =
                    "Fuzzy-search tracked files by name (case-insensitive subsequence; e.g., \"mgi\" → MessageInput). Results ranked by match quality.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitFileNode> searchFiles(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Partial file name pattern (fuzzy: case-insensitive subsequence match).")
                    String pattern,
            @ToolParam(
                            description = "Maximum results to return (1–50, default 20).",
                            required = false)
                    @Nullable Integer maxResults) {
        requireText(pattern, "pattern");
        final int limit = positiveOrDefault(maxResults, 20);
        log.info("searchFiles called: pattern='{}', maxResults={}", pattern, limit);
        List<GitFileNode> gitFileNodes = git(context).searchFiles(pattern, limit);
        log.info("searchFiles called: gitFileNodes={}", gitFileNodes);
        return gitFileNodes;
    }

    // ── File outline ──────────────────────────────────────────────────────────

    /**
     * Returns a structural outline (classes, methods, functions, ...) of a tracked source file
     * without its full text. Lets the model map a large file cheaply, then read only the relevant
     * lines via {@link #getFileContent}.
     *
     * @param filePath path relative to repo root
     * @return outline with symbols and their line ranges
     */
    @Tool(
            description =
                    "Structural outline of source code (classes, methods, functions) with line ranges, without full text.",
            resultConverter = CompactToolResultConverter.class)
    public GitFileOutline getFileOutline(
            ToolContext context,
            @ToolParam(description = "Source file path relative to repo root.") String filePath) {
        requireText(filePath, "filePath");
        log.info("getFileOutline called: filePath='{}'", filePath);
        GitFileOutline outline = git(context).getFileOutline(filePath);
        log.info("getFileOutline called: outline={}", outline);
        return outline;
    }

    // ── File content ────────────────────────────────────────────────────────

    /**
     * Returns the content of a tracked file, optionally limited to a line range. Binary files are
     * flagged without content. Files larger than 512 KB return a head+tail excerpt with {@code
     * truncated=true}.
     *
     * @param filePath path relative to repo root
     * @param fromLine first line to return (1-based, inclusive); null for start of file
     * @param toLine last line to return (1-based, inclusive); null for end of file
     * @return file content (full, ranged, or excerpt) with metadata
     */
    @Tool(
            description =
                    "Read file content (full or line range). Binary files flagged without content. "
                            + "Large files (>512 KB) return excerpt with truncated=true. When "
                            + "mentioning the file in your response, link it as "
                            + "[filename](/files?path=PATH&project=ID), where PATH is the path "
                            + "from the response and ID is the response's project field; append "
                            + "#Lfrom-Lto for a line range.",
            resultConverter = CompactToolResultConverter.class)
    public GitFileContent getFileContent(
            ToolContext context,
            @ToolParam(description = "File path relative to repo root.") String filePath,
            @ToolParam(
                            description =
                                    "First line to read (1-based, inclusive). Null for start of file.",
                            required = false)
                    @Nullable Integer fromLine,
            @ToolParam(
                            description =
                                    "Last line to read (1-based, inclusive). Null for end of file.",
                            required = false)
                    @Nullable Integer toLine,
            @ToolParam(
                            description =
                                    "Optional: read from a different project (repository id) than the "
                                            + "chat's active one, for a cross-project question. Omit to use "
                                            + "the active project. The response's \"project\" field names "
                                            + "which repository the content actually came from — check it, "
                                            + "don't assume.",
                            required = false)
                    @Nullable String project) {
        requireText(filePath, "filePath");
        log.info(
                "getFileContent called: filePath='{}', fromLine={}, toLine={}, project='{}'",
                filePath,
                fromLine,
                toLine,
                project);
        GitFileContent fileContent =
                git(context, project).getFileContent(filePath, fromLine, toLine);
        log.info("getFileContent called: fileContent='{}'", fileContent);
        return fileContent;
    }

    /**
     * Returns uncommitted changes to tracked files in the working tree. Untracked files (including
     * those matched by {@code .gitignore}) are not reported — same tracked-only rule the read tools
     * enforce.
     *
     * @param includePatch whether to include unified diff text for modified files (default false)
     */
    @Tool(
            name = "getUncommittedChanges",
            description =
                    "Uncommitted changes to tracked files in working tree (staged and unstaged); untracked files excluded. Status: A/M/D/R. Optional: include unified diff.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitDiffEntry> getUncommittedChanges(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Include unified diff for changed files (false=list only, true=includes patch, default false).",
                            required = false)
                    @Nullable Boolean includePatch) {
        final boolean patch = orDefault(includePatch, false);
        log.info("getUncommittedChanges called: includePatch='{}'", patch);
        List<GitDiffEntry> gitDiffEntries = git(context).getUncommittedChanges(patch);
        log.info("getUncommittedChanges called: gitDiffEntries='{}'", gitDiffEntries);
        return gitDiffEntries;
    }

    // ── Content grep ────────────────────────────────────────────────────────

    /**
     * Searches the text content of all tracked files for lines matching {@code pattern}.
     *
     * @param pattern literal string (or regex when {@code regex=true}) to search for
     * @param pathGlob optional glob pattern to restrict which files are searched
     * @param regex if true, treat pattern as an extended regular expression
     * @param contextLines lines of context before/after each match (0–10, default 1)
     * @param maxResults maximum number of matches to return (1–200, default 50)
     * @return list of matches with file path, line number, and line text
     */
    @Tool(
            description =
                    "Search file content for matching lines (case-insensitive). Returns path, line number, and text.",
            resultConverter = CompactToolResultConverter.class)
    public List<GitGrepMatch> grepContent(
            ToolContext context,
            @ToolParam(description = "Search pattern: literal string or regex (if regex=true).")
                    String pattern,
            @ToolParam(
                            description =
                                    "Optional: glob pattern to restrict search to certain files (e.g., \"*.java\", \"src/main/**\").",
                            required = false)
                    @Nullable String pathGlob,
            @ToolParam(
                            description =
                                    "Treat pattern as POSIX regex (true=regex, false=literal substring, default true).",
                            required = false)
                    @Nullable Boolean regex,
            @ToolParam(
                            description = "Context lines before/after match (0–10, default 1).",
                            required = false)
                    @Nullable Integer contextLines,
            @ToolParam(
                            description = "Maximum matches to return (1–200, default 50).",
                            required = false)
                    @Nullable Integer maxResults,
            @ToolParam(
                            description =
                                    "Optional: search a different project (repository id) than the "
                                            + "chat's active one, for a cross-project question. Omit to "
                                            + "use the active project. Each match's \"project\" field "
                                            + "names which repository it came from — check it, don't "
                                            + "assume.",
                            required = false)
                    @Nullable String project) {
        requireText(pattern, "pattern");
        final boolean useRegex = orDefault(regex, true);
        // contextLines defaults through orDefault rather than positiveOrDefault: 0 means "the
        // matching line only", a real answer the model can give. GitService clamps to 0–10.
        final int ctx = orDefault(contextLines, 1);
        final int limit = positiveOrDefault(maxResults, 50);
        log.info(
                "grepContent called: pattern='{}', pathGlob='{}', regex={}, contextLines={},"
                        + " maxResults={}, project='{}'",
                pattern,
                pathGlob,
                useRegex,
                ctx,
                limit,
                project);
        List<GitGrepMatch> matches =
                git(context, project).grepContent(pattern, pathGlob, useRegex, ctx, limit);
        log.info("grepContent called: {} matches found", matches.size());
        return matches;
    }
}
