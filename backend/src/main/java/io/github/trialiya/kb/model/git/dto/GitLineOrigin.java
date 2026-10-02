package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Где в истории появилась подстрока в строке файла: обход назад по версиям этой строки, пока в
 * очередной версии подстрока ещё есть.
 *
 * <p>Ответ — про <b>эту строку</b>, а не про подстроку во всём репозитории: та же подстрока могла
 * встречаться раньше в другом месте, и {@code git log -S} нашёл бы то место. Здесь — коммит, с
 * которого подстрока живёт в той строке, что нашёл поиск.
 *
 * <p>Пустые поля в JSON не печатаются: отсутствующее поле значит {@code null}.
 *
 * @param path относительный путь файла, как его спросили
 * @param line номер строки в файле, как его спросили (1-based)
 * @param query подстрока; ищется без учёта регистра, как ищет поиск
 * @param commit полный хеш снимка, от которого шли; {@code null} — рабочее дерево
 * @param status чем кончилась цепочка
 * @param steps версии строки от новой к старой; в каждой подстрока есть. При {@link
 *     Status#FOUND} последняя — та, где подстрока появилась
 * @param before версия строки прямо перед появлением подстроки — уже без неё (у {@link
 *     Status#UNCOMMITTED} — версия в HEAD); {@code null}, если строку тот коммит добавил (или
 *     создал с ней файл)
 */
public record GitLineOrigin(
        String path,
        int line,
        String query,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String commit,
        Status status,
        List<Step> steps,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Step before) {

    public enum Status {
        /** Найдено: последний шаг — коммит, где подстрока появилась в этой строке. */
        FOUND,
        /**
         * Подстрока появилась в незакоммиченной правке строки: шагов нет, а {@code before} — версия
         * строки в HEAD, которую правка заменила ({@code null}, если правка строку добавила).
         * Строка, изменённая в рабочем дереве с подстрокой, которая была и в HEAD, сюда не
         * попадает: её обход идёт по истории как обычно.
         */
        UNCOMMITTED,
        /** В строке подстроки нет: файл поменялся после поиска, или строки нет вовсе. */
        NOT_IN_LINE,
        /**
         * Цепочка дошла до начала истории клона, а он неполный (shallow): раньше смотреть нечего,
         * и последний шаг — граница клона, а не обязательно настоящее появление.
         */
        BOUNDARY,
        /**
         * Предел исчерпан — шагов или времени — раньше, чем цепочка кончилась; последний шаг —
         * самый старый из пройденных.
         */
        LIMIT
    }

    /**
     * Одна версия строки — в том коммите, который её последним менял.
     *
     * @param hash полный хеш коммита
     * @param author имя автора
     * @param date дата авторства
     * @param summary первая строка сообщения коммита
     * @param path путь файла в том коммите — после переименования он другой
     * @param line номер строки в файле того коммита (1-based)
     * @param text текст строки в том коммите, не длиннее 500 символов
     */
    public record Step(
            String hash,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String author,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            OffsetDateTime date,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String summary,

            String path,
            int line,
            String text) {}
}
