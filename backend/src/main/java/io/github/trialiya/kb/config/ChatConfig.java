package io.github.trialiya.kb.config;

import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.DocumentService;
import io.micrometer.core.instrument.util.IOUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
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
            OpenAiChatModel openAiChatModel,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            DocumentService documentService,
            ToolCallingManager toolCallingManager) {
        log.info("Model: {}", openAiChatModel.getDefaultOptions());
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SingleCallToolCallAdvisor(
                                toolCallingManager,
                                chatTopicRepository,
                                chatMessageRepository,
                                "recordChatInsights"))
                .defaultSystem(
                        IOUtils.toString(
                                ChatConfig.class
                                        .getClassLoader()
                                        .getResourceAsStream("promt/sys.md")))
                .defaultTools(
                        new TopicFunction(chatTopicRepository),
                        new MessageLookupFunction(chatMessageRepository),
                        new DocumentFunction(documentService))
                .build();
    }
}
