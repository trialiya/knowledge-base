package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.service.GitService;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that give the chat model read-only access to the Git repository configured via
 * {@code kb.project-path}.
 *
 * <p>Five capabilities are exposed:
 *
 * <ul>
 *   <li>{@link #getFileTree} — browse tracked files/directories (respects .gitignore).
 *   <li>{@link #getCommitLog} — recent commit history, optionally filtered by file.
 *   <li>{@link #getCommitDiff} — changed files and patches for specific commit(s).
 *   <li>{@link #searchFiles} — find tracked files by name pattern.
 *   <li>{@link #getFileContent} — read full content of a tracked file.
 * </ul>
 *
 * <p>All operations are read-only; no modifications are made to the repository. Files excluded by
 * {@code .gitignore} are never returned.
 */
@Slf4j
@AllArgsConstructor
public class GitFunction {

    private final GitService gitService;

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
                    "Получить дерево файлов git-репозитория (tracked файлы, .gitignore исключены). "
                            + "Показывает один уровень вложенности — для раскрытия подкаталога вызови повторно с path.")
    public List<GitFileNode> getFileTree(
            @ToolParam(
                            description =
                                    "Путь к подкаталогу (относительно корня репозитория). "
                                            + "Пустая строка или null — корень.",
                            required = false)
                    String path) {
        log.info("getFileTree called: path='{}'", path);
        List<GitFileNode> fileTree = gitService.getFileTree(path);
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
                    "Получить историю коммитов git-репозитория. "
                            + "Можно ограничить количество и/или фильтровать по конкретному файлу.")
    public List<GitCommit> getCommitLog(
            @ToolParam(
                            description =
                                    "Максимальное количество коммитов (по умолчанию 20, макс. 100)",
                            required = false)
                    Integer maxCount,
            @ToolParam(
                            description =
                                    "Путь к файлу — показать только коммиты, затрагивающие этот файл",
                            required = false)
                    String filePath) {
        int limit = (maxCount != null && maxCount > 0) ? maxCount : 20;
        log.info("getCommitLog called: maxCount={}, filePath='{}'", limit, filePath);
        List<GitCommit> commitLog = gitService.getCommitLog(limit, filePath);
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
                    "Получить изменения для указанного коммита или нескольких коммитов "
                            + "(список файлов, количество изменений, и опционально unified diff).")
    public List<GitCommit> getCommitDiff(
            @ToolParam(description = "Хеш коммита или несколько хешей через запятую")
                    String commitHashes,
            @ToolParam(
                            description =
                                    "Включить текст diff (unified patch) для каждого файла? "
                                            + "По умолчанию false — только список файлов и статистика.",
                            required = false)
                    Boolean includePatch) {
        boolean patch = includePatch != null && includePatch;
        log.info("getCommitDiff called: hashes='{}', includePatch={}", commitHashes, patch);
        List<GitCommit> commitDiff = gitService.getCommitDiff(commitHashes, patch);
        log.info("getCommitDiff called: commitDiff={}", commitDiff);
        return commitDiff;
    }

    // ── File search ─────────────────────────────────────────────────────────

    /**
     * Searches tracked file names by substring (case-insensitive). Ignored files are excluded.
     *
     * @param pattern search pattern (substring of file path)
     * @param maxResults max results to return (default 20, max 50)
     * @return matching file nodes
     */
    @Tool(
            description =
                    "Поиск файлов в репозитории по имени/пути (подстрока, регистронезависимо). "
                            + "Файлы из .gitignore исключены.")
    public List<GitFileNode> searchFiles(
            @ToolParam(description = "Подстрока для поиска в пути файла") String pattern,
            @ToolParam(
                            description = "Максимальное количество результатов (по умолчанию 20)",
                            required = false)
                    Integer maxResults) {
        int limit = (maxResults != null && maxResults > 0) ? maxResults : 20;
        log.info("searchFiles called: pattern='{}', maxResults={}", pattern, limit);
        List<GitFileNode> gitFileNodes = gitService.searchFiles(pattern, limit);
        log.info("searchFiles called: gitFileNodes={}", gitFileNodes);
        return gitFileNodes;
    }

    // ── File content ────────────────────────────────────────────────────────

    /**
     * Returns the full content of a tracked file. Binary files are detected and flagged without
     * content. Files larger than 512 KB are truncated.
     *
     * @param filePath path relative to repo root
     * @return file content or binary marker
     */
    @Tool(
            description =
                    "Получить содержимое конкретного файла из репозитория по его пути. "
                            + "Бинарные файлы помечаются без содержимого. Макс. размер 512 КБ.")
    public GitFileContent getFileContent(
            @ToolParam(description = "Путь к файлу относительно корня репозитория")
                    String filePath) {
        log.info("getFileContent called: filePath='{}'", filePath);
        GitFileContent fileContent = gitService.getFileContent(filePath);
        log.info("getFileContent called: fileContent='{}'", fileContent);
        return fileContent;
    }

    /**
     * Returns uncommitted changes in the working tree, excluding files matched by {@code
     * .gitignore}.
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    @Tool(description = "Returns uncommitted changes in the working tree")
    public List<GitDiffEntry> getUncommittedChanges(
            @ToolParam(description = "whether to include unified diff text for modified files")
                    boolean includePatch) {
        log.info("getUncommittedChanges called: includePatch='{}'", includePatch);
        List<GitDiffEntry> gitDiffEntries = gitService.getUncommittedChanges(includePatch);
        log.info("getUncommittedChanges called: gitDiffEntries='{}'", gitDiffEntries);
        return gitDiffEntries;
    }
}
