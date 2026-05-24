package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
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
                    Возвращает один уровень файлового дерева git-репозитория: только tracked файлы \
                    (.gitignore и untracked исключены). Каждый узел содержит: path (полный путь от \
                    корня репо), name (имя файла/каталога), type ("file" | "directory"), size (байты, \
                    только для файлов). Каталоги не раскрываются — вызови повторно с нужным path для \
                    следующего уровня. Используй для навигации по структуре проекта перед чтением файлов.\
                    """)
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
                    Возвращает историю коммитов репозитория в обратном хронологическом порядке. \
                    Каждый коммит содержит: hash (полный SHA), shortHash (первые 8 символов), \
                    author, email, date (ISO-8601), message. Поле files всегда null — для просмотра \
                    изменений используй getCommitDiff. Используй shortHash как аргумент для \
                    getCommitDiff.\
                    """)
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
                    Возвращает изменённые файлы для одного или нескольких коммитов. Каждый элемент \
                    содержит: status (A/M/D/R), path (новый путь), oldPath (при переименовании), \
                    additions и deletions (количество строк). При includePatch=true добавляется \
                    unified diff (усечённый до 500 строк на файл). Для получения хешей сначала \
                    вызови getCommitLog.\
                    """)
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
                    """
                    Ищет tracked файлы по подстроке пути (регистронезависимо). Файлы из .gitignore \
                    и untracked исключены. Возвращает узлы с path, name, type="file", size (байты). \
                    Используй перед getFileContent, если не знаешь точный путь файла.\
                    """)
    public List<GitFileNode> searchFiles(
            @ToolParam(
                            description =
                                    "Подстрока для поиска в пути файла, например: \"Controller\", "
                                            + "\".yml\", \"src/test\". Регистронезависимо.")
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
                    Возвращает структурный обзор файла кода БЕЗ полного текста: список символов \
                    (класс, интерфейс, метод, функция, поле, таблица и т.д.) с именем и диапазоном \
                    строк (startLine, endLine). Поддерживаемые языки: Java, JavaScript/TypeScript, \
                    Python. Поле parser показывает движок ("tree-sitter" или "regex"). \
                    Рекомендуемый порядок для больших файлов: сначала getFileOutline → затем \
                    getFileContent с нужным диапазоном строк. Для бинарных файлов и неподдерживаемых \
                    языков возвращается ошибка — используй getFileContent для таких файлов.\
                    """)
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
                    Возвращает содержимое tracked файла. Ответ содержит: path, content (текст), \
                    binary, sizeBytes, language (определяется по расширению), lineCount (всего строк \
                    в файле), truncated (вернулась ли только часть), fromLine/toLine (фактически \
                    возвращённый диапазон, null если весь файл). \
                    Если binary=true — content=null. Для больших файлов (>512 КБ) без указания \
                    диапазона возвращается фрагмент: начало + конец, truncated=true. \
                    Чтобы прочитать только часть большого файла, укажи fromLine/toLine (например, \
                    диапазон метода из getFileOutline) — это экономит токены. \
                    Untracked и .gitignore файлы недоступны.\
                    """)
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
                    Возвращает незакоммиченные изменения рабочего дерева: staged и unstaged. \
                    Каждая запись содержит: status (A/M/D/R), path, oldPath (при переименовании), \
                    additions и deletions. Новые untracked файлы (ещё не добавленные через git add) \
                    включаются со статусом A, patch=null. .gitignore и бинарные артефакты \
                    (.class, .jar и др.) исключены. При includePatch=true добавляется unified diff \
                    для изменённых файлов (усечённый до 500 строк).\
                    """)
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
}
