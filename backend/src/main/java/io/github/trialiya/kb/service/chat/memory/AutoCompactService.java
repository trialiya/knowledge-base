package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_APPLIED;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.memory.SummarizeWindow.MessageMix;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Последний рубеж перед пределом контекста: чат сжимает себя сам, пока прогон ещё может состояться.
 *
 * <p>Ждать здесь нечего и некого. Фоновая суммаризация ({@link SummarizeService}) сжимает только
 * начало окна и написанное ещё и паркует до подходящего момента ({@link PendingSummaryService}), а
 * два-три прогона с инструментами набивают окно быстрее, чем она успевает хоть что-то записать.
 * Прогон, начатый на переполненном окне, не отвечает плохо — он не начинается вовсе: провайдер
 * отвергает запрос по длине, и чат становится непригоден без единого способа это починить изнутри.
 *
 * <p>Поэтому раунд идёт синхронно, в начале прогона: вопрос уже сохранён, промпт ещё не собран, и
 * сжатое окно застаёт тот самый запрос, ради которого сжатие и затевалось. Цену этого — десятки
 * секунд ожидания перед ответом — платит один прогон из очень многих, и платит он её вместо отказа.
 *
 * <p>Сам раунд общий с {@code /compact} ({@link CompactService#compact}) — то же окно, тот же
 * запрос, та же запись. Отличий два, и оба в {@link CompactService.CompactTarget}: вид плашки
 * ({@link CompactMeta.Kind#AUTO_COMPACT}) и граница разметки — здесь это последний ряд окна, потому
 * что вопрос, ради которого чат сжался, обязан остаться живым.
 */
@Slf4j
@Service
public class AutoCompactService {

    private final ChatHistoryService chatHistory;
    private final CompactService compactService;
    private final PendingSummaryService pendingSummaries;
    private final SummaryWriter summaryWriter;
    private final ChatEventService events;
    private final SummarizeProperties properties;

    public AutoCompactService(
            ChatHistoryService chatHistory,
            CompactService compactService,
            PendingSummaryService pendingSummaries,
            SummaryWriter summaryWriter,
            ChatEventService events,
            SummarizeProperties properties) {
        this.chatHistory = chatHistory;
        this.compactService = compactService;
        this.pendingSummaries = pendingSummaries;
        this.summaryWriter = summaryWriter;
        this.events = events;
        this.properties = properties;
    }

    /**
     * Сжимает контекст чата, если тот дорос до доли окна модели. Не бросает: сжатие — страховка
     * прогона, а не его часть, и упавшая страховка не повод не отвечать. Прогон тогда уедет на
     * несжатом окне и либо ответит, либо получит отказ провайдера — то есть ровно то, что было бы
     * без этого класса.
     *
     * @param questionPosition позиция вопроса, ради которого идёт прогон: окно берётся ДО неё, и
     *     живым после сжатия остаётся именно он
     * @param modelContextTokens окно модели прогона; {@code null} — окно этой модели не названо в
     *     конфигурации, и порога у чата нет вовсе (см. {@code
     *     ChatModelProperties.ModelOption#contextTokens})
     * @param spent куда сложить замер раунда, который до модели дошёл, а сводки не дал — накопитель
     *     идущего прогона: своего ряда у такого раунда нет, и мимо прогона его деньги не попали бы
     *     в статистику чата ни одним числом
     */
    public void compactIfOversized(
            String conversationId,
            String runId,
            long questionPosition,
            @Nullable Integer modelContextTokens,
            CompactService.CompactOptions options,
            Consumer<TokenUsage> spent) {
        if (modelContextTokens == null) {
            return;
        }
        final long limit = Math.round(modelContextTokens * properties.autoCompactAtRatio());
        try {
            // Замок общий с фоновой суммаризацией и с /compact и охватывает чтение окна: без него
            // фоновый раунд, стартовавший по прошлому RUN_DONE, успел бы записать сводку по тому же
            // куску, который этот раунд уже заменяет своей.
            summaryWriter.inConversation(
                    conversationId,
                    () -> compact(conversationId, runId, questionPosition, limit, options, spent));
        } catch (Exception e) {
            log.error("[{}] Auto-compaction failed: {}", conversationId, e.getMessage(), e);
        }
    }

    private void compact(
            String conversationId,
            String runId,
            long questionPosition,
            long limit,
            CompactService.CompactOptions options,
            Consumer<TokenUsage> spent) {
        final List<PromptRow> rows = chatHistory.promptRowsBefore(conversationId, questionPosition);
        if (CompactService.nothingToCompact(rows)) {
            return;
        }
        // Вес всего живого окна, а не сжимаемого среза: порог здесь про то, сколько уедет
        // провайдеру со следующим запросом, — а уедет оно целиком.
        final SummarizeWindow.Weight weight = new SummarizeWindow(rows, properties).windowTokens();
        if (weight.tokens() < limit) {
            log.debug(
                    "[{}] No auto-compaction — live window {} is under the {} token limit",
                    conversationId,
                    weight,
                    limit);
            return;
        }
        final ChatMessageEntity lastRow = rows.getLast().entity();
        log.info(
                "[{}] Auto-compacting before the answer — live window {} reached the {} token"
                        + " limit: {}",
                conversationId,
                weight,
                limit,
                MessageMix.of(rows));
        final CompactPayload payload =
                compactService.compact(
                        conversationId,
                        rows,
                        new CompactService.CompactTarget(
                                CompactMeta.Kind.AUTO_COMPACT,
                                // Граница — последний сжатый ряд, а не вопрос: он уже сохранён и
                                // размеченный диапазон, дотянувшись до него, оставил бы прогон без
                                // вопроса. Время рядов оттуда же: по времени конца раунда плашка
                                // встала бы в ленте ПОД вопросом, который она не сжимала.
                                lastRow.getPosition(),
                                lastRow.getCreatedAt(),
                                (call, usage) -> {
                                    spent.accept(call);
                                    return null;
                                }),
                        null,
                        options);
        // Отложенная фоновая сводка описывает начало истории, которого в промпте больше нет.
        pendingSummaries.discard(conversationId);
        // runId — прогона, который за раунд заплатил; своего прогона у сжатия нет, и занятость чата
        // вкладкам уже показывает сам прогон.
        events.publish(conversationId, COMPACT_APPLIED, runId, null, payload);
    }
}
