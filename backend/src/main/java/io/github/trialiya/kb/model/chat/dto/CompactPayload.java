package io.github.trialiya.kb.model.chat.dto;

/**
 * Нагрузка события {@link ChatEventType#COMPACT_DONE}: чем закончилось сжатие контекста.
 *
 * @param messages сколько живых сообщений ушло в сводку — включая протокольные TOOL-строки и уже
 *     существовавшие сводки, то есть ровно то, что до сжатия уезжало модели в каждом запросе
 * @param summaryChars длина получившейся сводки в символах — вместе с {@code messages} это ответ на
 *     «во сколько раз сжали», который иначе виден только в логах
 */
public record CompactPayload(int messages, int summaryChars) {}
