package io.github.trialiya.kb.model.git.dto;

import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Авторство строк файла ({@code git blame}) для колонки blame файлового браузера: файл разбит на
 * диапазоны подряд идущих строк, пришедших из одного коммита, — как их и показывает интерфейс,
 * подписью на первой строке диапазона.
 *
 * <p>Коммиты из {@code .git-blame-ignore-revs} репозитория (массовые переформатирования) уже
 * пропущены: их строки приписаны тому, кто менял их до этого.
 *
 * <p>Репозиторий ответ не называет: он один на всю выдачу и известен клиенту из запроса.
 *
 * @param path относительный путь от корня репозитория
 * @param commit полный хеш ревизии, в снимке которой смотрели файл; {@code null} — рабочее дерево
 * @param lineCount сколько строк в файле; сумма {@code lineCount} по диапазонам
 * @param hunks диапазоны в порядке строк
 */
public record GitFileBlame(String path, @Nullable String commit, int lineCount, List<Hunk> hunks) {

    /**
     * Строки {@code fromLine}…{@code fromLine + lineCount - 1}, последними изменённые одним коммитом.
     *
     * <p>Все поля о коммите либо заполнены, либо все {@code null}: второе — строки, которых ещё нет
     * ни в одном коммите (незакоммиченная правка в рабочем дереве).
     *
     * @param fromLine первая строка диапазона (1-based)
     * @param lineCount сколько строк в диапазоне
     * @param hash полный хеш коммита
     * @param shortHash короткий хеш для подписи
     * @param author имя автора
     * @param email почта автора
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
            @Nullable String hash,
            @Nullable String shortHash,
            @Nullable String author,
            @Nullable String email,
            @Nullable OffsetDateTime date,
            @Nullable String summary,
            @Nullable String path,
            @Nullable Integer sourceLine) {}
}
