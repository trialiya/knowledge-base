package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.DEFAULT_CONVERSATION_ID;
import static io.github.trialiya.kb.utils.ChatUtils.context;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import io.github.trialiya.kb.model.search.SearchAgentResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.tools.ProjectContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;

/**
 * Search "sub-agent" exposed to the main chat model as a single tool ({@code searchCodebase}).
 *
 * <p>Each call runs an isolated, stateless mini agent: a dedicated system prompt plus a restricted,
 * <b>read-only</b> tool set (git grep / file read / knowledge-base search). The tool-call loop is
 * driven manually through {@link ToolCallingManager} so we can enforce a hard iteration cap —
 * agent-as-tool's main risk is runaway cost.
 *
 * <p>Design choices:
 *
 * <ul>
 *   <li><b>No chat memory.</b> The sub-agent never reads the parent conversation. Its whole input
 *       is what the call hands over: the {@code task}, plus the optional {@code context} briefing
 *       the caller chose to include. Nothing crosses that boundary on its own, in either direction.
 *   <li><b>Manual loop with a cap.</b> When the cap is hit we do NOT dump the last raw tool output;
 *       instead we issue a final summarization call with {@code tool_choice=none} — the tool set
 *       stays declared (so the gathered evidence remains a cache-eligible prefix) but the model
 *       cannot invoke anything — asking it to summarize what it has gathered.
 *   <li><b>Self-correcting tool errors.</b> Spring AI's default tool-execution exception processor
 *       feeds a failed call's error back to the model as the tool result, so a model that emitted
 *       malformed arguments is asked (by the prompt) to fix them and retry within the loop. If an
 *       exception still escapes, we degrade gracefully to a summary instead of breaking the parent.
 * </ul>
 */
@Slf4j
public class SearchAgentService {

    private static final String SUMMARIZE_BUDGET =
            """
            Лимит шагов поиска исчерпан. Не запрашивай больше инструментов. \
            Сформулируй итоговый отчёт СТРОГО на основе уже полученных результатов инструментов выше \
            (формат: Итог; Места — список path:line; Связи). \
            В конце добавь строку: "(достигнут лимит шагов — результат может быть неполным)".""";

    private static final String SUMMARIZE_DONE =
            """
            Сформулируй итоговый отчёт СТРОГО на основе полученных результатов инструментов выше \
            (формат: Итог; Места — список path:line; Связи). Не запрашивай больше инструментов.""";

    private final OpenAiChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final SubAgentConfig config;
    private final String systemPrompt;
    private final ToolCallback[] toolCallbacks;

    /**
     * Only to canonicalize the project the run works on — the id the report echoes has to be the
     * one a repository actually answers by, not the raw argument (which may be absent, or name the
     * default project explicitly). Reading is the sub-agent's tools' business, not this class's.
     */
    private final GitRegistry gitRegistry;

