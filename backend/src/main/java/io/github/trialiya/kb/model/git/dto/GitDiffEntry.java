package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import org.jspecify.annotations.Nullable;

/**
 * Одна запись из diff коммита.
 *
 * @param project id репозитория, к которому относится изменение — обязателен, когда запись
 *     верхнеуровневая ({@code getUncommittedChanges}): инструмент умеет читать не только активный
 *     проект чата (см. {@code GitFunction}), и без эха ответ не говорит, чей это рабочий каталог.
 *     Внутри {@link GitCommit} — {@code null} и в JSON не попадает: репозиторий уже назвал коммит,
 *     повторять его в каждой из десятков записей — платить токенами за то, что читается строкой
 *     выше
 * @param status статус: A (added), M (modified), D (deleted), R (renamed), C (copied), а для
 *     рабочего дерева ещё и U (untracked) — файл, который git не отслеживает и который проект
 *     показывает только через {@code allow-globs}; в индексе его нет, в коммит он сам не попадёт
 * @param path путь к файлу (для rename/copy — новый путь)
 * @param oldPath старый путь (только при rename/copy, иначе null)
 * @param additions количество добавленных строк
 * @param deletions количество удалённых строк
 * @param patchHeader служебная шапка патча ({@code diff --git}, {@code index}, {@code --- a/…},
 *     {@code +++ b/…}) — отдельно от него самого: она описывает файл, а не его строки. null, если
 *     патч не запрашивался или шапки у него нет (сообщение о бинарном файле)
 * @param patch текстовый diff (unified), начиная с первого {@code @@}; null если не запрашивался
 */
public record GitDiffEntry(
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String project,
        String status,
        String path,
        @Nullable String oldPath,
        int additions,
        int deletions,
        @Nullable String patchHeader,
        @Nullable String patch)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        String loc = oldPath == null ? path : oldPath + "→" + path;
        return status + " " + loc + " (+" + additions + " -" + deletions + ")";
    }
}
