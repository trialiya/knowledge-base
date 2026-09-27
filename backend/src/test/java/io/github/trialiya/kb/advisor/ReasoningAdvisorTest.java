package io.github.trialiya.kb.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Рассуждение обращения обязано дожить до записи ответа целиком. Проверяется вместе с настоящим
 * агрегатором Spring AI — тем, по которому advisor памяти собирает ответ для записи: ради его
 * поведения (метаданные чанков перезаписываются, а не склеиваются) advisor и существует, и замена
 * агрегатора заглушкой проверяла бы наше представление о нём, а не его самого.
 */
class ReasoningAdvisorTest {

    private final StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
    private final ReasoningAdvisor advisor = new ReasoningAdvisor();

    /** Так чанк отдаёт {@code OpenAiChatModel}: фрагмент рассуждения, у текста — пустая строка. */
    private static ChatClientResponse chunk(String reasoning, String text) {
        return ChatClientResponse.builder()
                .chatResponse(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content(text)
                                                        .properties(
                                                                Map.of(
                                                                        ReasoningAdvisor
                                                                                .REASONING_CONTENT,
                                                                        reasoning))
                                                        .build()))))
                .build();
    }

    /** Ответ, каким его увидит advisor памяти: чанки через advisor и агрегатор Spring AI. */
    private AssistantMessage aggregated(ChatClientResponse... chunks) {
        when(chain.nextStream(any())).thenReturn(Flux.just(chunks));
        final AtomicReference<ChatClientResponse> saved = new AtomicReference<>();
        new ChatClientMessageAggregator()
                .aggregateChatClientResponse(
                        advisor.adviseStream(
                                ChatClientRequest.builder().prompt(new Prompt("q")).build(), chain),
                        saved::set)
                .blockLast();
        return saved.get().chatResponse().getResult().getOutput();
    }

    @Test
    void theSavedAnswerCarriesTheWholeReasoningOfTheCall() {
        final AssistantMessage answer =
                aggregated(
                        chunk("Сначала ", ""),
                        chunk("история файла.", ""),
                        chunk("", "Вот "),
                        chunk("", "ответ."));

        assertThat(answer.getText()).isEqualTo("Вот ответ.");
        assertThat(ReasoningAdvisor.reasoningOf(answer)).isEqualTo("Сначала история файла.");
    }

    /**
     * Без advisor-а агрегатор оставил бы метаданные последнего чанка — пустую строку. Если Spring
     * AI однажды начнёт склеивать рассуждение сам, этот тест скажет, что advisor можно убрать.
     */
    @Test
    void theAggregatorAloneLosesTheReasoning() {
        final AtomicReference<ChatClientResponse> saved = new AtomicReference<>();
        new ChatClientMessageAggregator()
                .aggregateChatClientResponse(
                        Flux.just(chunk("Сначала история файла.", ""), chunk("", "Ответ.")),
                        saved::set)
                .blockLast();

        assertThat(
                        saved.get()
                                .chatResponse()
                                .getResult()
                                .getOutput()
                                .getMetadata()
                                .get(ReasoningAdvisor.REASONING_CONTENT))
                .isEqualTo("");
    }

    @Test
    void aCallWithoutReasoningStaysUntouched() {
        final AssistantMessage answer = aggregated(chunk("", "Ответ "), chunk("", "без раздумий."));

        assertThat(ReasoningAdvisor.reasoningOf(answer)).isNull();
    }

    /** Каждое обращение — свой буфер: рассуждение прошлой итерации в следующую не протекает. */
    @Test
    void eachCallStartsWithAnEmptyReasoning() {
        aggregated(chunk("Первое обращение.", ""), chunk("", "вызов"));

        assertThat(ReasoningAdvisor.reasoningOf(aggregated(chunk("Второе.", ""), chunk("", "ok"))))
                .isEqualTo("Второе.");
    }
}
