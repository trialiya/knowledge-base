package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.orDefault;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.search.SearchAgentResult;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Single tool that delegates to the search {@link SearchAgentService sub-agent}. The main chat
 * model hands it a high-level task; the sub-agent runs its own multi-step grep/read loop and
 * returns a compact, citation-bearing report.
 *
 * <p>This tool is intentionally NOT part of the sub-agent's own tool set (see {@code
 * kb.search.subagent.allowed-tools}) — that is the recursion guard.
 *
 * <p>The sub-agent never sees the conversation that called it, so everything it needs to know about
 * it travels in the arguments. {@code context} is that channel and the only one: what the chat has
 * already established, and what it wants from the report beyond the task itself.
 *
 * <p>Like the git read tools, it takes an optional {@code project}: the whole sub-run then reads
 * that repository, and the report echoes which one it was. The sub-agent's own prompt says nothing
 * about projects, so it never wanders off on its own — the repository is decided here, once.
 */
@Slf4j
@AllArgsConstructor
public class SearchAgentFunction {

    private final SearchAgentService searchAgent;

    @Tool(
            description =
                    """
                    Multi-step search across code and knowledge base: grep → read → analyze → report. \
                    Pass a detailed task (what + why, suspected keywords, class/method names, scope). \
                    The sub-agent iteratively searches, outlines structure, and reads files, \
                    returning a compact report with path:line citations. Use for broad/ambiguous queries \
                    ("where and how is authorization implemented?") when a single grepContent is insufficient. \
                    For simple exact matches, use grepContent directly instead.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public SearchAgentResult searchCodebase(
            ToolContext toolContext,
            @ToolParam(
                            description =
                                    "Detailed search task in natural language: what to find and why. "
                                            + "Be specific with keywords, class/method names, or scope for best results.")
                    String task,
            @ToolParam(
                            description =
                                    "Optional: what the conversation already established (findings,"
                                            + " paths and names ruled out) and any extra requirement"
                                            + " on the report. The sub-agent sees nothing but this"
                                            + " call — state facts, not \"the above\".",
                            required = false)
                    @Nullable String context,
            @ToolParam(
                            description =
                                    "Search scope: \"code\" | \"docs\" | \"all\" (default all).",
                            required = false)
                    @Nullable String scope,
            @ToolParam(
                            description =
                                    "Glob pattern to restrict code search (e.g., \"backend/**/*.java\"). "
                                            + "Null for no restriction.",
                            required = false)
                    @Nullable String pathGlob,
            @ToolParam(
                            description =
                                    "Optional: another project (repository id) to read instead of"
                                            + " the chat's active one; the response's"
                                            + " \"project\" field says which one answered.",
                            required = false)
                    @Nullable String project) {
        requireText(task, "task");
        final String effectiveScope = orDefault(scope, "all");
        final String conversationId = conversationId(toolContext);
        log.info(
                "[{}] searchCodebase called: scope={} pathGlob={} project={} contextChars={}",
                conversationId,
                effectiveScope,
                pathGlob,
                project,
                context == null ? 0 : context.length());
        return searchAgent.run(task, context, effectiveScope, pathGlob, toolContext, project);
    }
}
