package io.github.trialiya.kb.model.git.dto;

import static io.github.trialiya.kb.tools.Compact.truncate;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;

/**
 * Содержимое файла из репозитория, обогащённое метаданными для ИИ.
 *
 * @param path относительный путь
 * @param content текстовое содержимое (null для бинарных файлов); при усечении/диапазоне — только
 *     запрошенная или укороченная часть
 * @param binary true если файл бинарный
 * @param sizeBytes размер файла в байтах
 * @param language язык программирования по расширению ("java", "javascript", "typescript",
 *     "python", "sql", ...), либо null если не определён
 * @param lineCount общее количество строк в файле (а не в возвращённом фрагменте)
 * @param truncated true если content содержит не весь файл (диапазон или усечение по размеру)
 * @param fromLine первая возвращённая строка (1-based), либо null если возвращён весь файл
 * @param toLine последняя возвращённая строка (1-based, включительно), либо null если весь файл
 */
public record GitFileContent(
        String path,
        String content,
        boolean binary,
        long sizeBytes,
        String language,
        int lineCount,
        boolean truncated,
        Integer fromLine,
        Integer toLine)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        String head =
                Compact.tag("file:" + path)
                        .add("lang", language)
                        .add("lines", lineCount)
                        .add("range", fromLine == null ? null : fromLine + "-" + toLine)
                        .add("truncated", truncated ? "1" : null)
                        .done();
        return binary
                ? head + " (binary, " + sizeBytes + "B)"
                : head + "\n" + truncate(content, 40);
    }
}
