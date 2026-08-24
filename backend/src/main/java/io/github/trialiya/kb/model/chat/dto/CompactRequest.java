package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /compact}: хвост команды {@code /compact <текст>}.
 *
 * @param instructions на чём сосредоточиться при сжатии; {@code null} или пустая строка — обычное
 *     сжатие без фокуса. В историю чата не попадает: команда — это управление, а не реплика диалога
 *     (см. {@code CompactService})
 */
public record CompactRequest(@Nullable String instructions) {}
