package io.github.trialiya.kb.config;

import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.micrometer.core.instrument.util.IOUtils;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ChatConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryService chatMemoryService) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryService)
                .maxMessages(50)
                .build();
    }

    @Bean
    public ChatClient chatClientBuilder(
            ChatModel chatModel,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            DocumentService documentService,
            GitService gitService,
            AttachmentService attachmentService) {
        log.info("Model: {}", chatModel.getDefaultOptions());
        ToolCallback[] callbacks =
                Stream.of(
                                ToolCallbacks.from(
                                        new TopicFunction(chatTopicRepository),
                                        new MessageLookupFunction(chatMessageRepository),
                                        new DocumentFunction(documentService, attachmentService),
                                        new GitFunction(gitService),
                                        new AttachmentFunction(attachmentService)))
                        .map(RecordingToolCallback::new)
                        .toArray(ToolCallback[]::new);
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultSystem(
                        IOUtils.toString(
                                ChatConfig.class
                                        .getClassLoader()
                                        .getResourceAsStream("prompt/sys.md")))
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
