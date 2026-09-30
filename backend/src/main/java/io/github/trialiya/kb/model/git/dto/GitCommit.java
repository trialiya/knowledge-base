package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * вообще были коммиты». Пустые {@code body} и {@code files} в JSON не печатаются, так что у
 * коммитов одной выдачи набор ключей может разниться; «Обзор» чата это допускает ({@code
 * recordList.js}). В плашке UI ({@link #getFormattedResponse}) и в её мете тела нет ни при каких
 * условиях: там строка на коммит.
 *
 * @param hash полный SHA коммита
 * @param shortHash сокращённый SHA (минимум 7 символов, длиннее при неоднозначности)
 * @param author имя автора
 * @param email email автора
 * @param date дата коммита (ISO-8601 с offset)
 * @param message subject — первый абзац сообщения, переносы строк склеены пробелами
 * @param body остальная часть сообщения — всё после первой пустой строки; {@code null}, если тела
 *     нет или его не запрашивали
 * @param files список затронутых файлов (только если запрошены изменения)
 * @param parents полные SHA родителей — только у коммита, описанного целиком для панели файлов
 *     ({@code GET /api/git/commit}): вкладка «Коммит» ведёт по ним к предыдущему коммиту. В истории
 *     и в ответах инструментов их нет — модели они ничего не дают, а стоят по 40 знаков на коммит
 */
public record GitCommit(
        String hash,
        String shortHash,
        String author,
        String email,
        OffsetDateTime date,
        String message,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String body,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<GitDiffEntry> files,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<String> parents)
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
