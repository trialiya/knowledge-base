package io.github.trialiya.kb.diag;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * ДИАГНОСТИКА (временная). Самый внутренний advisor ({@link Ordered#LOWEST_PRECEDENCE} — ближе
 * всего к модели): логирует каждый {@code ChatResponse}, который модель отдаёт в поток, ДО того как
 * его увидит подписчик в {@code ChatRunService}.
 *
 * <p>Назначение — ответить на вопрос «можно ли поймать формирование вызова инструмента изменением
 * конфигурации {@code ChatConfig} (через advisor), не трогая подписчика». Сравниваем вид потока на
 * уровне advisor с видом на уровне подписчика (обе точки логирует {@link StreamDiagnostics}). Если
 * на уровне advisor tool calls тоже не видны — значит, в Spring AI 1.1.x их в принципе нельзя
 * перехватить через advisor API (внутренний цикл исполнения инструментов и {@code
 * MessageAggregator} находятся глубже), и решение лежит в плоскости либо ручного цикла, либо
 * апгрейда до 2.0.
 */
public class StreamDiagnosticsAdvisor implements StreamAdvisor {

    private final StreamDiagnostics diagnostics;

    public StreamDiagnosticsAdvisor(StreamDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public String getName() {
        return "streamDiagnosticsAdvisor";
    }

    @Override
    public int getOrder() {
        // Наименьший приоритет ⇒ самый внутренний advisor: видим сырой поток модели до пост-
        // обработки другими advisor'ами (в т.ч. до MessageChatMemoryAdvisor на обратном пути).
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        final String conversationId =
                String.valueOf(request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "?"));
        final AtomicInteger seq = new AtomicInteger();
        return chain.nextStream(request)
                .doOnNext(
                        response ->
                                diagnostics.advisorChunk(
                                        conversationId,
                                        seq.incrementAndGet(),
                                        response.chatResponse()));
    }
}
