package io.github.trialiya.kb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.tool.ToolCallEntity;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@AllArgsConstructor
@Slf4j
@Service
public class ChatMemoryService implements ChatMemoryRepository {

    /**
     * Инструменты, отметки о вызове которых не сохраняем: служебные либо те, что полезно звать
     * заново.
     */
    private static final Set<String> SKIP_TOOLS =
            Set.of(
                    "recordChatInsights",
                    "getUserName",
                    "getCurrentDateTime",
                    "getOriginalMessages");

    private static final String PREAMBLE =
            """
        Инструменты, уже вызванные ранее в этом чате (с урезанным результатом).
        Служебные данные только для справки.
        """;

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMessageRepository.deleteChatMessageByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        final AtomicLong lastPosition = new AtomicLong();
        final List<ChatMessageEntity> newMessagesToSave =
                messages.stream()
                        .filter(message -> Strings.isNotBlank(message.getText()))
                        .filter(
                                message -> {
                                    if (message instanceof IMessage iMessage) {
                                        lastPosition.set(iMessage.chatMessage().getPosition());
                                        return false;
                                    }
                                    return true;
                                })
                        .map(
                                message ->
                                        new ChatMessageEntity(
                                                0,
                                                conversationId,
                                                message.getText(),
                                                message.getMessageType(),
                                                lastPosition.incrementAndGet(),
                                                false,
                                                false,
                                                LocalDateTime.now(),
                                                null))
                        .toList();
        chatMessageRepository.saveAll(newMessagesToSave);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return chatMessageRepository
                .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAt(conversationId)
                .stream()
                .map(ChatMessageEntity::getMessage)
                .map(Message.class::cast)
                .toList();
    }

    @Override
    public List<String> findConversationIds() {
        return chatTopicRepository.findIdsByUserOrderByUpdatedAtDesc(ChatUtils.getUser());
    }

    public List<ChatMessageEntity> findChatMessageByConversationId(String conversationId) {
        return chatMessageRepository.findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAt(
                conversationId);
    }

    public Page findLatestPage(String conversationId, int limit) {
        return toPage(chatMessageRepository.findLatest(conversationId, limit + 1), limit);
    }

    public Page findPageBefore(
            String conversationId, LocalDateTime beforeCreatedAt, long beforeId, int limit) {
        return toPage(
                chatMessageRepository.findBefore(
                        conversationId, beforeCreatedAt, beforeId, limit + 1),
                limit);
    }

    private Page toPage(List<ChatMessageEntity> rowsDesc, int limit) {
        boolean hasMore = rowsDesc.size() > limit;
        List<ChatMessageEntity> chrono =
                (hasMore ? rowsDesc.subList(0, limit) : rowsDesc).reversed();
        MessageCursor cursor =
                chrono.isEmpty()
                        ? null
                        : new MessageCursor(
                                chrono.getFirst().getCreatedAt(), chrono.getFirst().getId());
        return new Page(chrono, hasMore, cursor);
    }

    public record Page(
            List<ChatMessageEntity> messages, boolean hasMore, MessageCursor oldestCursor) {}

    @Transactional
    public void saveToolCallIncremental(
            String conversationId, String runId, int callIndex, ToolInvocation tc) {
        if (SKIP_TOOLS.contains(tc.name())) {
            return;
        }
        try {
            String resultMetaJson =
                    tc.resultMeta() != null && !tc.resultMeta().isEmpty()
                            ? objectMapper.writeValueAsString(tc.resultMeta())
                            : null;
            toolCallRepository.save(
                    new ToolCallEntity(
                            conversationId,
                            runId,
                            callIndex,
                            tc.name(),
                            tc.argumentsRaw(),
                            tc.status().name(),
                            tc.error(),
                            tc.resultText(),
                            resultMetaJson));
        } catch (JsonProcessingException e) {
            log.warn(
                    "Failed to serialize result meta for tool call {} in {}",
                    tc.name(),
                    conversationId,
                    e);
        }
    }

    @Transactional
    public void saveToolCalls(String conversationId, String runId, List<ToolInvocation> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        final List<ToolInvocation> filtered =
                toolCalls.stream().filter(tc -> !SKIP_TOOLS.contains(tc.name())).toList();
        if (filtered.isEmpty()) {
            return;
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(filtered);
        } catch (JsonProcessingException e) {
            // крошки некритичны — логируем и не ломаем ход
            log.warn("Failed to serialize tool calls for {}", conversationId, e);
            return;
        }

        final List<ToolInvocationMeta> meta = new ArrayList<>(filtered.size());
        for (ToolInvocation toolCall : filtered) {
            meta.add(toolCall.toMeta());
        }
        chatMessageRepository.save(
                new ChatMessageEntity(
                        0L,
                        conversationId,
                        PREAMBLE + "\n" + json,
                        MessageType.SYSTEM,
                        nextPosition(conversationId),
                        false,
                        false,
                        LocalDateTime.now(),
                        new ChatMessageMeta(runId, meta)));
    }

    private long nextPosition(String conversationId) {
        return chatMessageRepository
                        .findFirstByConversationIdOrderByPositionDesc(conversationId)
                        .map(ChatMessageEntity::getPosition)
                        .orElse(0L)
                + 1;
    }
}
