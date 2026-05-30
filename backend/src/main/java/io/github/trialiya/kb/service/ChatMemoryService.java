package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

@AllArgsConstructor
@Service
public class ChatMemoryService implements ChatMemoryRepository {
    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;

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
                                                LocalDateTime.now()))
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
}
