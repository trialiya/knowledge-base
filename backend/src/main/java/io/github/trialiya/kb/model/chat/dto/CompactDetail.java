package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;

/**
 * Детали одного сжатия — то, что показывает модалка за кнопкой «Подробнее» на плашке «контекст
 * сжат». Отдельным запросом, а не полем страницы истории: сводка бывает в десятки килобайт, а
 * читают её изредка и по одной.
 *
 * @param summary текст сводки без протокольной обёртки — ровно тот документ, который написала
 *     модель; обёртка адресована ей, а не читателю
 */
public record CompactDetail(
        long messageId, int messages, int summaryChars, LocalDateTime createdAt, String summary) {}
