package io.github.trialiya.kb.advisor;

import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Доставляет сообщения, отправленные во время прогона, внутрь его tool-цикла (см. {@link
 * PendingMessageService#flushMidTurn}). Стоит СТРОГО после advisor-а памяти и до самых внутренних
 * ({@code LOWEST_PRECEDENCE}): его {@code before}-сторона исполняется после {@code
 * MessageChatMemoryAdvisor.before()}, который к этому моменту уже записал TOOL-ответы итерации в
 * историю. Ровно поэтому вставка здесь протокольно валидна: хвост чата — {@code tool}, а не
 * оборванный {@code assistant.tool_calls}, и USER-ряд с позицией max+1 не влезает внутрь пары (и не
 * провоцирует {@code repairDanglingToolCalls} на синтетический ответ).
 *
 * <p>Доставленные ряды дописываются и в инструкции ТЕКУЩЕЙ итерации: окно памяти собрано до их
 * записи, и иначе модель увидела бы их только следующей итерацией — которой может не быть, если эта
 * оказалась финальной. На следующей итерации ряды вернутся из памяти как {@code IMessage} и в
 * повторную запись не пойдут ({@code ChatHistoryService.append} их отфильтрует) — дублей нет ни в
 * промпте, ни в истории.
 *
 * <p>На каждой итерации каждого прогона это один индексированный SELECT по пустой почти всегда
 * таблице — на фоне вызова модели цена невидима.
 */
public class InterjectionAdvisor implements StreamAdvisor {

    private final PendingMessageService pendingMessages;

    public InterjectionAdvisor(PendingMessageService pendingMessages) {
        this.pendingMessages = pendingMessages;
    }

    @Override
    public String getName() {
        return "interjectionAdvisor";
    }

    @Override
    public int getOrder() {
        // После памяти (DEFAULT_ORDER + 100), до самых внутренних (LOWEST_PRECEDENCE).
        return ToolCallingAdvisor.DEFAULT_ORDER + 150;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        // fromCallable + boundedElastic: flushMidTurn ходит в БД, а вызвать adviseStream могут и на
        // потоке, где блокироваться нельзя. Память решает то же самое publishOn-ом в BaseAdvisor.
        return Mono.fromCallable(() -> withPendingMessages(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(chain::nextStream);
    }

    private ChatClientRequest withPendingMessages(ChatClientRequest request) {
        final Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            // Путь без памяти (суб-агенты, тесты): очереди у такого вызова нет по построению.
            return request;
        }
        final Object runId = request.context().get(AdvisorParams.RUN_ID_PARAM);
        final List<Message> injected =
                pendingMessages.flushMidTurn(
                        String.valueOf(conversationId),
                        runId == null ? null : String.valueOf(runId));
        if (injected.isEmpty()) {
            return request;
        }
        final List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        messages.addAll(injected);
        return request.mutate()
                .prompt(request.prompt().mutate().messages(messages).build())
                .build();
    }
}
