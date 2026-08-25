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
 * @param project id репозитория, из истории которого взят коммит — обязателен в ответе, потому что
 *     {@code getCommitLog} и {@code getCommitDiff} умеют читать не только активный проект чата (см.
 *     {@code GitFunction}), а хеш сам по себе о репозитории не говорит
 * @param hash полный SHA коммита
 * @param shortHash сокращённый SHA (минимум 7 символов, длиннее при неоднозначности)
 * @param author имя автора
 * @param email email автора
 * @param date дата коммита (ISO-8601 с offset)
 * @param message сообщение коммита (subject)
 * @param files список затронутых файлов (только если запрошены изменения)
 */
public record GitCommit(
        String project,
        String hash,
        String shortHash,
        String author,
        String email,
        OffsetDateTime date,
        String message,
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
                "project", project,
                "shortHash", shortHash,
                "author", author,
                "email", email,
                "date", date,
                "message", message,
                "changesFilesCount", files != null ? files.size() : 0);
    }
}
