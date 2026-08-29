package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * Нагрузка события {@link ChatEventType#COMPACT_ERROR}: сжатие не состоялось.
 *
 * <p>Кроме причины несёт замер несостоявшегося раунда — тот, который провайдер всё равно посчитал
 * (см. {@code CompactService}). Записан он на строку самой команды, и вкладка, дождавшаяся ошибки,
 * обязана досчитать его в итог чата сразу: иначе её статистика расходилась бы с той, что она увидит
 * после перезагрузки.
 *
 * @param message текст ошибки — то же, что раньше ехало единственным полем нагрузки
 * @param messageId id строки команды {@code /compact}, на которую записан замер; {@code null} —
 *     раунда не было вовсе (сжатие упало до обращения к модели)
 * @param usage токены раунда; {@code null} — эндпоинт замера не отдал либо раунда не было
 */
public record CompactErrorPayload(
        String message, @Nullable Long messageId, @Nullable RunTokenUsage usage) {}
