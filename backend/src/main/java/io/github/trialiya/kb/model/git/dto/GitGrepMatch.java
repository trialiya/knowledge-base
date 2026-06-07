package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.tools.ToolCallResponseItem;
import io.github.trialiya.kb.tools.ToolCallResultMetaProvider;
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
 * @param path относительный путь к файлу от корня репозитория
 * @param matchLine номер строки совпадения (1-based); при нескольких совпадениях в одном блоке —
 *     номер первого
 * @param text текст блока: одна строка (без контекста) или многострочный фрагмент (с контекстом)
 */
public record GitGrepMatch(String path, int matchLine, String text)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        return path + ":" + matchLine;
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "path", path,
                "matchLine", matchLine);
    }
}
