package io.github.trialiya.kb.config;

import io.github.trialiya.kb.advisor.MessageLoggingAdvisor;
import io.github.trialiya.kb.advisor.ToolPreparingAdvisor;
import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.functions.AttachmentFunction;
import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.functions.GitEditFunction;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.functions.ScriptFunction;
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
import io.github.trialiya.kb.service.script.ScriptRunner;
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
import org.springframework.ai.tool.ToolCallbackProvider;
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

    /**
     * NOT the size of the context sent to the model — that is decided by {@code SummarizeService}
     * (messages flagged {@code summarized} drop out of {@link
     * ChatMemoryService#findByConversationId}), and the thresholds live in {@code
     * kb.chat.summarize.*}.
     *
     * <p>{@code maxMessages} only acts on the write path: {@link MessageWindowChatMemory#add} trims
     * history + new messages to the last N and hands that list to {@code saveAll}. Reads go through
     * {@link MessageWindowChatMemory#get}, which is a bare {@code findByConversationId} with no
     * window at all. Since {@link ChatMemoryService#saveAll} is append-only — already persisted
     * messages are filtered out, nothing is ever deleted — the trim has no observable effect here.
     * It is kept high so that it also cannot silently drop a message from an unusually large single
     * {@code add()} batch.
     *
     * <p>Deliberately not surfaced in Settings → Модели: a number that changes nothing would only
     * read as the context limit, which is exactly the confusion the panel used to create.
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryService chatMemoryService) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryService)
                .maxMessages(300)
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

    /**
     * The {@code runScript} tool. Off by default: a script is still executed code, so it is an
     * explicit opt-in like {@code kb.mcp.enabled}, even though the engine it runs in has no
     * filesystem, no host classes and no threads (see {@code ScriptRunner}).
     *
     * <p>Read-only in this step — the injected {@code kb} object exposes listing, reading and
     * searching only. When the tool is absent, {@code ScriptGuideService} also yields an empty
     * prompt fragment, so the model is never told about a tool it does not have.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.script", name = "enabled", havingValue = "true")
    public ScriptFunction scriptFunction(ScriptRunner scriptRunner) {
        log.info("Script tool enabled (runScript)");
        return new ScriptFunction(scriptRunner);
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
            ObjectProvider<ScriptFunction> scriptFunction,
            ChatEventService chatEventService,
            McpProperties mcpProperties,
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        log.info("Model: {}", chatModel.getOptions());

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
        // Present only when kb.script.enabled=true (see scriptFunction bean).
        scriptFunction.ifAvailable(functions::add);

        // MCP-derived tools (see spring.ai.mcp.client.* connections) are merged in only when
        // kb.mcp.enabled=true — external MCP servers run arbitrary local commands or call
        // arbitrary URLs, so this stays an explicit opt-in even once servers are configured.
        ToolCallback[] mcpCallbacks =
                mcpProperties.enabled()
                        ? mcpToolCallbackProvider.stream()
                                .flatMap(provider -> Stream.of(provider.getToolCallbacks()))
                                .toArray(ToolCallback[]::new)
                        : new ToolCallback[0];
        if (mcpProperties.enabled()) {
            log.info("MCP tools enabled: {} tool(s) exposed to the model", mcpCallbacks.length);
        }
        ToolCallback[] callbacks =
                Stream.concat(
                                Stream.of(ToolCallbacks.from(functions.toArray())),
                                Stream.of(mcpCallbacks))
                        .map(RecordingToolCallback::new)
                        .toArray(ToolCallback[]::new);

        // Advisor chain — outermost to innermost (ascending getOrder()):
        //
        //   ToolCallingAdvisor        (DEFAULT_ORDER     = MIN+300)  — drives the tool loop.
        //       Internal conversation history is DISABLED: each iteration's prompt carries only
        //       [system, last tool response]; the rest of the context is re-read from ChatMemory
        //       by the memory advisor below. This is the Spring AI mode for persisting every
        //       segment (assistant text + tool_calls, tool responses) as a separate message.
        //
        //   MessageChatMemoryAdvisor  (MIN+400)                      — INSIDE the loop:
        //       before(): prepends the stored history and appends the new user/tool-response
        //       message to the store; after(): saves each iteration's assistant message —
        //       including intermediate segments with tool_calls. Requires a ChatMemoryRepository
        //       that round-trips tool messages (ChatMemoryService: chat_message.tool_data).
        //
        //   ToolPreparingAdvisor      (LOWEST_PRECEDENCE = MAX)      — INSIDE the loop:
        //       called on every iteration; emits TOOL_PREPARING before each tool execution round.
        //
        //   MessageLoggingAdvisor     (LOWEST_PRECEDENCE = MAX)      — INSIDE the loop, innermost:
        //       DEBUG-only, logs the exact message list about to be sent to the model on each
        //       iteration (post memory-prepend, post history-window trim). Off by default — enable
        //       via logging.level.io.github.trialiya.kb.advisor.MessageLoggingAdvisor=DEBUG.
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(
                ToolCallingAdvisor.builder()
                        .toolCallingManager(toolCallingManager)
                        .disableInternalConversationHistory()
                        .build());
        advisors.add(
                MessageChatMemoryAdvisor.builder(chatMemory)
                        .order(ToolCallingAdvisor.DEFAULT_ORDER + 100)
                        .build());
        advisors.add(new ToolPreparingAdvisor(chatEventService));
        advisors.add(new MessageLoggingAdvisor());

        return ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                .defaultSystem(sysPrompt)
                .defaultToolCallbacks(callbacks)
                .build();
    }
}
