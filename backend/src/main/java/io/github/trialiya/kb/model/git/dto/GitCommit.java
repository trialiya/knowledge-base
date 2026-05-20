package io.github.trialiya.kb.model.git.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Запись из истории коммитов.
 *
 * @param hash полный SHA коммита
 * @param shortHash первые 8 символов SHA
 * @param author имя автора
 * @param email email автора
 * @param date дата коммита (ISO-8601 с offset)
 * @param message сообщение коммита (subject)
 * @param files список затронутых файлов (только если запрошены изменения)
 */
public record GitCommit(
        String hash,
        String shortHash,
        String author,
        String email,
        OffsetDateTime date,
        String message,
        List<GitDiffEntry> files) {}
