package io.github.trialiya.kb.advisor;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.TOOL_PREPARING;

import io.github.trialiya.kb.service.ChatEventService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Самый внутренний advisor стримингового пути ({@link Ordered#LOWEST_PRECEDENCE} — ближе всего к
 * модели). Находится внутри цикла {@link
 * org.springframework.ai.chat.client.advisor.ToolCallingAdvisor}: вызывается на каждой итерации и
 * видит сырой поток модели до того, как инструмент будет запущен.
 *
 * <p>Когда модель заканчивает формировать вызов инструмента (последний чанк несёт {@code
 * finishReason=TOOL_CALLS} или {@code hasToolCalls()=true}), публикует в {@link ChatEventService}
 * событие {@link io.github.trialiya.kb.model.chat.dto.ChatEventType#TOOL_PREPARING}. Фронт
 * показывает «готовлю данные…» с задержкой — быстрые вызовы проходят незаметно.
 */
public class ToolPreparingAdvisor implements StreamAdvisor {

    /** Ключ для передачи runId через advisor-параметры запроса. */
    public static final String RUN_ID_PARAM = "RUN_ID";

    private final ChatEventService events;

    public ToolPreparingAdvisor(ChatEventService events) {
        this.events = events;
    }

    @Override
    public String getName() {
        return "toolPreparingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        final String conversationId =
                String.valueOf(request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "?"));
        final String runId = String.valueOf(request.context().getOrDefault(RUN_ID_PARAM, "?"));
        // Одна публикация на итерацию: модель отдаёт несколько tool-call дельт —
        // нам достаточно одного сигнала TOOL_PREPARING.
        final AtomicBoolean preparingSent = new AtomicBoolean(false);

        return chain.nextStream(request)
                .doOnNext(
                        response -> {
                            if (preparingSent.get()) {
                                return;
                            }
                            final ChatResponse cr = response.chatResponse();
                            if (cr == null) {
                                return;
                            }
                            final boolean hasToolCalls = cr.hasToolCalls();
                            final String finishReason = finishReasonOf(cr);
                            final boolean toolCallFinish =
                                    "TOOL_CALLS".equalsIgnoreCase(finishReason);
                            if ((hasToolCalls || toolCallFinish)
                                    && preparingSent.compareAndSet(false, true)) {
                                events.publish(conversationId, TOOL_PREPARING, runId, null, null);
                            }
                        });
    }

    private static String finishReasonOf(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .orElse(null);
    }
}
