package io.github.trialiya.kb.diag;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ДИАГНОСТИКА (временная, ветка claude/clever-ramanujan-oucoiw). Логирует точную форму каждого
 * чанка стрима ответа в ДВУХ точках наблюдения, чтобы эмпирически выяснить, в какой момент (и
 * доходит ли вообще) информация о вызове инструмента видна приложению при стриминге в Spring AI
 * 1.1.x.
 *
 * <p><b>Контекст проблемы.</b> Ранний сигнал {@code TOOL_PREPARING} (коммит 944e1ac) опирается на
 * {@code response.getResult().getOutput().getToolCalls()} в стримовом потоке {@code
 * ChatRunService.onNext}. В Spring AI 1.1.x {@code MessageAggregator} вырезает {@code toolCalls} из
 * стримового {@link AssistantMessage} (spring-projects/spring-ai#3366, #5167), поэтому проверка
 * почти наверняка всегда ложна, а при включённом внутреннем исполнении инструментов чанк с {@code
 * finishReason=tool_calls} и вовсе может не доходить до подписчика. Эти логи и должны показать, что
 * именно происходит.
 *
 * <p><b>Что измеряем.</b>
 *
 * <ul>
 *   <li><b>subscriber</b> — ровно то, что доходит до {@code ChatRunService.onNext} (точка, на
 *       которой работает текущий детектор {@code hasToolCallDelta}).
 *   <li><b>advisor</b> — самый внутренний advisor (ближе всего к модели): отвечает на вопрос «можно
 *       ли поймать формирование вызова изменением конфигурации ChatConfig через advisor».
 *   <li><b>TOOL STARTED</b> — момент реального запуска инструмента ({@code RecordingToolCallback}
 *       уже вызван): по таймстампам логов видно «тихую паузу» между последним текстовым чанком и
 *       стартом инструмента.
 * </ul>
 *
 * <p>Включается свойством {@code kb.diag.stream-tool-calls=true} (env {@code
 * KB_DIAG_STREAM_TOOL_CALLS}). По умолчанию выключена. «Интересные» чанки (пустой текст, есть
 * {@code finishReason}, есть tool calls) логируются на уровне INFO, остальные — на DEBUG логгера
 * {@code io.github.trialiya.kb.diag.StreamDiagnostics}.
 */
@Slf4j
@Component
public class StreamDiagnostics {

    private final boolean enabled;

    public StreamDiagnostics(@Value("${kb.diag.stream-tool-calls:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.info(
                    "[stream-diag] ENABLED — per-chunk stream shape logged at INFO "
                            + "(subscriber + advisor + tool-start); disable with "
                            + "kb.diag.stream-tool-calls=false");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Точка 1 — подписчик: ровно то, что доходит до {@code ChatRunService.onNext}. */
    public void subscriberChunk(
            String conversationId,
            String runId,
            int seq,
            ChatResponse response,
            String text,
            String finishReason) {
        log("subscriber", conversationId, runId, seq, response, text, finishReason);
    }

    /** Точка 2 — самый внутренний advisor (ближе всего к модели). */
    public void advisorChunk(String conversationId, int seq, ChatResponse response) {
        log(
                "advisor",
                conversationId,
                "-",
                seq,
                response,
                textOf(response),
                finishReasonOf(response));
    }

    /** Реальный старт инструмента — опорная точка для замера «тихой паузы». */
    public void toolStarted(String conversationId, String name) {
        if (enabled) {
            log.info("[stream-diag][{}] >>> TOOL STARTED name={}", conversationId, name);
        }
    }

    private void log(
            String where,
            String conversationId,
            String runId,
            int seq,
            ChatResponse response,
            String text,
            String finishReason) {
        if (!enabled) {
            return;
        }
        final int textLen = text == null ? -1 : text.length();
        final boolean emptyText = text == null || text.isEmpty();
        final boolean hasToolCalls = response != null && response.hasToolCalls();
        final List<AssistantMessage.ToolCall> calls = toolCalls(response);
        final boolean interesting =
                emptyText || finishReason != null || hasToolCalls || !calls.isEmpty();
        final String msg =
                String.format(
                        "[stream-diag][%s][run=%s][%s #%d] textLen=%d empty=%b finishReason=%s"
                                + " hasToolCalls=%b outToolCalls=%s",
                        conversationId,
                        runId,
                        where,
                        seq,
                        textLen,
                        emptyText,
                        finishReason,
                        hasToolCalls,
                        describe(calls));
        if (interesting) {
            log.info(msg);
        } else {
            log.debug(msg);
        }
    }

    private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AssistantMessage::getToolCalls)
                .orElse(List.of());
    }

    private static String describe(List<AssistantMessage.ToolCall> calls) {
        if (calls.isEmpty()) {
            return "[]";
        }
        return calls.stream()
                .map(
                        c ->
                                c.name()
                                        + "{id="
                                        + c.id()
                                        + ",argsLen="
                                        + (c.arguments() == null ? -1 : c.arguments().length())
                                        + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String textOf(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElse("");
    }

    private static String finishReasonOf(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .orElse(null);
    }
}
