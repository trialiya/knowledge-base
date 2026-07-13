package io.github.trialiya.kb.functions;

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
 */
@Slf4j
@AllArgsConstructor
public class SearchAgentFunction {

    private final SearchAgentService searchAgent;

    @Tool(
            description =
                    """
                    Глубокий многошаговый поиск по коду репозитория и базе знаний. \
                    На вход — развёрнутая ЗАДАЧА (что ищем и зачем; подозреваемые термины, \
                    имена классов/методов, область). Сабагент сам итеративно вызывает grep, \
                    обзор структуры и чтение файлов и возвращает СЖАТЫЙ отчёт с цитатами path:line. \
                    Используй для широких/неоднозначных запросов ("где и как реализована \
                    авторизация?"), когда одиночного grepContent недостаточно. \
                    Для простого точного совпадения используй grepContent напрямую.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public SearchAgentResult searchCodebase(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Подробная постановка задачи на естественном языке: что искать и"
                                            + " зачем. Чем конкретнее (термины, имена классов/методов,"
                                            + " область), тем лучше результат.")
                    String task,
            @ToolParam(
                            description =
                                    "Область поиска: \"code\" | \"docs\" | \"all\" (по умолчанию all).",
                            required = false)
                    @Nullable String scope,
            @ToolParam(
                            description =
                                    "Glob для ограничения путей в коде, например \"backend/**/*.java\"."
                                            + " null — без ограничения.",
                            required = false)
                    @Nullable String pathGlob) {
        final String conversationId = conversationId(context);
        log.info(
                "[{}] searchCodebase called: scope={} pathGlob={}",
                conversationId,
                scope,
                pathGlob);
        return searchAgent.run(task, scope, pathGlob, context);
    }
}
