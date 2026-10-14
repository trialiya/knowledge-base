package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.tools.Compact;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * Авторство строк файла ({@code git blame}) — для колонки blame файлового браузера: файл разбит на
 * диапазоны подряд идущих строк, пришедших из одного коммита, — как их и показывает интерфейс,
 * подписью на первой строке диапазона. Инструмент модели {@code getBlame} отвечает им же, но
 * сложенным плотнее ({@link GitBlameAnswer}); плашку вызова считает этот класс.
 *
 * <p>Коммиты из {@code .git-blame-ignore-revs} репозитория (массовые переформатирования) уже
 * пропущены: их строки приписаны тому, кто менял их до этого.
 *
 * <p>В неглубоком (shallow) клоне git приписывает самому старому коммиту клона все строки, которые
 * старше него, — такой ханк помечен {@code boundary} и идёт без автора и описания коммита: строки не
 * моложе этого коммита, но не обязательно им написаны, и настоящего автора клон не знает.
 *
 * <p>Репозиторий ответ не называет: он один на всю выдачу и известен клиенту из запроса, а модели —
 * из обёртки ответа ({@code ToolResult}). Пустые поля в JSON не печатаются: отсутствующее поле
 * значит {@code null}.
 *
 * @param path относительный путь от корня репозитория
 * @param commit полный хеш ревизии, в снимке которой смотрели файл; {@code null} — рабочее дерево
 * @param lineCount сколько строк в файле — во всём, а не в запрошенном диапазоне; без диапазона это
 *     сумма {@code lineCount} по ханкам
 * @param hunks диапазоны в порядке строк; при запрошенном диапазоне — только его строки, ханк на его
 *     границе обрезан по ней
 * @param fromLine первая строка, о которой ответ (1-based), уже уложенная в файл; {@code null} —
 *     спрашивали весь файл
 * @param toLine последняя строка ответа (включительно); {@code toLine < fromLine} — от файла в
 *     диапазон не попало ничего (начало за его концом), и {@code hunks} пуст
 */
public record GitFileBlame(
        String path,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String commit,
        int lineCount,
        List<Hunk> hunks,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer fromLine,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer toLine)
        implements ToolCallResponseItem {

    /** Ханков в плашке вызова: она — напоминание о том, что пришло, а не сам ответ. */
    private static final int GIST_HUNKS = 5;

    @Override
    public String getFormattedResponse() {
        StringBuilder gist = new StringBuilder(Compact.tag("blame:" + path)
                .add("at", commit == null ? null : commit.substring(0, 7))
                .add("lines", lineCount)
                .add("range", fromLine == null ? null : fromLine + "-" + toLine)
                .add("hunks", hunks.size())
                .done());
        hunks.stream().limit(GIST_HUNKS).forEach(h -> gist.append('\n').append(h.gist()));
        return gist.toString();
    }

    /**
     * Строки {@code fromLine}…{@code fromLine + lineCount - 1}, последними изменённые одним коммитом.
     *
     * <p>Все поля о коммите либо заполнены, либо все {@code null}: второе — строки, которых ещё нет
     * ни в одном коммите (незакоммиченная правка в рабочем дереве). Исключение — ханк на границе
     * клона ({@code boundary}): у него нет автора и описания.
     *
     * @param fromLine первая строка диапазона (1-based)
     * @param lineCount сколько строк в диапазоне
     * @param hash полный хеш коммита; короткий для подписи клиент берёт из него сам
     * @param author имя автора
     * @param date дата авторства
     * @param summary первая строка сообщения коммита
     * @param path путь файла в этом коммите — после переименования он отличается от текущего, и
     *     ссылка на файл в снимке того коммита ведёт по нему
     * @param sourceLine номер первой строки диапазона в файле того коммита (1-based) — строки
     *     диапазона и там идут подряд, так что ссылка на снимок выделяет их {@code sourceLine}…
     *     {@code sourceLine + lineCount - 1}
     * @param boundary коммит — граница неглубокого клона: история за ним обрезана, и строки ханка
     *     могут быть старше него
     */
    public record Hunk(
            int fromLine,
            int lineCount,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String hash,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String author,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            OffsetDateTime date,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String summary,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String path,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer sourceLine,

            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean boundary) {

        /** Ханк не на границе клона — таких подавляющее большинство. */
        public Hunk(
                int fromLine,
                int lineCount,
                @Nullable String hash,
                @Nullable String author,
                @Nullable OffsetDateTime date,
                @Nullable String summary,
                @Nullable String path,
                @Nullable Integer sourceLine) {
            this(fromLine, lineCount, hash, author, date, summary, path, sourceLine, false);
        }

        /**
         * Тот же ханк, помеченный границей неглубокого клона, — без автора и описания коммита: они
         * говорили бы, кто и зачем написал строки, а этого клон не знает. Хеш, дата, путь и строка
         * остаются: по ним ссылка ведёт в тот коммит. Дата — авторства этого коммита, а не верхняя
         * граница возраста строк: после rebase у предков бывает дата и позже.
         */
        public Hunk atBoundary() {
            return new Hunk(fromLine, lineCount, hash, null, date, null, path, sourceLine, true);
        }

        /**
         * {@code 12-18 a1b2c3d Alice 2024-03-01 Fix parser}; без коммита — {@code 19 uncommitted};
         * на границе клона — {@code 12-18 a1b2c3d boundary 2024-03-01} (автора и описания у такого
         * ханка нет).
         */
        String gist() {
            int to = fromLine + lineCount - 1;
            String lines = to == fromLine ? String.valueOf(fromLine) : fromLine + "-" + to;
            if (hash == null) {
                return lines + " uncommitted";
            }
            // Поля коммита приходят вместе, но парсер оставляет голый ханк, если коммит не
            // описан, — плашка тогда без них, а не с «null».
            StringJoiner gist = new StringJoiner(" ").add(lines).add(hash.substring(0, 7));
            if (boundary) {
                gist.add("boundary");
            }
            if (author != null) {
                gist.add(author);
            }
            if (date != null) {
                gist.add(date.toLocalDate().toString());
            }
            if (summary != null) {
                gist.add(Compact.oneLine(summary, 60));
            }
            return gist.toString();
        }
    }
}
