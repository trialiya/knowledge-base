package io.github.trialiya.kb.config;

import io.github.trialiya.kb.advisor.ToolPreparingAdvisor;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitEditFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.SearchAgentFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.ChatEventService;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
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
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

@Configuration
@Slf4j
public class ChatConfig {

    /**
     * Пул для фоновой генерации ответов (см. {@code ChatRunService}). Виртуальные потоки — по
     * одному на прогон; обёртка переносит {@link
     * org.springframework.security.core.context.SecurityContext} текущего пользователя на
     * worker-поток. {@code destroyMethod = "shutdown"} — чтобы при остановке контекста корректно
     * завершить нижележащий {@link ExecutorService} (обёртка делегирует ему shutdown).
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatRunExecutor() {
        return new DelegatingSecurityContextExecutorService(
                Executors.newVirtualThreadPerTaskExecutor());
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

    /**
     * Working-tree edit tools ({@code createFile}/{@code editFile}). Two gates, both required:
     *
     * <ul>
     *   <li>{@code kb.git.edit-enabled=true} — explicit opt-in, default off;
     *   <li>the working tree is actually writable — with a read-only mount the bean method returns
     *       {@code null} (Spring then registers no bean), so the model never sees tools that could
     *       only fail with an I/O error.
     * </ul>
     *
     * When absent, {@code chatClientBuilder} simply omits the tools — read-only mode needs no other
     * configuration. The search sub-agent is unaffected either way: its {@code allowed-tools} list
     * is an explicit allow-list of read-only tools.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.git", name = "edit-enabled", havingValue = "true")
    @Nullable
    public GitEditFunction gitEditFunction(GitService gitService) {
        if (!gitService.isRepoWritable()) {
            log.warn(
                    "kb.git.edit-enabled=true, but the repository working tree is not writable "
                            + "(read-only mount?) — file edit tools are NOT exposed to the model");
            return null;
        }
        log.info("Git edit tools enabled (createFile/editFile)");
        return new GitEditFunction(gitService);
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
            ObjectProvider<GitEditFunction> gitEditFunction,
            DocumentFunction documentFunction,
            AttachmentService attachmentService,
            ObjectProvider<SearchAgentService> searchAgentService,
            ChatEventService chatEventService) {
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
        // Present only when kb.git.edit-enabled=true AND the tree is writable (see gitEditFunction
        // bean) — in read-only mode the edit tools are not offered to the model at all.
        gitEditFunction.ifAvailable(functions::add);

        ToolCallback[] callbacks =
                Stream.of(ToolCallbacks.from(functions.toArray()))
                        .map(RecordingToolCallback::new)
                        .toArray(ToolCallback[]::new);

        // Advisor chain — outermost to innermost (ascending getOrder()):
        //
        //   MessageChatMemoryAdvisor  (HIGHEST_PRECEDENCE+200 = MIN+200)  — OUTSIDE the loop:
        //       loads conversation history once before the loop starts and saves only the user
        //       message + final assistant reply. Tool request/response messages are NOT written to
        //       the store. This is intentional — our JDBC ChatMemoryRepository does not support
        //       ToolResponseMessage / tool-call serialization. Matches Spring AI 1.x behaviour.
        //
        //   ToolCallingAdvisor        (DEFAULT_ORDER      = MIN+300)       — drives the tool loop.
        //       Because MessageChatMemoryAdvisor is OUTSIDE the loop (order < DEFAULT_ORDER),
        //       ToolCallingAdvisor manages its own internal conversation accumulation across
        //       iterations and no call to .disableInternalConversationHistory() is needed.
        //
        //   ToolPreparingAdvisor      (LOWEST_PRECEDENCE  = MAX)           — INSIDE the loop:
        //       called on every iteration; emits TOOL_PREPARING before each tool execution round.
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        advisors.add(ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build());
        advisors.add(new ToolPreparingAdvisor(chatEventService));

        return ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                .defaultSystem(sysPrompt)
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
