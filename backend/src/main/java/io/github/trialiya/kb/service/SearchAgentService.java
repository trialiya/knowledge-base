package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.DEFAULT_CONVERSATION_ID;
import static io.github.trialiya.kb.utils.ChatUtils.buildContext;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.model.search.SearchAgentResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
 *   <li><b>No chat memory.</b> The only input is the {@code task} string; nothing leaks in or out
 *       of the parent conversation window.
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

    public SearchAgentService(
            OpenAiChatModel chatModel,
            ToolCallingManager toolCallingManager,
            SubAgentConfig config,
            Resource systemPrompt,
            ToolCallback[] toolCallbacks) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.config = config;
        this.systemPrompt = readResource(systemPrompt);
        this.toolCallbacks = toolCallbacks;
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
     * @param task detailed natural-language search task (the "context")
     * @param scope optional area hint: "code" | "docs" | "all"
     * @param pathGlob optional glob to restrict code paths (e.g. {@code "backend/**\/*.java"})
     * @param parentContext the parent tool context (used only to carry the conversation id)
     */
    public SearchAgentResult run(
            String task,
            @Nullable String scope,
            @Nullable String pathGlob,
            @Nullable ToolContext parentContext) {
        final long startMs = System.currentTimeMillis();
        final TokenUsage usage = new TokenUsage();
        final String conversationId =
                parentContext != null ? conversationId(parentContext) : DEFAULT_CONVERSATION_ID;
        final String fullTask = buildTask(task, scope, pathGlob);

        final OpenAiChatOptions toolOptions =
                OpenAiChatOptions.builder()
                        .model(config.modelId())
                        .maxTokens(config.maxTokens())
                        .temperature(0.0)
                        .toolCallbacks(toolCallbacks)
                        .toolContext(buildContext(conversationId))
                        .build();

        final List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(fullTask));

        Prompt prompt = new Prompt(messages, toolOptions);
        log.info("[{}] search sub-agent start: task='{}'", conversationId, truncate(task, 160));

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
            usage.add(response);
        } catch (Exception e) {
            log.error("[{}] search sub-agent initial call failed", conversationId, e);
            return result(
                    conversationId,
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
                return result(conversationId, text, false, hops, startMs, usage);
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
                return result(conversationId, text, false, hops, startMs, usage);
            }

            prompt = new Prompt(exec.conversationHistory(), toolOptions);
            try {
                response = chatModel.call(prompt);
                usage.add(response);
            } catch (Exception e) {
                log.warn(
                        "[{}] search sub-agent follow-up call failed: {}",
                        conversationId,
                        e.getMessage());
                String text = summarize(prompt, conversationId, fullTask, SUMMARIZE_BUDGET, usage);
                return result(conversationId, text, false, hops, startMs, usage);
            }
            hops++;
        }

        final String text = response == null ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            // Model stopped without producing prose — ask it to summarize the gathered evidence.
            String summary = summarize(prompt, conversationId, fullTask, SUMMARIZE_DONE, usage);
            return result(conversationId, summary, true, hops, startMs, usage);
        }
        return result(conversationId, text, true, hops, startMs, usage);
    }

    /**
     * Builds the result record and logs the response and token usage (the request's deliverable).
     */
    private SearchAgentResult result(
            String conversationId,
            String report,
            boolean complete,
            int hops,
            long startMs,
            TokenUsage usage) {
        long durationMs = System.currentTimeMillis() - startMs;
        log.info(
                "[{}] search sub-agent done: complete={}, hops={}, {} ms, "
                        + "tokens(prompt={}, completion={}, total={}), report='{}'",
                conversationId,
                complete,
                hops,
                durationMs,
                usage.prompt,
                usage.completion,
                usage.total,
                truncate(report, 1000));
        return new SearchAgentResult(report, complete, hops, durationMs);
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
            TokenUsage usage) {
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
            usage.add(summary);
            final String text = summary.getResult().getOutput().getText();
            return (text == null || text.isBlank()) ? "Поиск не дал результатов." : text;
        } catch (Exception e) {
            log.error("[{}] search sub-agent summarization failed", conversationId, e);
            return "Поиск прерван: " + rootMessage(e);
        }
    }

    private static String buildTask(
            String task, @Nullable String scope, @Nullable String pathGlob) {
        final StringBuilder sb = new StringBuilder("ЗАДАЧА ПОИСКА:\n").append(task);
        if (scope != null && !scope.isBlank()) {
            sb.append("\n\nОбласть: ").append(scope.trim());
        }
        if (pathGlob != null && !pathGlob.isBlank()) {
            sb.append("\nОграничение путей (glob): ").append(pathGlob.trim());
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

    /** Running token total across every model call in one sub-agent run (loop + summarization). */
    private static final class TokenUsage {
        private long prompt;
        private long completion;
        private long total;

        private void add(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return;
            }
            Usage u = response.getMetadata().getUsage();
            if (u == null) {
                return;
            }
            prompt += nz(u.getPromptTokens());
            completion += nz(u.getCompletionTokens());
            total += nz(u.getTotalTokens());
        }

        private static long nz(Integer value) {
            return value == null ? 0L : value;
        }
    }
}
