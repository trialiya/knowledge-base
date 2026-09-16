package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.Map;

/**
 * Одно совпадение при поиске по содержимому tracked файлов (аналог блока вывода {@code git grep
 * -C}).
 *
 * <p>Когда {@code contextLines=0}, каждый блок содержит ровно одну строку совпадения. Когда {@code
 * contextLines>0}, несколько соседних строк одного файла объединяются в один блок — так AI получает
 * контекст без лишних записей.
 *
 * <p>Формат {@code text} при наличии контекста:
 *
 * <pre>
 * -84-          BROWSE_PATHS_V2_ASPECT_NAME,
 * :85:          SUB_TYPES_ASPECT_NAME,
 * -86-          STRUCTURED_PROPERTIES_ASPECT_NAME,
 * </pre>
 *
 * Строки с совпадением обрамлены {@code :N:}, строки контекста — {@code -N-}.
 *
 * <p>Репозиторий совпадение не называет: он один на всю выдачу и назван обёрткой ответа ({@code
 * ToolResult}).
 *
 * @param path относительный путь к файлу от корня репозитория
 * @param matchLine номер строки совпадения (1-based); при нескольких совпадениях в одном блоке —
 *     номер первого
 * @param text текст блока: одна строка (без контекста) или многострочный фрагмент (с контекстом)
 * @param tracked отслеживается ли файл git'ом. {@code false} — совпадение из второго прогона, по
 *     untracked-файлам проекта ({@code allow-globs}, только при {@code includeUntracked}): истории
 *     у такого файла нет, в коммит он не попадёт, и найденная строка может быть выводом сборки, а
 *     не исходником. Без этого признака выдача двух прогонов неразличима
 */
public record GitGrepMatch(String path, int matchLine, String text, boolean tracked)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Совпадение в отслеживаемом файле — обычный случай, для него и есть этот конструктор. */
    public GitGrepMatch(String path, int matchLine, String text) {
        this(path, matchLine, text, true);
    }

    /** Та же запись, но про untracked-файл: чем она станет после фильтров второго прогона. */
    public GitGrepMatch untracked() {
        return new GitGrepMatch(path, matchLine, text, false);
    }

    @Override
    public String getFormattedResponse() {
        return path + ":" + matchLine + (tracked ? "" : " [untracked]");
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "path", path,
                "matchLine", matchLine,
                "tracked", tracked);
    }
}
