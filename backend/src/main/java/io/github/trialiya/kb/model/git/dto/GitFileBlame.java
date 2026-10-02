package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.tools.Compact;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Авторство строк файла ({@code git blame}) — для колонки blame файлового браузера и для
 * инструмента модели {@code getBlame}: файл разбит на
 * диапазоны подряд идущих строк, пришедших из одного коммита, — как их и показывает интерфейс,
 * подписью на первой строке диапазона.
 *
 * <p>Коммиты из {@code .git-blame-ignore-revs} репозитория (массовые переформатирования) уже
 * пропущены: их строки приписаны тому, кто менял их до этого.
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
     * ни в одном коммите (незакоммиченная правка в рабочем дереве).
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
            Integer sourceLine) {

        /** {@code 12-18 a1b2c3d Alice 2024-03-01 Fix parser}; без коммита — {@code 19 uncommitted}. */
        String gist() {
            int to = fromLine + lineCount - 1;
            String lines = to == fromLine ? String.valueOf(fromLine) : fromLine + "-" + to;
            if (hash == null) {
                return lines + " uncommitted";
            }
            return lines + " " + hash.substring(0, 7) + " " + author + " "
                    + (date == null ? "" : date.toLocalDate() + " ") + Compact.oneLine(summary, 60);
        }
    }
}
