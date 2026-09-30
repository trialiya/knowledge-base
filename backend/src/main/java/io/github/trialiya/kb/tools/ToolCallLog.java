package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.Compact.oneLine;

import io.github.trialiya.kb.utils.ChatUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Журнал вызовов инструментов — одна запись на вызов, пишет её {@link RecordingToolCallback}.
 * Уровень решает, сколько в ней подробностей: при включённом DEBUG этого логгера — аргументы и
 * ответ целиком, иначе в INFO только имя инструмента, усечённые аргументы и начало ответа. Ответ
 * инструмента бывает содержимым целого файла или документа, поэтому в INFO он целиком не попадает
 * никогда.
 *
 * <p>Логгер отдельный, чтобы подробности включались для вызовов инструментов, не затрагивая
 * остальной пакет: {@code logging.level.io.github.trialiya.kb.tools.ToolCallLog: DEBUG}.
 */
@Slf4j
final class ToolCallLog {

    /**
     * Предел строки аргументов в кратком виде; сами значения уже усечены {@code parseToolInput}.
     */
    static final int ARGS_MAX = 300;

    /** Предел начала ответа в кратком виде. */
    static final int RESULT_MAX = 200;

    private ToolCallLog() {}

    static void ok(
            @Nullable ToolContext context,
            String tool,
            @Nullable String input,
            Map<Object, Object> args,
            @Nullable String result,
            long millis) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "[{}] tool {} ok in {} ms\n  args: {}\n  result ({} chars): {}",
                    conversationId(context),
                    tool,
                    millis,
                    input,
                    length(result),
                    result);
        } else if (log.isInfoEnabled()) {
            log.info(
                    "[{}] tool {}({}) ok in {} ms -> {} chars: {}",
                    conversationId(context),
                    tool,
                    oneLine(args.toString(), ARGS_MAX),
                    millis,
                    length(result),
                    oneLine(result, RESULT_MAX));
        }
    }

    static void failed(
            @Nullable ToolContext context,
            String tool,
            @Nullable String input,
            Map<Object, Object> args,
            Exception error,
            long millis) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] tool {} failed in {} ms\n  args: {}", conversationId(context), tool, millis, input, error);
        } else if (log.isInfoEnabled()) {
            log.info(
                    "[{}] tool {}({}) failed in {} ms: {}",
                    conversationId(context),
                    tool,
                    oneLine(args.toString(), ARGS_MAX),
                    millis,
                    oneLine(error.toString(), RESULT_MAX));
        }
    }

    private static int length(@Nullable String text) {
        return text == null ? 0 : text.length();
    }

    private static String conversationId(@Nullable ToolContext context) {
        return context == null ? "-" : ChatUtils.conversationId(context);
    }
}