    public SearchAgentService(
            OpenAiChatModel chatModel,
            ToolCallingManager toolCallingManager,
            SubAgentConfig config,
            Resource systemPrompt,
            String extraInstructions,
            ToolCallback[] toolCallbacks,
            GitRegistry gitRegistry) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.config = config;
        this.gitRegistry = gitRegistry;
        String basePrompt = readResource(systemPrompt);
        this.systemPrompt =
                extraInstructions.isBlank() ? basePrompt : basePrompt + "\n\n" + extraInstructions;
        this.toolCallbacks = toolCallbacks.clone();
        log.info(
                "SearchAgentService ready: model={}, maxIterations={}, tools={}",
                config.modelId(),
                config.maxIterations(),
                java.util.Arrays.stream(toolCallbacks)
                        .map(c -> c.getToolDefinition().name())
                        .toList());
    }

    /**
     * Runs the search sub-agent for a single task and returns a compact, citation-bearing report.
     * Never throws into the parent tool loop — failures are returned as a short message string.
     *
     * @param task detailed natural-language search task
     * @param context optional briefing from the calling conversation: what it already established
     *     (findings, paths and names ruled in or out, the constraint behind the question) and what
     *     it wants from the report beyond the task. The sub-agent has no other way to learn any of
     *     it — see the "no chat memory" note on the class
     * @param scope optional area hint: "code" | "docs" | "all"
     * @param pathGlob optional glob to restrict code paths (e.g. {@code "backend/**\/*.java"})
     * @param parentContext the parent tool context, for the conversation id and the run's project —
     *     the sub-agent reads the same repository the chat that called it does
     * @param requestedProject the repository the caller named instead, for a cross-project search;
     *     {@code null} or blank — the parent run's own project. The whole sub-run reads it: it is
     *     what the sub-agent's own tools receive as their context project
     */
    public SearchAgentResult run(
            String task,
            @Nullable String context,
            @Nullable String scope,
            @Nullable String pathGlob,
            @Nullable ToolContext parentContext,
            @Nullable String requestedProject) {
        final long startMs = System.currentTimeMillis();
        final AtomicReference<RunTokenUsage.Tally> usage =
                new AtomicReference<>(RunTokenUsage.Tally.EMPTY);
        final String conversationId =
                parentContext != null ? conversationId(parentContext) : DEFAULT_CONVERSATION_ID;
        // Canonical, not the raw argument: it goes into the report's echo, which the write guard
        // (ToolInvocationCollector#hasSeenFile) compares against a canonical id. An unknown id
        // fails here, loudly, instead of silently searching the default repository.
        final String projectId =
                gitRegistry
                        .forProject(ProjectContext.resolve(parentContext, requestedProject))
                        .project()
                        .id();
        final String fullTask = buildTask(task, context, scope, pathGlob);

        final OpenAiChatOptions toolOptions =
                OpenAiChatOptions.builder()
                        .model(config.modelId())
                        .maxTokens(config.maxTokens())
                        .temperature(0.0)
                        .toolCallbacks(toolCallbacks)
                        .toolContext(context(conversationId).project(projectId).build())
                        .build();

        final List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(fullTask));

        Prompt prompt = new Prompt(messages, toolOptions);
        log.info("[{}] search sub-agent start: task='{}'", conversationId, truncate(task, 160));

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
            add(usage, response);
        } catch (Exception e) {
            log.error("[{}] search sub-agent initial call failed", conversationId, e);
            return result(
                    conversationId,
                    projectId,
                    "Поиск не выполнен: " + rootMessage(e),
                    false,
                    0,
                    startMs,
                    usage);
        }

        int hops = 0;
        while (response != null && response.hasToolCalls()) {
            if (hops >= config.maxIterations()) {
                log.info(
                        "[{}] search sub-agent hit iteration cap ({}), summarizing",
                        conversationId,
                        config.maxIterations());
                String text = summarize(prompt, conversationId, fullTask, SUMMARIZE_BUDGET, usage);
                return result(conversationId, projectId, text, false, hops, startMs, usage);
            }

            final ToolExecutionResult exec;
            try {
                exec = toolCallingManager.executeToolCalls(prompt, response);
            } catch (Exception e) {
                // Default exception processor normally turns tool errors into tool-result messages
                // so the model self-corrects within the loop; this is the backstop for anything
                // that still escapes. Summarize what we have rather than break the parent.
                log.warn(
                        "[{}] search sub-agent tool execution failed: {}",
                        conversationId,
                        e.getMessage());
                String text = summarize(prompt, conversationId, fullTask, SUMMARIZE_BUDGET, usage);
                return result(conversationId, projectId, text, false, hops, startMs, usage);
            }

            prompt = new Prompt(exec.conversationHistory(), toolOptions);
            try {
                response = chatModel.call(prompt);
                add(usage, response);
            } catch (Exception e) {
                log.warn(
                        "[{}] search sub-agent follow-up call failed: {}",
                        conversationId,
                        e.getMessage());
                String text = summarize(prompt, conversationId, fullTask, SUMMARIZE_BUDGET, usage);
                return result(conversationId, projectId, text, false, hops, startMs, usage);
            }
            hops++;
        }

        final Generation result = response == null ? null : response.getResult();
        final String text = result == null ? null : result.getOutput().getText();
        if (text == null || text.isBlank()) {
            // Model stopped without producing prose — ask it to summarize the gathered evidence.
            String summary = summarize(prompt, conversationId, fullTask, SUMMARIZE_DONE, usage);
            return result(conversationId, projectId, summary, true, hops, startMs, usage);
        }
        return result(conversationId, projectId, text, true, hops, startMs, usage);
    }

    /**
     * Builds the result record and logs the response and token usage (the request's deliverable).
     */
    private SearchAgentResult result(
            String conversationId,
            String project,
            String report,
            boolean complete,
            int hops,
            long startMs,
            AtomicReference<RunTokenUsage.Tally> usage) {
        long durationMs = System.currentTimeMillis() - startMs;
        final RunTokenUsage spent = usage.get().view();
        log.info(
                "[{}] search sub-agent done: project={}, complete={}, hops={}, {} ms, model={},"
                        + " context={}, generated={}, billed={} over {} call(s), report='{}'",
                conversationId,
                project,
                complete,
                hops,
                durationMs,
                config.modelId(),
                spent.contextTokens(),
                spent.outputTokens(),
                spent.promptTokens() + spent.outputTokens(),
                spent.modelCalls(),
                truncate(report, 1000));
        return new SearchAgentResult(
                project, report, complete, hops, durationMs, config.modelId(), spent);
    }

    /**
     * Учитывает замер одного обращения суб-агента. Правило то же, что у чата ({@link
     * RunTokenUsage.Tally}), и это важнее, чем кажется: суб-агент — тоже цикл вызовов, где каждое
     * следующее обращение несёт всю предыдущую переписку заново, поэтому простая сумма prompt'ов
     * росла бы квадратично от числа шагов и говорила бы о «размере поиска» неправду.
     */
    private static void add(
            AtomicReference<RunTokenUsage.Tally> usage, @Nullable ChatResponse response) {
        usage.updateAndGet(tally -> tally.with(TokenUsage.of(response)));
    }

    /**
     * Final, tool-less call: the model must answer from the evidence already in {@code prompt}.
     * {@code prompt} never contains a dangling tool-call assistant turn at the call sites above, so
     * the message history stays valid for the provider API.
     *
     * <p>The original task is restated alongside the instruction: after many tool-result turns the
     * model can drift, and re-anchoring on what was actually asked keeps the final report on point.
     */
    private String summarize(
            Prompt prompt,
            String conversationId,
            String fullTask,
            String instruction,
            AtomicReference<RunTokenUsage.Tally> usage) {
        final List<Message> messages = new ArrayList<>(prompt.getInstructions());
        messages.add(new UserMessage("Напоминание исходной задачи:\n" + fullTask));
        messages.add(new UserMessage(instruction));

        final OpenAiChatOptions finalOptions =
                OpenAiChatOptions.builder()
                        .model(config.modelId())
                        .maxTokens(config.maxTokens())
                        .temperature(0.0)
                        // Keep the SAME tool set as the loop calls, then forbid their use with
                        // tool_choice=none. Dropping the tools here would change the request's
                        // `tools` array, and OpenAI prompt caching only reuses a prefix when that
                        // array is identical across requests — so a tool-less final call would
                        // force a full cache miss on the whole accumulated conversation (up to
                        // maxIterations rounds of tool output). tool_choice=none preserves the
                        // "the model cannot request more" guarantee without busting the cache.
                        .toolCallbacks(toolCallbacks)
                        .toolChoice("none")
                        .build();

        try {
            final ChatResponse summary = chatModel.call(new Prompt(messages, finalOptions));
            add(usage, summary);
            final Generation result = summary.getResult();
            final String text = result == null ? null : result.getOutput().getText();
            return (text == null || text.isBlank()) ? "Поиск не дал результатов." : text;
        } catch (Exception e) {
            log.error("[{}] search sub-agent summarization failed", conversationId, e);
            return "Поиск прерван: " + rootMessage(e);
        }
    }

    /**
     * Задача сабагенту одной строкой. Контекст стоит ПОСЛЕ задачи, а не перед ней: задача — то, за
     * чем вызвали, и она же повторяется при финальной суммаризации ({@link #summarize}); брифинг же
     * бывает длинным, и открывать им запрос значило бы топить формулировку под чужими фактами.
     */
    private static String buildTask(
            String task,
            @Nullable String context,
            @Nullable String scope,
            @Nullable String pathGlob) {
        final StringBuilder sb = new StringBuilder("SEARCH TASK:\n").append(task);
        if (context != null && !context.isBlank()) {
            // Названо чужим — «known to the caller», а не «известно тебе»: это не находки
            // сабагента, проверять их он не обязан, а вот противоречие им — повод сказать об этом
            // в отчёте, а не молча подстроиться.
            sb.append("\n\nKNOWN TO THE CALLER (conversation context and report requirements):\n")
                    .append(context.trim());
        }
        if (scope != null && !scope.isBlank()) {
            sb.append("\n\nScope: ").append(scope.trim());
        }
        if (pathGlob != null && !pathGlob.isBlank()) {
            sb.append("\nPath restriction (glob): ").append(pathGlob.trim());
        }
        return sb.toString();
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read search-agent system prompt", e);
        }
    }

    // Reference comparison on purpose: the guard is against a cause chain that points at
    // itself, which equals() would not catch.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
