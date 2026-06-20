package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.SearchAgentFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;

@Configuration
@Slf4j
public class ChatConfig {

    /**
     * Пул для фоновой генерации ответов (см. {@code ChatRunService}). Виртуальные потоки — по
     * одному на прогон; обёртка переносит {@link
     * org.springframework.security.core.context.SecurityContext} текущего пользователя на
     * worker-поток.
     */
    @Bean
    public Executor chatRunExecutor() {
        return new DelegatingSecurityContextExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryService chatMemoryService) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryService)
                .maxMessages(50)
                .build();
    }

    @Bean
    public GitFunction gitFunction(GitService gitService) {
        return new GitFunction(gitService);
    }

    @Bean
    public DocumentFunction documentFunction(
            DocumentService documentService, AttachmentService attachmentService) {
        return new DocumentFunction(documentService, attachmentService);
    }

    /**
     * The search sub-agent. Its tool set is the read-only subset of the git/document tools allowed
     * by {@code kb.search.subagent.allowed-tools}. Tools are NOT wrapped in {@link
     * RecordingToolCallback} — the sub-agent's internal steps are not part of the user-facing
     * invocation log. {@code searchCodebase} is excluded by construction (the allow-list contains
     * only git/document tools), which is the recursion guard.
     *
     * <p>Only wired when {@code kb.search.subagent.enabled=true}; when disabled the bean is absent
     * entirely (so nothing reads {@code allowed-tools}) and {@code chatClientBuilder} simply omits
     * the {@code searchCodebase} tool.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.search.subagent", name = "enabled", havingValue = "true")
    public SearchAgentService searchAgentService(
            OpenAiChatModel openAiChatModel,
            ToolCallingManager toolCallingManager,
            SubAgentConfig subAgentConfig,
            @Value("classpath:prompt/search-agent.md") Resource searchAgentPrompt,
            GitFunction gitFunction,
            DocumentFunction documentFunction) {
        ToolCallback[] readOnly =
                Stream.of(ToolCallbacks.from(gitFunction, documentFunction))
                        .filter(
                                cb ->
                                        subAgentConfig
                                                .allowedTools()
                                                .contains(cb.getToolDefinition().name()))
                        .toArray(ToolCallback[]::new);
        return new SearchAgentService(
                openAiChatModel, toolCallingManager, subAgentConfig, searchAgentPrompt, readOnly);
    }

    @Bean
    public ChatClient chatClientBuilder(
            ChatModel chatModel,
            ChatMemory chatMemory,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            GitFunction gitFunction,
            DocumentFunction documentFunction,
            AttachmentService attachmentService,
            ObjectProvider<SearchAgentService> searchAgentService) {
        log.info("Model: {}", chatModel.getDefaultOptions());

        List<Object> functions =
                new ArrayList<>(
                        List.of(
                                new TopicFunction(chatTopicRepository),
                                new MessageLookupFunction(chatMessageRepository),
                                documentFunction,
                                gitFunction,
                                new AttachmentFunction(attachmentService)));
        // Present only when kb.search.subagent.enabled=true (see searchAgentService bean).
        searchAgentService.ifAvailable(svc -> functions.add(new SearchAgentFunction(svc)));

        ToolCallback[] callbacks =
                Stream.of(ToolCallbacks.from(functions.toArray()))
                        .map(RecordingToolCallback::new)
                        .toArray(ToolCallback[]::new);
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultSystem(sysPrompt)
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
