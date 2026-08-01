package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.script.ScriptRunner;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.RunCancellation;
import lombok.AccessLevel;
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
 * Whether the script may also write is decided by {@code ScriptEditPolicy} — and, for the search
 * sub-agent's copy, refused outright (see {@link #readOnly}).
 *
 * <p><b>The description here is deliberately short.</b> The full handbook — the {@code kb}
 * reference, the budgets, what to do about each error, and for a weak model the worked examples too
 * — is injected into the system prompt by {@code ScriptGuideService} whenever this tool is present.
 * Keeping it there rather than in the annotation means one text to maintain, one that can grow to
 * the length a weak model actually needs without bloating every tool listing, and one whose
 * tutorial half is dropped per model rather than per deployment (see {@code
 * ChatModelProperties.ModelOption#weak}, {@code ScriptGuideService}).
 */
@Slf4j
// Private: which of the two factories was used is the whole difference between the chat's copy of
// the tool and the sub-agent's, and a bare boolean at the call site would not say which is which.
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScriptFunction {

    private final ScriptRunner scriptRunner;

    /**
     * Withholds {@code kb.edit}/{@code kb.create} whatever {@code ScriptEditPolicy} says. Set for
     * the search sub-agent's copy of the tool, whose whole contract is that it only reads.
     */
    private final boolean forceReadOnly;

    /** The chat model's copy: writes follow {@code ScriptEditPolicy}. */
    public static ScriptFunction forChat(ScriptRunner scriptRunner) {
        return new ScriptFunction(scriptRunner, false);
    }

    /** The search sub-agent's copy: never writes. */
    public static ScriptFunction readOnly(ScriptRunner scriptRunner) {
        return new ScriptFunction(scriptRunner, true);
    }

    @Tool(
            description =
                    """
                    Выполняет JS-скрипт, который сам обходит репозиторий: доступен только объект kb \
                    (kb.files, kb.read, kb.grep, kb.outline, kb.searchDocs, kb.log; при включённой \
                    правке ещё kb.edit и kb.create) — файловых, сетевых и Java-API нет. Результат \
                    возвращай через return. Бери, когда нужно пройтись по многим файлам и что-то \
                    сопоставить/посчитать/изменить; для одного точного поиска, чтения или правки \
                    используй grepContent / getFileContent / editFile. Справочник по kb и лимиты — \
                    в системном промпте, раздел «Скрипты (runScript)». \
                    Ответ: value (то, что вернул скрипт), log, stats, filesRead, edits (диффы \
                    изменённых файлов), error (kind=SYNTAX|RUNTIME|TIMEOUT|BUDGET с подсказкой, \
                    как починить).
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
                scriptRunner.run(
                        script, timeoutSeconds, RunCancellation.from(context), forceReadOnly);
        log.info("runScript finished: {}", result.getFormattedResponse());
        return result;
    }
}
