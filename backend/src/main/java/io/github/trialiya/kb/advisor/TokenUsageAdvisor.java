package io.github.trialiya.kb.advisor;

import static io.github.trialiya.kb.advisor.ToolPreparingAdvisor.RUN_ID_PARAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_USAGE;

import io.github.trialiya.kb.model.chat.dto.TokenUsage;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.RunUsageRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Считает токены прогона и шлёт итог на фронт событием {@link
 * io.github.trialiya.kb.model.chat.dto.ChatEventType#RUN_USAGE}.
 *
 * <p>Внутренний advisor ({@link Ordered#LOWEST_PRECEDENCE}, как {@link ToolPreparingAdvisor}), и
 * это здесь не стиль, а единственное рабочее место. Снаружи цикла {@link
 * org.springframework.ai.chat.client.advisor.ToolCallingAdvisor} usage итераций не увидеть: его
 * несёт агрегированный чанк с {@code finishReason=TOOL_CALLS}, а цикл этот чанк из
 * downstream-потока отфильтровывает — до подписчика в {@code ChatRunService} доезжает в лучшем
 * случае замер последней итерации.
 *
 * <p>Отсюда же берутся границы обращений к модели, и берутся даром: {@link #adviseStream} цикл
 * вызывает заново на КАЖДОЙ итерации, поэтому состояние итерации — обычная локальная переменная, а
 * вынюхивать границу по {@code finishReason} или по старту инструмента не нужно.
 *
 * <p>Правила свёртки замеров внутри одного обращения (по-полевой максимум) — в {@link TokenUsage};
 * как из обращений собирается итог прогона — в {@link RunTokenUsage}.
 */
public class TokenUsageAdvisor implements StreamAdvisor {

    private final ChatEventService events;
    private final RunUsageRegistry usage;

    public TokenUsageAdvisor(ChatEventService events, RunUsageRegistry usage) {
        this.events = events;
        this.usage = usage;
    }

    @Override
    public String getName() {
        return "tokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        final Object runIdParam = request.context().get(RUN_ID_PARAM);
        // Считаем только заведённый ChatRunService прогон. Проверка не формальность: параметр
        // прогона в контексте ставит вызывающий, а карту чистит только владелец прогона, и
        // накопитель, заведённый здесь по чужому runId, удалить было бы некому.
        if (!(runIdParam instanceof String runId) || !usage.tracked(runId)) {
            return chain.nextStream(request);
        }
        final String conversationId =
                String.valueOf(request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "?"));
        final AtomicReference<TokenUsage> iteration = new AtomicReference<>(TokenUsage.EMPTY);

        return chain.nextStream(request)
                .doOnNext(response -> onResponse(conversationId, runId, iteration, response))
                // Итерация кончилась — её замер уходит в итог прогона. Именно doFinally: на
                // остановке прогона поток отменяют, а потраченное к этому моменту потрачено.
                .doFinally(signal -> usage.add(runId, iteration.getAndSet(TokenUsage.EMPTY)));
    }

    /**
     * Публикуем на каждое изменение замера, а не один раз в конце итерации: провайдер, который шлёт
     * usage нарастающим итогом, так даёт живой счётчик, а провайдер с единственным финальным чанком
     * — ровно одно событие. Пустые замеры не публикуются вовсе: у эндпоинта без поддержки usage в
     * стриме фронт не должен показать уверенный ноль.
     */
    private void onResponse(
            String conversationId,
            String runId,
            AtomicReference<TokenUsage> iteration,
            @Nullable ChatClientResponse response) {
        final TokenUsage measured = usageOf(response);
        if (measured.isEmpty()) {
            return;
        }
        final TokenUsage before = iteration.get();
        final TokenUsage merged = iteration.accumulateAndGet(measured, TokenUsage::merge);
        if (merged.equals(before)) {
            return;
        }
        // Пока не измерен ни один prompt, заполнения контекста нет, а плашку возглавляет именно
        // оно: провайдер, шлющий сначала выход и лишь в финальном чанке вход, до этого чанка даёт
        // замер, показать который нечем. Ждём — «неизвестно» это не ноль.
        final RunTokenUsage running = usage.snapshot(runId, merged);
        if (running.contextTokens() == 0) {
            return;
        }
        events.publish(conversationId, RUN_USAGE, runId, null, running);
    }

    /** Замер из ответа модели; {@link TokenUsage#EMPTY}, если провайдер его не прислал. */
    private static TokenUsage usageOf(@Nullable ChatClientResponse response) {
        final ChatResponse chatResponse = response == null ? null : response.chatResponse();
        final ChatResponseMetadata metadata =
                chatResponse == null ? null : chatResponse.getMetadata();
        final Usage measured = metadata == null ? null : metadata.getUsage();
        if (measured == null) {
            return TokenUsage.EMPTY;
        }
        return new TokenUsage(
                nz(measured.getPromptTokens()),
                nz(measured.getCompletionTokens()),
                nz(measured.getTotalTokens()),
                nz(measured.getCacheReadInputTokens()),
                nz(measured.getCacheWriteInputTokens()));
    }

    private static long nz(@Nullable Number value) {
        return value == null ? 0L : value.longValue();
    }
}
