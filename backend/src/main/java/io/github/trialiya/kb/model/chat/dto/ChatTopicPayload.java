package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Payload события {@link ChatEventType#CHAT_TOPIC}.
 *
 * @param topic отображаемое название после записи — то же, что {@link Chat#topic()}. Не всегда
 *     равно {@link #aiTopic}: чат могли переименовать, пока шёл запрос названия, и тогда вкладке
 *     нельзя затирать название пользователя предложенным
 * @param aiTopic название от ИИ, известное на момент события; {@code null} — ИИ чат ещё не называл
 */
public record ChatTopicPayload(String topic, @Nullable String aiTopic) {}
