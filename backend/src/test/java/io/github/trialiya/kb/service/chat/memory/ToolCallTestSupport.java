package io.github.trialiya.kb.service.chat.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;

/** Общая обвязка для юнит-тестов вокруг протокольных tool-данных. */
final class ToolCallTestSupport {

    private ToolCallTestSupport() {}

    /**
     * Учит мок репозитория вести себя как БД: возвращать сохранённые ряды с проставленными id. На
     * них {@link ToolCallService#index} строит messageId строк {@code tool_call_index}.
     */
    static void echoSavedWithIds(ChatMessageRepository messageRepo) {
        final AtomicLong nextId = new AtomicLong(100);
        when(messageRepo.saveAll(any()))
                .thenAnswer(
                        inv -> {
                            final Iterable<ChatMessageEntity> entities = inv.getArgument(0);
                            final List<ChatMessageEntity> saved = new ArrayList<>();
                            for (ChatMessageEntity e : entities) {
                                saved.add(
                                        new ChatMessageEntity(
                                                nextId.incrementAndGet(),
                                                e.getConversationId(),
                                                e.getContent(),
                                                e.getType(),
                                                e.getPosition(),
                                                e.isSummarized(),
                                                e.isSummary(),
                                                e.getCreatedAt(),
                                                e.getMeta(),
                                                e.getToolData()));
                            }
                            return saved;
                        });
    }

    static AssistantMessage assistantWithCalls(AssistantMessage.ToolCall... calls) {
        return new AssistantMessage("", Map.of(), List.of(calls), List.of()) {};
    }

    static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    static ChatMessageEntity entity(
            String conversationId,
            MessageType type,
            @Nullable ChatMessageMeta meta,
            @Nullable ToolData toolData) {
        return new ChatMessageEntity(
                1L, conversationId, "", type, 1, false, false, LocalDateTime.now(), meta, toolData);
    }
}
