package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.tools.Compact;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * Ответ инструмента {@code getBlame} — тот же {@link GitFileBlame}, сложенный плотнее: коммит
 * описан один раз в словаре {@code commits}, а ханк ссылается на него хешем.
 *
 * <p>Ханков у файла с историей в разы больше, чем коммитов: десятки ханков из пяти-шести коммитов
 * — обычный диапазон, и {@link GitFileBlame} повторял бы в каждом автора, дату и тему коммита.
 * Колонка blame в «Файлах» получает {@link GitFileBlame} как есть: ей нужна строка на ханк, а не
 * экономия контекста.
 *
 * <p>По той же причине ханк опускает то, что совпадает с очевидным: {@code path} — если это путь
 * файла, {@code sourceLine} — если строка в том коммите та же, что сейчас. Режим «Обзор» модалки
 * вызова ({@code blameResult.js}) разворачивает ответ обратно в строки.
 *
 * @param path относительный путь от корня репозитория
 * @param commit полный хеш ревизии, в снимке которой смотрели файл; {@code null} — рабочее дерево
 * @param lineCount сколько строк во всём файле, а не в диапазоне
 * @param fromLine первая строка ответа; {@code null} — спрашивали весь файл
 * @param toLine последняя строка ответа (включительно); см. {@link GitFileBlame#toLine()}
 * @param commits коммиты ханков по полному хешу, в порядке первого появления
 * @param hunks диапазоны в порядке строк
 */
public record GitBlameAnswer(
        String path,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String commit,
        int lineCount,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer fromLine,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer toLine,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Commit> commits,
        List<Hunk> hunks)
        implements ToolCallResponseItem {

    /** Ханков в плашке вызова. */
    private static final int GIST_HUNKS = 5;

    /** Ответ blame в плотной форме. */
    public static GitBlameAnswer of(GitFileBlame blame) {
        Map<String, Commit> commits = new LinkedHashMap<>();
        List<Hunk> hunks = new ArrayList<>(blame.hunks().size());
        for (GitFileBlame.Hunk hunk : blame.hunks()) {
            if (hunk.hash() != null) {
                commits.computeIfAbsent(hunk.hash(), hash -> Commit.of(hunk));
            }
            hunks.add(Hunk.of(hunk, blame.path()));
        }
        return new GitBlameAnswer(
                blame.path(),
                blame.commit(),
                blame.lineCount(),
                blame.fromLine(),
                blame.toLine(),
                Collections.unmodifiableMap(commits),
                List.copyOf(hunks));
    }

    /**
     * Плашка вызова: заголовок и первые ханки строкой каждый, с автором из словаря. Напоминание о
     * том, что пришло, а не сам ответ.
     */
    @Override
    public String getFormattedResponse() {
        StringBuilder gist = new StringBuilder(Compact.tag("blame:" + path)
                .add("at", commit == null ? null : commit.substring(0, 7))
                .add("lines", lineCount)
                .add("range", fromLine == null ? null : fromLine + "-" + toLine)
                .add("hunks", hunks.size())
                .done());
        hunks.stream()
                .limit(GIST_HUNKS)
                .forEach(hunk ->
                        gist.append('\n').append(hunk.gist(hunk.hash() == null ? null : commits.get(hunk.hash()))));
        return gist.toString();
    }

    /**
     * Коммит, к которому восходят строки ханков. Поля описания — все или ни одного (см. {@link
     * GitFileBlame.Hunk}); у коммита на границе неглубокого клона нет автора и темы.
     *
     * @param author имя автора
     * @param date дата авторства
     * @param summary первая строка сообщения коммита
     * @param boundary коммит — граница неглубокого клона: его строки могут быть старше него, и кто их
     *     написал, клон не знает
     */
    public record Commit(
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String author,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            OffsetDateTime date,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String summary,

            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean boundary) {

        static Commit of(GitFileBlame.Hunk hunk) {
            return new Commit(hunk.author(), hunk.date(), hunk.summary(), hunk.boundary());
        }
    }

    /**
     * Строки {@code fromLine}…{@code fromLine + lineCount - 1}, последними изменённые одним коммитом.
     *
     * @param hash полный хеш коммита — ключ в {@code commits}; {@code null} — незакоммиченная правка
     * @param path путь файла в том коммите; {@code null} — тот же, что у файла ответа
     * @param sourceLine первая строка диапазона в файле того коммита; {@code null} — та же, что
     *     {@code fromLine}, или коммита нет
     */
    public record Hunk(
            int fromLine,
            int lineCount,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String hash,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String path,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer sourceLine) {

        static Hunk of(GitFileBlame.Hunk hunk, String file) {
            return new Hunk(
                    hunk.fromLine(),
                    hunk.lineCount(),
                    hunk.hash(),
                    Objects.equals(hunk.path(), file) ? null : hunk.path(),
                    Objects.equals(hunk.sourceLine(), hunk.fromLine()) ? null : hunk.sourceLine());
        }

        /**
         * {@code 12-18 a1b2c3d Alice 2024-03-01 Fix parser}; без коммита — {@code 19 uncommitted};
         * на границе клона — {@code 12-18 a1b2c3d boundary 2024-03-01} (автора и описания у такого
         * коммита нет).
         */
        String gist(@Nullable Commit commit) {
            int to = fromLine + lineCount - 1;
            String lines = to == fromLine ? String.valueOf(fromLine) : fromLine + "-" + to;
            if (hash == null) {
                return lines + " uncommitted";
            }
            StringJoiner gist = new StringJoiner(" ").add(lines).add(hash.substring(0, 7));
            // Поля коммита приходят вместе, но парсер оставляет голый ханк, если коммит не
            // описан, — плашка тогда без них, а не с «null».
            if (commit == null) {
                return gist.toString();
            }
            if (commit.boundary()) {
                gist.add("boundary");
            }
            if (commit.author() != null) {
                gist.add(commit.author());
            }
            if (commit.date() != null) {
                gist.add(commit.date().toLocalDate().toString());
            }
            if (commit.summary() != null) {
                gist.add(Compact.oneLine(commit.summary(), 60));
            }
            return gist.toString();
        }
    }
}
