package io.github.trialiya.kb.functions;

import java.util.stream.Collectors;

/**
 * Предел длины строки в блоке совпадения {@code grepContent} / {@code grepDocuments}.
 *
 * <p>Число совпадений у обоих инструментов ограничено, длина строки — нет: совпадение в
 * минифицированном файле или в абзаце без переносов — это одна строка в сотни килобайт, и модель
 * получила бы её целиком. Длинная строка обрезается с пометкой, сколько символов не показано;
 * прочитать её всю можно через {@code getFileContent} / {@code getDocumentSection}.
 *
 * <p>Только для инструментов: панель поиска в UI получает строку целиком и подсвечивает в ней
 * совпадение, где бы оно ни стояло.
 */
final class GrepLines {

    /** Символов строки блока, включая её префикс {@code :N:} / {@code -N-}. */
    static final int MAX_LINE_CHARS = 500;

    private GrepLines() {}

    static String cap(String text) {
        if (text.length() <= MAX_LINE_CHARS) {
            return text;
        }
        return text.lines().map(GrepLines::capLine).collect(Collectors.joining("\n"));
    }

    private static String capLine(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS) + "… (+" + (line.length() - MAX_LINE_CHARS) + " chars)";
    }
}
