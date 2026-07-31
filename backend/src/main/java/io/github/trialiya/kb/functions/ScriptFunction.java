package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.script.ScriptRunner;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.RunCancellation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The {@code runScript} tool: one JS script that walks the repository itself, instead of a dozen
 * round-trips of grep → outline → read.
 *
 * <p>Registered only when {@code kb.script.enabled=true} (see {@code ChatConfig#scriptFunction}).
 * Read-only: the script's {@code kb} object exposes listing, reading and searching, and nothing
 * that writes.
 *
 * <p><b>The description here is deliberately short.</b> The full handbook — the {@code kb}
 * reference, worked examples, the budgets, what to do about each error — is injected into the
 * system prompt by {@code ScriptGuideService} whenever this tool is present. Keeping it there
 * rather than in the annotation means one text to maintain, and one that can grow to the length a
 * weak model actually needs without bloating every tool listing.
 */
@Slf4j
@AllArgsConstructor
public class ScriptFunction {

    private final ScriptRunner scriptRunner;

    @Tool(
            description =
                    """
                    Выполняет JS-скрипт, который сам обходит репозиторий: доступен только объект kb \
                    (kb.files, kb.read, kb.grep, kb.outline, kb.searchDocs, kb.log) — файловых, \
                    сетевых и Java-API нет. Результат возвращай через return. Бери, когда нужно \
                    пройтись по многим файлам и что-то сопоставить/посчитать; для одного точного \
                    поиска или чтения используй grepContent / getFileContent. Полная инструкция по \
                    kb, примеры и лимиты — в системном промпте, раздел «Скрипты (runScript)». \
                    Ответ: value (то, что вернул скрипт), log, stats, filesRead, error \
                    (kind=SYNTAX|RUNTIME|TIMEOUT|BUDGET с подсказкой, как починить).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public ScriptResult runScript(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Тело скрипта на JavaScript (ES2023). Выполняется как тело "
                                            + "функции — верхнеуровневый return разрешён и является "
                                            + "способом вернуть результат.")
                    String script,
            @ToolParam(
                            description =
                                    "Лимит времени в секундах. По умолчанию 10, максимум 30 "
                                            + "(значения сверх максимума молча урезаются).",
                            required = false)
                    @Nullable Integer timeoutSeconds) {
        log.info("runScript called: {} chars, timeoutSeconds={}", script.length(), timeoutSeconds);
        ScriptResult result =
                scriptRunner.run(script, timeoutSeconds, RunCancellation.from(context));
        log.info("runScript finished: {}", result.getFormattedResponse());
        return result;
    }
}
