package io.github.trialiya.kb.model.chat.dto;

/**
 * Ссылка на объект, который пользователь приложил к сообщению, — как её присылает клиент.
 *
 * <p>Только вид и ссылка: подпись бэк берёт с самого объекта, а не с клиента (см. {@code
 * ContextItemService.resolve}). Поэтому это отдельный тип, а не {@code ContextItem} из меты — в
 * хранимую запись клиент напрямую не пишет.
 */
public record ContextItemRequest(String kind, String ref) {}
