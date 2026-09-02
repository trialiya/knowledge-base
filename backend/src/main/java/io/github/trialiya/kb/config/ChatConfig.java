package io.github.trialiya.kb.config;

import io.github.trialiya.kb.advisor.InterjectionAdvisor;
import io.github.trialiya.kb.advisor.MessageLoggingAdvisor;
import io.github.trialiya.kb.advisor.TokenUsageAdvisor;
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
import io.github.trialiya.kb.functions.SkillFunction;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.service.chat.script.ScriptCancelledException;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import io.github.trialiya.kb.service.document.DocumentService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
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

    @Bean
    public GitFunction gitFunction(GitRegistry gitRegistry) {
        return new GitFunction(gitRegistry);
    }

    /**
     * Working-tree edit tools ({@code createFile}/{@code editFile}). Exposed when <em>some</em>
     * project accepts writes — configured for it ({@code kb.projects[].edit-enabled}, defaulting to
     * {@code kb.projects[].edit-enabled}) and its working tree actually writable. With none, the
     * bean method returns {@code null} (Spring then registers no bean), so the model never sees
     * tools that could only fail with an I/O error.
     *
     * <p>Presence is therefore a weaker statement than it used to be: it says the tools are worth
     * offering, not that any given project accepts them. A call naming a read-only project is
     * refused by {@code GitRegistry#requireEditable}, through the tool error channel.
     *
     * <p>When absent, {@code chatClient} simply omits the tools — read-only mode needs no other
     * configuration. The search sub-agent is unaffected either way: its {@code allowed-tools} list
     * is an explicit allow-list of read-only tools.
     */
    @Bean
    @Nullable
    public GitEditFunction gitEditFunction(GitRegistry gitRegistry) {
        if (!gitRegistry.anyEditable()) {
            log.info("File edit tools are NOT exposed to the model: no project accepts writes");
            return null;
        }
        log.info("Git edit tools enabled (createFile/editFile)");
        return new GitEditFunction(gitRegistry);
    }

    /**
     * The {@code runScript} tool. Off by default: a script is still executed code, so it is an
     * explicit opt-in like {@code kb.mcp.enabled}, even though the engine it runs in has no
     * filesystem, no host classes and no threads (see {@code ScriptRunner}).
     *
     * <p>Whether scripts may also write is a second decision, made by {@code ScriptEditPolicy} from
     * the project's own permission ({@code GitRegistry#editsAllowed} — configured plus a writable
     * tree) and {@code kb.script.edit-enabled}. When the tool is absent, {@code ScriptGuideService}
     * also yields an empty prompt fragment, so the model is never told about a tool it does not
     * have.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kb.script", name = "enabled", havingValue = "true")
    public ScriptFunction scriptFunction(ScriptRunner scriptRunner, GitRegistry gitRegistry) {
        log.info("Script tool enabled (runScript)");
        return ScriptFunction.forChat(scriptRunner, gitRegistry);
    }

    /**
     * The {@code readSkill} tool — on-demand instruction files ({@code SkillService}). Absent when
     * there are no skills to read: today every skill is a half of the {@code runScript} handbook,
     * so {@code kb.script.enabled=false} means no tool and an empty {@code {skill_catalogue}} — the
     * model is never offered a reader with an empty shelf.
     */
    @Bean
    @Nullable
    public SkillFunction skillFunction(SkillService skillService) {
        if (!skillService.anySkills()) {
            log.info("Skill tool is NOT exposed to the model: no skills available");
            return null;
        }
        log.info("Skill tool enabled (readSkill)");
        return new SkillFunction(skillService);
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
            GitRegistry gitRegistry,
            ScriptGuideService scriptGuideService,
            SkillService skillService,
            ChatModelProperties chatModelProperties) {
        // Two gates, and both matter. kb.script.enabled decides whether the tool exists anywhere —
        // without it the sub-agent's allow-list must not be able to conjure one up. Given that, the
        // sub-agent gets its own copy, forced read-only: its allow-list may include runScript, but
        // never the ability to write, whatever the main chat is allowed to do.
        boolean scriptsAvailable = subAgentScriptsAvailable(scriptProperties, subAgentConfig);
        List<Object> functions = new ArrayList<>(List.of(gitFunction, documentFunction));
        if (scriptsAvailable) {
            functions.add(ScriptFunction.readOnly(scriptRunner, gitRegistry));
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
                        ? subAgentScriptInstructions(
                                scriptGuideService,
                                skillService,
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
                readOnly,
                gitRegistry);
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
     * The script handbook for the search sub-agent — the reference half whatever its model, plus
     * the {@code script-writing} skill inlined for a weak one.
     *
     * <p>Never the extended half: that half is an order to call {@code readSkill}, and the
     * sub-agent has no such tool (its tools are an explicit allow-list) and no skill catalogue in
     * its prompt. Obeying it would abort the search on a tool it cannot call; ignoring it would
     * cost a weak model the worked examples. Giving it the tool instead is the wrong trade — the
     * sub-agent runs on a hard iteration budget, and spending one on a document it could simply
     * have been handed buys nothing.
     *
     * <p>Package-private so {@code ChatConfigSubAgentScriptsAvailableTest} can pin it: it is the
     * one place where the sub-agent's prompt and its tool set have to agree.
     */
    static String subAgentScriptInstructions(
            ScriptGuideService scriptGuideService, SkillService skillService, boolean weak) {
        String reference = scriptGuideService.readOnlyInstructions(false);
        return weak ? reference + "\n\n" + skillService.textOf("script-writing") : reference;
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
            ObjectProvider<SkillFunction> skillFunction,
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
        // Present only when some project accepts writes (see gitEditFunction bean) — with none,
        // the edit tools are not offered to the model at all.
        gitEditFunction.ifAvailable(functions::add);
        // Present only when kb.script.enabled=true (see scriptFunction bean).
        scriptFunction.ifAvailable(functions::add);
        // Present only when there are skills to read (see skillFunction bean).
        skillFunction.ifAvailable(functions::add);

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
     * The shared connection, in place of the one the OpenAI autoconfiguration would contribute —
     * see {@link ChatModelRegistry#buildDefaultModel}. A bean rather than a detail of the registry
     * because the longest blocking calls hang on it directly: attachments, document summaries and
     * chat summarization take the model, not the registry.
     */
    @Bean
    public OpenAiChatModel openAiChatModel(
            OpenAiCommonProperties commonProperties,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
        return ChatModelRegistry.buildDefaultModel(
                commonProperties,
                chatProperties,
                toolCallingManager,
                observationRegistry,
                meterRegistry,
                httpClientCustomizers);
    }

    /**
     * Connections to the model endpoints: the shared one, plus one per {@code kb.chat.models} entry
     * that named its own {@code base-url}/{@code api-key}.
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
            ChatModelProperties chatModelProperties,
            ChatMemory chatMemory,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService,
            PendingMessageService pendingMessageService,
            RunRegistry runRegistry) {
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
                            chatEventService,
                            pendingMessageService,
                            runRegistry));
        }
        return new ChatClientRegistry(
                chatModelProperties.defaultModel().id(), chatClient, byModelId);
    }

    @Bean
    public ChatClient chatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            @Value("classpath:prompt/sys.md") Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService,
            PendingMessageService pendingMessageService,
            RunRegistry runRegistry) {
        log.info("Model: {}", chatModel.getOptions());
        return buildChatClient(
                chatModel,
                chatMemory,
                sysPrompt,
                toolCallingManager,
                chatToolset,
                chatEventService,
                pendingMessageService,
                runRegistry);
    }

    private static ChatClient buildChatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            Resource sysPrompt,
            ToolCallingManager toolCallingManager,
            ChatToolset chatToolset,
            ChatEventService chatEventService,
            PendingMessageService pendingMessageService,
            RunRegistry runRegistry) {
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
        //       including intermediate segments with tool_calls. Requires a ChatMemory that
        //       round-trips tool messages (ChatHistoryMemory: chat_message.tool_data).
        //
        //   InterjectionAdvisor       (MIN+450)                      — INSIDE the loop:
        //       delivers messages the user sent mid-run. MUST sit after the memory advisor:
        //       its before() runs once memory has persisted the iteration's tool responses,
        //       which is the only protocol-safe insertion point for a USER row (see the
        //       advisor's own javadoc).
        //
        //   TokenUsageAdvisor         (LOWEST_PRECEDENCE = MAX)      — INSIDE the loop:
        //       tallies the run's tokens. Must sit inside: the tool-call chunk carrying an
        //       iteration's usage never leaves the loop, so from outside only the last
        //       iteration would ever be counted.
        //
        //   MessageLoggingAdvisor     (LOWEST_PRECEDENCE - 1 = MAX-1) — INSIDE the loop, last
        //       before the model itself: DEBUG-only, measures the request about to be sent on each
        //       iteration (post memory-prepend, post history-window trim) — sizes, previews and a
        //       rolling prefix hash. A step above MAX on purpose: the chain is closed by the model
        //       call advisor sitting at MAX, and a tie with it is lost in a chain without the tool
        //       loop (see the advisor's own getOrder). Off by default — enable via
        //       logging.level.io.github.trialiya.kb.advisor.MessageLoggingAdvisor=DEBUG.
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
        advisors.add(new InterjectionAdvisor(pendingMessageService));
        advisors.add(new TokenUsageAdvisor(chatEventService, runRegistry));
        advisors.add(new MessageLoggingAdvisor());

        return ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                .defaultSystem(sysPrompt)
                .defaultTools((Object[]) chatToolset.all())
                .build();
    }
}
