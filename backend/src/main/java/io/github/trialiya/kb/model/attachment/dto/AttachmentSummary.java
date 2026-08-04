package io.github.trialiya.kb.model.attachment.dto;

import org.jspecify.annotations.Nullable;

/**
 * Метаданные вложения без его содержимого.
 *
 * <p>Существует ради одного: {@code content} лежит в той же строке таблицы, и обычный {@code
 * findById} тянет весь файл целиком. Опись приложенного собирается при каждом чтении истории — то
 * есть на каждой итерации tool-цикла, — и вытаскивать ради имени и размера мегабайт текста там
 * нельзя.
 *
 * @param summary AI-описание вложения, если его запрашивали
 * @param outline структурная опись (заголовки markdown / имена символов кода), посчитанная при
 *     загрузке; null, если файлу она не идёт
 */
public record AttachmentSummary(
        Long id,
        String fileName,
        String contentType,
        long fileSize,
        @Nullable String summary,
        @Nullable String outline) {}
