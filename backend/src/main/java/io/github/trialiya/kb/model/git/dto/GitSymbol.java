package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.tools.ToolCallResponseItem;
import io.github.trialiya.kb.tools.ToolCallResultMetaProvider;
import java.util.Map;

/**
 * Один символ в структурном обзоре файла (класс, метод, функция, поле и т.д.).
 *
 * @param kind вид символа: "class", "interface", "enum", "record", "method", "function", "field",
 *     "import", "table", "view" и т.д.
 * @param name имя символа
 * @param signature сигнатура/заголовок (например, "public List&lt;String&gt; foo(int x)"), либо
 *     null
 * @param startLine первая строка символа (1-based)
 * @param endLine последняя строка символа (1-based, включительно)
 */
public record GitSymbol(String kind, String name, String signature, int startLine, int endLine)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {
    @Override
    public String getFormattedResponse() {
        return kind
                + " "
                + name
                + " L"
                + startLine
                + "-"
                + endLine
                + (signature == null ? "" : "  " + signature);
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "kind", kind,
                "name", name,
                "startLine", startLine,
                "endLine", endLine);
    }
}
