package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Payload события {@link ChatEventType#CHAT_TOPIC}.
 *
 * @param topic отображаемое название после записи — то же, что {@link Chat#topic()}. Не всегда
 *     равно {@link #aiTopic}: пользователь мог переименовать чат, пока шёл запрос, и тогда вкладке
 *     нельзя затирать его название предложенным
 * @param aiTopic только что записанное название от ИИ
 */
public record ChatTopicPayload(@Nullable String topic, String aiTopic) {}
