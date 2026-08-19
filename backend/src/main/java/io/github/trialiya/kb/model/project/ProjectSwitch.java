package io.github.trialiya.kb.model.project;

/**
 * Смена проекта, случившаяся очередным сообщением чата: {@code from} → {@code to}. Оба id —
 * канонические (см. {@code ProjectCatalog}); {@code from} может быть id, уже выбывшим из
 * конфигурации, — канонизировать его не во что, а сказать, откуда ушли, всё равно надо.
 *
 * <p>Рождается при разрешении прогона ({@code ChatController#projectSwitch}) и оседает маркером в
 * {@code chat_message.meta} вопроса, который смену совершил ({@code ChatMessageMeta}).
 */
public record ProjectSwitch(String from, String to) {}
