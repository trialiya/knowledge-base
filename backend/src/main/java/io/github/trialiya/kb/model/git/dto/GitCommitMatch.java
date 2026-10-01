package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Коммит, найденный поиском страницы «Поиск», и где именно в нём совпало — как строки совпадений у
 * файлов ({@code git grep}) и у документов: страница показывает их, не сравнивая заново.
 *
 * @param commit сам коммит, без {@code body}: совпавшие строки описания — в {@code lines}, а
 *     описание целиком видно на вкладке «Коммит», куда ведёт карточка
 * @param subjectMatch запрос встретился в заголовке
 * @param hashMatch коммит найден только по префиксу хеша — ни в заголовке, ни в описании запроса нет
 * @param lines строки описания с запросом; номер — строка описания, считая с 1
 */
public record GitCommitMatch(GitCommit commit, boolean subjectMatch, boolean hashMatch, List<Line> lines) {

    /** Строка описания с запросом. */
    public record Line(int line, String text) {}
}
