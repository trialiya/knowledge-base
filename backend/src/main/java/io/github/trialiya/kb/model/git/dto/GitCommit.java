package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Запись из истории коммитов.
 *
 * <p>Репозиторий коммит не называет: он один на всю выдачу и назван обёрткой ответа ({@code
 * ToolResult}).
 *
 * <p>{@code body} в истории заполняется только по запросу: тела идут на тысячи символов каждое, и
 * два десятка коммитов лога с телами — это десятки тысяч токенов контекста за ответ на «какие
 * вообще были коммиты». Незаполненное поле всё равно печатается как {@code null}, а не выкидывается
 * из JSON: список коммитов в «Обзоре» чата разбирается по совпадению набора ключей ({@code
 * recordList.js}), и коммит с телом рядом с коммитом без тела развалил бы этот вид. В плашке UI
 * ({@link #getFormattedResponse}) и в её мете тела нет ни при каких условиях: там строка на коммит.
 *
 * @param hash полный SHA коммита
 * @param shortHash сокращённый SHA (минимум 7 символов, длиннее при неоднозначности)
 * @param author имя автора
 * @param email email автора
 * @param date дата коммита (ISO-8601 с offset)
 * @param message первая строка сообщения (subject)
 * @param body остальная часть сообщения — всё после первой пустой строки; {@code null}, если тела
 *     нет или его не запрашивали
 * @param files список затронутых файлов (только если запрошены изменения)
 */
public record GitCommit(
        String hash,
        String shortHash,
        String author,
        String email,
        OffsetDateTime date,
        String message,
        @Nullable String body,
        @Nullable List<GitDiffEntry> files)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        String head = shortHash + " " + date.toLocalDate() + " " + author + ": " + message;
        if (files == null || files.isEmpty()) return head;
        int add = files.stream().mapToInt(GitDiffEntry::additions).sum();
        int del = files.stream().mapToInt(GitDiffEntry::deletions).sum();
        return head + " (" + files.size() + " files +" + add + " -" + del + ")";
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "shortHash", shortHash,
                "author", author,
                "email", email,
                "date", date,
                "message", message,
                "changesFilesCount", files != null ? files.size() : 0);
    }
}
