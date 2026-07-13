package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;

/** Одно совпадение поиска по сообщениям внутри чата (find-бар, Ctrl+F). */
public record MessageSearchHit(long id, LocalDateTime createdAt) {}
