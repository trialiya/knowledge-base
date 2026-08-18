package io.github.trialiya.kb.config;

import io.github.trialiya.kb.advisor.MessageLoggingAdvisor;
import io.github.trialiya.kb.advisor.ToolPreparingAdvisor;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
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
import io.github.trialiya.kb.service.ContextItemService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.ScriptGuideService;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.service.script.ScriptCancelledException;
import io.github.trialiya.kb.service.script.ScriptRunner;
import io.github.trialiya.kb.tools.ChatToolset;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
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
     * When absent, {@code chatClient} simply omits the tools — read-only mode needs no other
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
     * <p>Whether scripts may also write is a second decision, made by {@code ScriptEditPolicy} from
     * {@code kb.git.edit-enabled} + a writable tree + {@code kb.script.edit-enabled}. When the tool
     * is absent, {@code ScriptGuideService} also yields an empty prompt fragment, so the model is
     * never told about a tool it does not have.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.script", name = "enabled", havingValue = "true")
    public ScriptFunction scriptFunction(ScriptRunner scriptRunner) {
        log.info("Script tool enabled (runScript)");
        return ScriptFunction.forChat(scriptRunner);
    }

    /**
     * Replaces Spring AI's own processor for one reason: {@link ScriptCancelledException} has to
     * stay an exception.
     *
     * <p>A tool that throws is normally a tool that failed, and the default answer — hand the model
     * the exception message as the tool's result — is the right one for a failure: the model reads
     * what went wrong and tries something else. Cancellation is not a failure. The run it belonged
     * to is already disposed, so a result there would restart a conversation the user stopped, at
     * cost, with nobody reading the answer. Listing the class here makes the processor rethrow it,
     * which is what {@code ScriptRunner} assumed all along.
     *
     * <p>Everything else keeps the framework's behaviour, including {@code
     * spring.ai.tools.throw-exception-on-error} — this is one exception added to the rethrow list,
     * not a change of policy.
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor(
            ToolCallingProperties properties) {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(properties.isThrowExceptionOnError())
                .rethrowExceptions(List.of(ScriptCancelledException.class))
                .build();
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
     * entirely (so nothing reads {@code allowed-tools}) and {@code chatClient} simply omits the
     * {@code searchCodebase} tool.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.search.subagent", name = "enabled", havingValue = "true")
    public SearchAgentService searchAgentService(
            ChatModelRegistry chatModelRegistry,
            ToolCallingManager toolCallingManager,
            SubAgentConfig subAgentConfig,
            @Value("classpath:prompt/search-agent.md") Resource searchAgentPrompt,
            GitFunction gitFunction,
            DocumentFunction documentFunction,
            ScriptProperties scriptProperties,
            ScriptRunner scriptRunner,
            ScriptGuideService scriptGuideService,
            ChatModelProperties chatModelProperties) {
        // Two gates, and both matter. kb.script.enabled decides whether the tool exists anywhere —
        // without it the sub-agent's allow-list must not be able to conjure one up. Given that, the
        // sub-agent gets its own copy, forced read-only: its allow-list may include runScript, but
        // never the ability to write, whatever the main chat is allowed to do.
        boolean scriptsAvailable = subAgentScriptsAvailable(scriptProperties, subAgentConfig);
        List<Object> functions = new ArrayList<>(List.of(gitFunction, documentFunction));
        if (scriptsAvailable) {
            functions.add(ScriptFunction.readOnly(scriptRunner));
        }
        ToolCallback[] readOnly =
                Stream.of(ToolCallbacks.from(functions.toArray()))
                        .filter(
                                cb ->
                                        subAgentConfig
                                                .allowedTools()
                                                .contains(cb.getToolDefinition().name()))
                        .toArray(ToolCallback[]::new);
        // The handbook is long, and it is also the only place the sub-agent is told scripts exist —
        // so it goes in exactly when the tool does. The sub-agent's own model (kb.search.subagent
        // .model-id) can differ from the main chat's, so its weak/strong flag is looked up
        // separately rather than inherited from whichever model the current chat turn resolved to.
        String scriptInstructions =
                scriptsAvailable
                        ? scriptGuideService.readOnlyInstructions(
                                chatModelProperties.isWeak(subAgentConfig.modelId()))
                        : "";
        // The sub-agent's model may be one of the kb.chat.models entries with an endpoint of its
        // own, so the connection is looked up by id like the main chat's, not taken as the default.
        return new SearchAgentService(
                chatModelRegistry.forModel(subAgentConfig.modelId()),
                toolCallingManager,
                subAgentConfig,
                searchAgentPrompt,
                scriptInstructions,
                readOnly);
    }

    /**
     * Whether the search sub-agent gets {@code runScript}. Both halves are load-bearing: {@code
     * kb.script.enabled} decides whether the tool exists at all, so listing it in {@code
     * allowed-tools} cannot conjure one up in a deployment that switched scripts off — which would
     * otherwise leave the sub-agent running scripts with no handbook to run them by.
     *
     * <p>Package-private so {@code ChatConfigSubAgentScriptsAvailableTest} can pin it directly —
     * the decision is one boolean, and a test that re-derived it would only be testing its own
     * copy.
     */
    static boolean subAgentScriptsAvailable(
            ScriptProperties scriptProperties, SubAgentConfig subAgentConfig) {
        return scriptProperties.enabled() && subAgentConfig.allowedTools().contains("runScript");
    }

    /**
     * The tool set of the main chat, assembled in one place so that the {@code ChatClient} and the
     * Settings catalogue ({@code ToolCatalogService}) cannot disagree about what the model can
     * call. Which tools are in it is decided by the opt-ins documented on the beans above.
     */
    @Bean
    public ChatToolset chatToolset(
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            GitFunction gitFunction,
            ObjectProvider<GitEditFunction> gitEditFunction,
            DocumentFunction documentFunction,
            AttachmentService attachmentService,
            ContextItemService contextItemService,
            ObjectProvider<SearchAgentService> searchAgentService,
            ObjectProvider<ScriptFunction> scriptFunction,
            McpProperties mcpProperties,
            ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        List<Object> functions =
                new ArrayList<>(
                        List.of(
                                new TopicFunction(chatTopicRepository),
                                new MessageLookupFunction(
                                        chatMessageRepository, contextItemService),
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
        List<ToolCallback> mcpCallbacks =
                mcpProperties.enabled()
                        ? mcpToolCallbackProvider.stream()
                                .flatMap(provider -> Stream.of(provider.getToolCallbacks()))
                                .<ToolCallback>map(RecordingToolCallback::new)
                                .toList()
                        : List.of();
        if (mcpProperties.enabled()) {
            log.info("MCP tools enabled: {} tool(s) exposed to the model", mcpCallbacks.size());
        }
        List<ToolCallback> builtin =
                Stream.of(ToolCallbacks.from(functions.toArray()))
                        .<ToolCallback>map(RecordingToolCallback::new)
                        .toList();
        return new ChatToolset(builtin, mcpCallbacks);
    }

    /**
     * Connections to the model endpoints: the autoconfigured one, plus one per {@code
     * kb.chat.models} entry that named its own {@code base-url}/{@code api-key}.
     */
    @Bean
    public ChatModelRegistry chatModelRegistry(
            OpenAiChatModel openAiChatModel,
            ChatModelProperties chatModelProperties,
            OpenAiCommonProperties commonProperties,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
        ChatModelRegistry registry =
                ChatModelRegistry.build(
                        openAiChatModel,
                        chatModelProperties,
                        commonProperties,
                        chatProperties,
                        toolCallingManager,
                        observationRegistry,
                        meterRegistry,
                        httpClientCustomizers);
        if (!registry.ownEndpointModelIds().isEmpty()) {
            log.info("Models with an endpoint of their own: {}", registry.ownEndpointModelIds());
        }
        return registry;
    }

    /**
     * The {@link ChatClient} each chat run goes through — the default one, plus a copy over every
     * connection built above. Every one of them goes through {@link #buildChatClient}, so an
     * alternative endpoint cannot drift into a different advisor chain or a different tool set.
     */
    @Bean
    public ChatClientRegistry chatClientRegistry(
            ChatClient chatClient,
            ChatModelRegistry chatModelRegistry,
            ChatMemory chatMemory,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService) {
        Map<String, ChatClient> byModelId = new LinkedHashMap<>();
        for (String modelId : chatModelRegistry.ownEndpointModelIds()) {
            byModelId.put(
                    modelId,
                    buildChatClient(
                            chatModelRegistry.forModel(modelId),
                            chatMemory,
                            sysPrompt,
                            toolCallingManager,
                            chatToolset,
                            chatEventService));
        }
        return new ChatClientRegistry(chatClient, byModelId);
    }

    @Bean
    public ChatClient chatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService) {
        log.info("Model: {}", chatModel.getOptions());
        return buildChatClient(
                chatModel,
                chatMemory,
                sysPrompt,
                toolCallingManager,
                chatToolset,
                chatEventService);
    }

    private static ChatClient buildChatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService) {
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
                .defaultTools((Object[]) chatToolset.all())
                .build();
    }
}
