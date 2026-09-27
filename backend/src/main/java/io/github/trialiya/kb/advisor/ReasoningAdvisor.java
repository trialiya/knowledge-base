package io.github.trialiya.kb.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * Собирает рассуждение модели ({@code reasoning_content}) за одно обращение к ней, чтобы оно легло
 * в историю вместе с ответом (см. {@code ChatHistoryService#append}).
 *
 * <p>Без него рассуждения не дожило бы до записи. {@code OpenAiChatModel} кладёт в метаданные
 * КАЖДОГО чанка стрима {@link #REASONING_CONTENT} — фрагмент этого чанка, у текстовых чанков пустую
 * строку, — а {@code MessageAggregator}, по которому advisor памяти собирает ответ для записи,
 * метаданные чанков не склеивает, а перезаписывает: в собранном ответе остаётся фрагмент последнего
 * чанка, то есть чаще всего ничего.
 *
 * <p>Поэтому фрагменты копятся здесь, в одном буфере на обращение, и буфер кладётся в метаданные
 * каждого чанка после первого фрагмента — своим ключом {@link #ACCUMULATED}. Агрегатор забирает его
 * вместе с последним чанком, а к записи ответа буфер уже полон: запись идёт по завершении
 * обращения, после последнего чанка. Сам буфер, а не строка-снимок: снимок на каждом чанке
 * копировал бы всё рассуждение заново, и долгое рассуждение стоило бы квадрат своей длины. Ключ
 * свой, а не {@link #REASONING_CONTENT}: тот читает сам Spring AI и ждёт там строку.
 *
 * <p>Стоит ВНУТРИ tool-цикла и внутри advisor-а памяти — ровно там, где у памяти идёт сборка ответа
 * одной итерации: снаружи чанки итераций уже перемешаны циклом.
 */
public class ReasoningAdvisor implements StreamAdvisor {

    /** Ключ метаданных сообщения, по которому Spring AI и читает, и отправляет рассуждение. */
    public static final String REASONING_CONTENT = "reasoningContent";

    /** Свой ключ накопленного рассуждения обращения (см. javadoc класса). */
    static final String ACCUMULATED = "kbReasoning";

    @Override
    public String getName() {
        return "reasoningAdvisor";
    }

    @Override
    public int getOrder() {
        // Внутри памяти (DEFAULT_ORDER + 100): её агрегатор должен увидеть уже переписанные чанки.
        return ToolCallingAdvisor.DEFAULT_ORDER + 200;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        // Буфер на обращение: adviseStream цикл зовёт заново на каждой итерации. Чанки одного
        // стрима приходят строго по очереди, поэтому синхронизация не нужна.
        final StringBuilder reasoning = new StringBuilder();
        return chain.nextStream(request).map(response -> carry(response, reasoning));
    }

    /**
     * Рассуждение, собранное за обращение, написавшее этот ответ; {@code null} — модель не
     * рассуждала или ответ пришёл не из стрима этого advisor-а.
     */
    public static @Nullable String reasoningOf(Message message) {
        final Object accumulated = message.getMetadata().get(ACCUMULATED);
        if (accumulated instanceof CharSequence text && !text.isEmpty()) {
            return text.toString();
        }
        return null;
    }

    private static ChatClientResponse carry(ChatClientResponse response, StringBuilder reasoning) {
        final ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults().size() != 1) {
            return response;
        }
        final Generation generation = chatResponse.getResults().getFirst();
        final AssistantMessage output = generation.getOutput();
        if (output.getMetadata().get(REASONING_CONTENT) instanceof String piece) {
            reasoning.append(piece);
        }
        if (reasoning.isEmpty()) {
            return response;
        }
        final Map<String, Object> metadata = new HashMap<>(output.getMetadata());
        metadata.put(ACCUMULATED, reasoning);
        final AssistantMessage carried =
                AssistantMessage.builder()
                        .content(output.getText())
                        .properties(metadata)
                        .toolCalls(output.getToolCalls())
                        .media(output.getMedia())
                        .build();
        return response.mutate()
                .chatResponse(
                        new ChatResponse(
                                List.of(new Generation(carried, generation.getMetadata())),
                                chatResponse.getMetadata()))
                .build();
    }
}
