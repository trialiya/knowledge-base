package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.service.file.outline.LanguageDetector;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Байты файла → ответ читающего инструмента: язык по имени, счётчик строк, запрошенный диапазон и
 * усечение слишком большого файла.
 *
 * <p>Отдельно от {@code GitService}, потому что байты приходят из двух разных мест — рабочего
 * дерева и дерева коммита, — а правила показа у них одни: диапазон, который спросили, считается от
 * начала файла в обоих случаях, и «слишком большой» одинаково велик и в истории, и на диске.
 */
final class FileViews {

    /** Первые строки большого файла в усечённом ответе. */
    private static final int HEAD_LINES = 200;

    /** И последние — конец файла обычно говорит не меньше начала. */
    private static final int TAIL_LINES = 50;

    private FileViews() {}

    /**
     * @param path нормализованный путь относительно корня репозитория
     * @param tracked знает ли о файле git; для чтения из коммита всегда {@code true}
     * @param commit хеш коммита, из которого прочитан файл, либо {@code null} для рабочего дерева
     * @param bytes содержимое как есть
     * @param size размер в байтах — у файла на диске он может отличаться от {@code bytes.length}
     *     (например, у символьной ссылки), поэтому передаётся отдельно
     * @param fromLine первая строка запрошенного диапазона (1-based), либо {@code null}
     * @param toLine последняя строка диапазона (включительно), либо {@code null}
     */
    static GitFileContent of(
            String path,
            boolean tracked,
            @Nullable String commit,
            byte[] bytes,
            long size,
            @Nullable Integer fromLine,
            @Nullable Integer toLine) {
        String language = LanguageDetector.detect(path);
        if (RepoFiles.isBinary(bytes)) {
            return new GitFileContent(
                    path, tracked, commit, null, true, size, language, 0, false, null, null);
        }

        String full = RepoFiles.decodeToLf(bytes);
        // Разбиение с устойчивыми номерами строк; -1 сохраняет пустые строки в хвосте.
        String[] lines = full.split("\n", -1);
        int total = lines.length;
        boolean rangeRequested = fromLine != null || toLine != null;

        if (!rangeRequested) {
            // Большой файл без явного диапазона — начало и конец: целиком он вытеснил бы из
            // контекста то, ради чего его открыли.
            boolean oversized = size > RepoFiles.MAX_FILE_SIZE;
            String content = oversized ? headTailExcerpt(lines) : full;
            return new GitFileContent(
                    path, tracked, commit, content, false, size, language, total, oversized, null,
                    null);
        }

        // Запрошенный диапазон укладываем в [1, total].
        int from = fromLine == null ? 1 : Math.max(1, fromLine);
        int to = toLine == null ? total : Math.min(total, toLine);
        if (from > total || from > to) {
            // Пустой срез: содержимого нет, но метаданные остаются правдой.
            return new GitFileContent(
                    path,
                    tracked,
                    commit,
                    "",
                    false,
                    size,
                    language,
                    total,
                    true,
                    from,
                    Math.max(from, to));
        }
        String slice = String.join("\n", Arrays.asList(lines).subList(from - 1, to));
        return new GitFileContent(
                path,
                tracked,
                commit,
                slice,
                false,
                size,
                language,
                total,
                from > 1 || to < total,
                from,
                to);
    }

    /** Первые {@code HEAD_LINES} и последние {@code TAIL_LINES} строк с отметкой о пропуске. */
    private static String headTailExcerpt(String[] lines) {
        if (lines.length <= HEAD_LINES + TAIL_LINES) {
            return String.join("\n", lines);
        }
        var sb = new StringBuilder();
        for (int i = 0; i < HEAD_LINES; i++) {
            sb.append(lines[i]).append('\n');
        }
        int omitted = lines.length - HEAD_LINES - TAIL_LINES;
        sb.append("... (").append(omitted).append(" lines omitted) ...\n");
        for (int i = lines.length - TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
