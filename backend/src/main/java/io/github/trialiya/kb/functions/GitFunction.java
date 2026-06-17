package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that give the chat model read-only access to the Git repository configured via
 * {@code kb.project-path}.
 *
 * <p>Seven capabilities are exposed:
 *
 * <ul>
 *   <li>{@link #getFileTree} — browse tracked files/directories one level at a time.
 *   <li>{@link #getCommitLog} — recent commit history, optionally filtered by file.
 *   <li>{@link #getCommitDiff} — changed files and patches for one or more commits.
 *   <li>{@link #searchFiles} — find tracked files by name/path substring.
 *   <li>{@link #getFileOutline} — structural map (classes/methods) of a source file.
 *   <li>{@link #getFileContent} — read the full UTF-8 text, or a line range, of a tracked file.
 *   <li>{@link #getUncommittedChanges} — staged and unstaged working-tree changes.
 * </ul>
 *
 * <p><b>Security constraints:</b> all operations are strictly read-only. Only files tracked by Git
 * are accessible — untracked files (including those matching {@code .gitignore}) are refused even
 * if they exist on disk. Binary files and files larger than 512 KB are detected and returned
 * without content so they never bloat the model context.
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
                    """
                    Один уровень файлового дерева репозитория. Узел: path, name, \
                    type ("file"|"directory"), size (байты, только для файлов). \
                    Каталоги не раскрываются — вызови повторно с нужным path для следующего уровня.\
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitFileNode> getFileTree(
            @ToolParam(
                            description =
                                    "Путь к подкаталогу относительно корня репозитория (например, "
                                            + "\"src/main/java\"). Пустая строка или null — корень репо.",
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
                    """
                    История коммитов в обратном хронологическом порядке. Коммит: hash (полный SHA), \
                    shortHash (8 символов), author, email, date (ISO-8601), message. \
                    Поле files всегда null (изменения — через getCommitDiff по shortHash).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitCommit> getCommitLog(
            @ToolParam(
                            description =
                                    "Максимальное количество коммитов для возврата. "
                                            + "Допустимый диапазон: 1–100, по умолчанию 20.",
                            required = false)
                    Integer maxCount,
            @ToolParam(
                            description =
                                    "Путь к файлу (относительно корня репо) — вернуть только коммиты, "
                                            + "затрагивающие этот файл. Null — вся история.",
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
                    """
                    Изменённые файлы для одного/нескольких коммитов. Элемент: status (A/M/D/R), \
                    path, oldPath (при переименовании), additions, deletions. \
                    При includePatch=true добавляется unified diff. Хеши бери из getCommitLog.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitCommit> getCommitDiff(
            @ToolParam(
                            description =
                                    "Хеш коммита (полный или короткий) или несколько хешей через запятую, "
                                            + "например: \"abc1234\" или \"abc1234,def5678\".")
                    String commitHashes,
            @ToolParam(
                            description =
                                    "Включить unified diff (patch) для каждого файла. "
                                            + "false (по умолчанию) — только список файлов и статистика строк; "
                                            + "true — добавляет текст изменений, увеличивает объём ответа.",
                            required = false)
                    Boolean includePatch,
            @ToolParam(
                            description =
                                    "Путь к файлу (относительно корня репо) — вернуть diff только по этому файлу. "
                                            + "Null — все изменённые файлы коммита.",
                            required = false)
                    String filePath) {
        boolean patch = includePatch != null && includePatch;
        log.info(
                "getCommitDiff called: hashes='{}', includePatch={}, filePath='{}'",
                commitHashes,
                patch,
                filePath);
        List<GitCommit> commitDiff = gitService.getCommitDiff(commitHashes, patch, filePath);
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
                    """
                    Ищет tracked файлы по имени (fuzzy: символы запроса как подпоследовательность, \
                    регистронезависимо — "mgi" находит MessageInput). Результаты ранжированы: \
                    лучшее совпадение первым. Узлы: path, name, type="file", size. Ищет ТОЛЬКО по \
                    имени/пути файла (поиск по содержимому — grepContent).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitFileNode> searchFiles(
            @ToolParam(
                            description =
                                    "Часть имени файла, например: \"Controller\", \".yml\", "
                                            + "\"mgi\". Регистронезависимый fuzzy-поиск.")
                    String pattern,
            @ToolParam(
                            description =
                                    "Максимальное количество результатов. Диапазон: 1–50, по умолчанию 20.",
                            required = false)
                    Integer maxResults) {
        int limit = (maxResults != null && maxResults > 0) ? maxResults : 20;
        log.info("searchFiles called: pattern='{}', maxResults={}", pattern, limit);
        List<GitFileNode> gitFileNodes = gitService.searchFiles(pattern, limit);
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
                    """
                    Структурный обзор файла кода БЕЗ полного текста: символы (класс, интерфейс, \
                    метод, функция, поле и т.д.) с именем и диапазоном строк (startLine, endLine). \
                    Поле parser — движок ("tree-sitter"|"regex"). Языки: Java, JS/TS, Python; \
                    для остальных и бинарных — ошибка, читай через getFileContent.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitFileOutline getFileOutline(
            @ToolParam(
                            description =
                                    "Точный путь к файлу относительно корня репозитория, например: "
                                            + "\"src/main/java/com/example/App.java\".")
                    String filePath) {
        log.info("getFileOutline called: filePath='{}'", filePath);
        GitFileOutline outline = gitService.getFileOutline(filePath);
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
                    """
                    Содержимое tracked файла. Ответ: path, content, binary, sizeBytes, \
                    language (по расширению), lineCount, truncated, fromLine/toLine \
                    (фактический диапазон, null если весь файл). При binary=true content=null. \
                    Для экономии токенов укажи fromLine/toLine (например, диапазон из getFileOutline).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitFileContent getFileContent(
            @ToolParam(
                            description =
                                    "Точный путь к файлу относительно корня репозитория, например: "
                                            + "\"src/main/java/com/example/App.java\". "
                                            + "Используй getFileTree или searchFiles для уточнения пути.")
                    String filePath,
            @ToolParam(
                            description =
                                    "Первая строка для чтения (1-based, включительно). "
                                            + "null — с начала файла. Используй вместе с toLine для "
                                            + "чтения только нужного фрагмента большого файла.",
                            required = false)
                    Integer fromLine,
            @ToolParam(
                            description =
                                    "Последняя строка для чтения (1-based, включительно). "
                                            + "null — до конца файла. Выход за пределы файла "
                                            + "автоматически усекается.",
                            required = false)
                    Integer toLine) {
        log.info(
                "getFileContent called: filePath='{}', fromLine={}, toLine={}",
                filePath,
                fromLine,
                toLine);
        GitFileContent fileContent = gitService.getFileContent(filePath, fromLine, toLine);
        log.info("getFileContent called: fileContent='{}'", fileContent);
        return fileContent;
    }

    /**
     * Returns uncommitted changes in the working tree, excluding files matched by {@code
     * .gitignore}.
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    @Tool(
            name = "getUncommittedChanges",
            description =
                    """
                    Незакоммиченные изменения рабочего дерева (staged и unstaged). Запись: \
                    status (A/M/D/R), path, oldPath (при переименовании), additions, deletions. \
                    Untracked файлы (ещё не в git add) — статус A, patch=null. \
                    При includePatch=true добавляется unified diff для изменённых файлов.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitDiffEntry> getUncommittedChanges(
            @ToolParam(
                            description =
                                    "Включить unified diff для изменённых файлов. "
                                            + "false — только список файлов и статистика строк (быстро); "
                                            + "true — добавляет текст изменений (увеличивает объём ответа).")
                    boolean includePatch) {
        log.info("getUncommittedChanges called: includePatch='{}'", includePatch);
        List<GitDiffEntry> gitDiffEntries = gitService.getUncommittedChanges(includePatch);
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
     * @param contextLines lines of context before/after each match (0–10, default 0)
     * @param maxResults maximum number of matches to return (1–200, default 50)
     * @return list of matches with file path, line number, and line text
     */
    @Tool(
            description =
                    """
                    Ищет текст ВНУТРИ содержимого tracked файлов (git grep), всегда \
                    регистронезависимо. Возвращает: path, line (1-based), text. \
                    Для поиска по имени/пути файла используй searchFiles. \
                    regex=false (по умолчанию) — буквальная подстрока; regex=true — POSIX ERE \
                    (нужно при | . * ^ $). contextLines (0–10) — строки вокруг совпадения.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public List<GitGrepMatch> grepContent(
            @ToolParam(
                            description =
                                    "Что искать. Если содержит `|`, `.*` или другие regex-символы — "
                                            + "установи regex=true.")
                    String pattern,
            @ToolParam(
                            description =
                                    "Glob для ограничения по путям: \"*.java\", \"src/main/**\", "
                                            + "\"**/*Service*.java\". null — все tracked файлы.",
                            required = false)
                    String pathGlob,
            @ToolParam(
                            description =
                                    "true (по умолчанию) — POSIX ERE (при | .* ^ $); false — "
                                            + "буквальная подстрока.",
                            required = false)
                    Boolean regex,
            @ToolParam(
                            description =
                                    "Строк контекста до/после совпадения (как grep -C). "
                                            + "1 по умолчанию, рекомендуется 2–5. Диапазон: 0–10.",
                            required = false)
                    Integer contextLines,
            @ToolParam(
                            description = "Максимум совпадений. По умолчанию 50, диапазон 1–200.",
                            required = false)
                    Integer maxResults) {
        boolean useRegex = regex == null || regex;
        int ctx = contextLines != null ? contextLines : 1;
        int limit = maxResults != null ? maxResults : 50;
        log.info(
                "grepContent called: pattern='{}', pathGlob='{}', regex={}, contextLines={}, maxResults={}",
                pattern,
                pathGlob,
                useRegex,
                ctx,
                limit);
        List<GitGrepMatch> matches =
                gitService.grepContent(pattern, pathGlob, useRegex, ctx, limit);
        log.info("grepContent called: {} matches found", matches.size());
        return matches;
    }
}
