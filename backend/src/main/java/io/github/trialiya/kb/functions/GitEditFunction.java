package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that let the chat model <b>modify</b> the working tree of the repository at
 * {@code kb.project-path}: {@link #createFile} and {@link #editFile}. Nothing is ever committed —
 * changes stay uncommitted for the user to review ({@code getUncommittedChanges}) and commit.
 *
 * <p>Registered as a bean only when {@code kb.git.edit-enabled=true} <em>and</em> the working tree
 * is actually writable (see {@code ChatConfig#gitEditFunction}); in read-only mode (e.g. a ro
 * volume mount) these tools are simply absent from the model's tool list.
 *
 * <p><b>Read-before-edit guard.</b> {@code editFile} is rejected unless the target file was "seen"
 * earlier in the same chat-response session (tracked by the request-scoped {@link
 * ToolInvocationCollector}). Deliberately permissive about <em>how</em> it was seen — a partial
 * read (line range / outline) or a search hit is enough, because the exact-match {@code oldString}
 * contract already forces the model to quote real current content:
 *
 * <ul>
 *   <li>a read tool was called with this {@code filePath} argument, or
 *   <li>any successful tool result (grep/search/diff/...) mentions this path.
 * </ul>
 */
@Slf4j
@AllArgsConstructor
public class GitEditFunction {

    /** Tools whose {@code filePath} argument means the model deliberately looked at this file. */
    private static final Set<String> PATH_ARG_READ_TOOLS =
            Set.of("getFileContent", "getFileOutline", "editFile");

    private final GitService gitService;

    @Tool(
            description =
                    """
                    Создать НОВЫЙ файл в рабочем дереве репозитория и добавить его в индекс \
                    (git add). Ошибка, если файл уже существует (для правки — editFile) или путь \
                    игнорируется .gitignore. Изменение НЕ коммитится — пользователь сам просмотрит \
                    и закоммитит. Ответ: operation, path, additions, lineCount.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitEditResult createFile(
            @ToolParam(
                            description =
                                    "Путь нового файла относительно корня репозитория, например: "
                                            + "\"src/main/java/com/example/New.java\".")
                    String filePath,
            @ToolParam(description = "Полное содержимое нового файла (UTF-8).") String content) {
        log.info("createFile called: filePath='{}', {} chars", filePath, content.length());
        return gitService.createFile(filePath, content);
    }

    @Tool(
            description =
                    """
                    Точечная правка существующего tracked файла: заменяет oldString на newString. \
                    oldString должен встречаться в файле РОВНО один раз (при replaceAll=false) и \
                    совпадать посимвольно, включая пробелы и переводы строк — сначала посмотри \
                    актуальное содержимое (getFileContent, getFileOutline или результат поиска). \
                    Изменение НЕ коммитится. Ответ: operation, path, additions, deletions, \
                    lineCount, diff (unified diff правки).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitEditResult editFile(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Точный путь к файлу относительно корня репозитория, например: "
                                            + "\"src/main/java/com/example/App.java\".")
                    String filePath,
            @ToolParam(
                            description =
                                    "Точный существующий фрагмент файла для замены (посимвольно, "
                                            + "с отступами). Должен быть уникален в файле — при "
                                            + "неоднозначности добавь соседние строки.")
                    String oldString,
            @ToolParam(
                            description =
                                    "Новый текст вместо oldString. Пустая строка — удалить фрагмент.")
                    String newString,
            @ToolParam(
                            description =
                                    "true — заменить ВСЕ вхождения oldString; false (по умолчанию) — "
                                            + "ровно одно, иначе ошибка.",
                            required = false)
                    @Nullable Boolean replaceAll) {
        log.info(
                "editFile called: filePath='{}', old {} chars, new {} chars, replaceAll={}",
                filePath,
                oldString.length(),
                newString.length(),
                replaceAll);
        requireFileSeenInThisResponse(context, filePath);
        return gitService.editFile(filePath, oldString, newString, Boolean.TRUE.equals(replaceAll));
    }

    /**
     * Rejects an edit when nothing in this chat-response session shows the model has actually seen
     * the target file. Counts as "seen": a successful read-tool call with the same {@code filePath}
     * argument, or any successful tool call whose raw result text contains the path (covers grep,
     * search, diffs, tree listings — including partial reads of a single method). Skipped when no
     * {@link ToolInvocationCollector} is present (background jobs, tests).
     */
    private static void requireFileSeenInThisResponse(ToolContext context, String filePath) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null) {
            return;
        }
        final String path = filePath.strip().replace('\\', '/');
        final boolean seen =
                collector.snapshot().stream()
                        .filter(
                                inv ->
                                        ToolInvocationCollector.ToolInvocationStatus.OK
                                                == inv.status())
                        .anyMatch(
                                inv ->
                                        (PATH_ARG_READ_TOOLS.contains(inv.name())
                                                        && path.equals(
                                                                String.valueOf(
                                                                        inv.arguments()
                                                                                .get("filePath"))))
                                                || (inv.resultText() != null
                                                        && inv.resultText().contains(path)));
        if (!seen) {
            throw new IllegalStateException(
                    "Файл "
                            + path
                            + " НЕ изменён: его содержимое не было прочитано в этом ответе. "
                            + "Сначала посмотри файл — getFileContent(filePath=\""
                            + path
                            + "\") (можно диапазон строк), getFileOutline или найди нужный "
                            + "фрагмент через grepContent — затем повтори editFile.");
        }
    }
}
