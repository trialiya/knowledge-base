package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.tools.Compact;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Структурный обзор файла: список символов верхнего уровня без полного содержимого. Позволяет ИИ
 * понять устройство файла и затем точечно прочитать нужный диапазон строк через getFileContent.
 *
 * <p>Репозиторий обзор не называет: его называет обёртка ответа ({@code ToolResult}).
 *
 * @param path относительный путь
 * @param tracked отслеживается ли файл git'ом. {@code false} — файл виден только через {@code
 *     allow-globs} проекта: обзор у него такой же, но истории нет, и правка в нём останется
 *     неотслеживаемой. Тот же признак, что у {@code GitFileContent}: обзор и чтение — два ответа об
 *     одном файле, и различаться они не должны
 * @param language определённый язык, либо null
 * @param lineCount общее количество строк
 * @param parser имя использованного парсера: "tree-sitter" или "regex" (фолбэк)
 * @param symbols список символов в порядке появления в файле
 */
public record GitFileOutline(
        String path,
        boolean tracked,
        @Nullable String language,
        int lineCount,
        String parser,
        List<GitSymbol> symbols)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        StringBuilder sb =
                new StringBuilder(
                        Compact.tag("file:" + path)
                                .add("lang", language)
                                .add("untracked", tracked ? null : "1")
                                .add("lines", lineCount)
                                .done());
        symbols.forEach(s -> sb.append("\n  ").append(s.getFormattedResponse()));
        return sb.toString();
    }
}
