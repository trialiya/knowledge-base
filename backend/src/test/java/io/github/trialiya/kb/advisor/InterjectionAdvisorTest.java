package io.github.trialiya.kb.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Advisor — единственная точка, где очередь чата попадает внутрь идущего прогона: доставленные ряды
 * обязаны доехать и в инструкции ТЕКУЩЕЙ итерации (окно памяти собрано до их записи), а итерация
 * без очереди — пройти сквозь advisor нетронутой.
 */
class InterjectionAdvisorTest {

    private final PendingMessageService pendingMessages = mock(PendingMessageService.class);
    private final StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

    private final InterjectionAdvisor advisor = new InterjectionAdvisor(pendingMessages);

    @Test
    void deliveredMessagesJoinTheCurrentIterationsPrompt() {
        final Message interjection = new UserMessage("и добавь тесты");
        when(pendingMessages.flushMidTurn("conv-1", "run-1")).thenReturn(List.of(interjection));
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        final ChatClientRequest request =
                request(List.of(new SystemMessage("sys"), toolResponse()), "conv-1", "run-1");

        advisor.adviseStream(request, chain).blockLast();

        final ArgumentCaptor<ChatClientRequest> forwarded =
                ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(forwarded.capture());
        final List<Message> instructions = forwarded.getValue().prompt().getInstructions();
        assertThat(instructions).hasSize(3);
        // В конец, после TOOL-ответов: для модели сообщение пришло после результатов инструментов —
        // ровно так же ряд лежит и в истории.
        assertThat(instructions.getLast()).isSameAs(interjection);
    }

    @Test
    void anEmptyQueueLeavesTheRequestUntouched() {
        when(pendingMessages.flushMidTurn(anyString(), anyString())).thenReturn(List.of());
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        final ChatClientRequest request =
                request(List.of(new SystemMessage("sys")), "conv-1", "run-1");

        advisor.adviseStream(request, chain).blockLast();

        final ArgumentCaptor<ChatClientRequest> forwarded =
                ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(forwarded.capture());
        assertThat(forwarded.getValue()).isSameAs(request);
    }

    /** Пути без памяти (суб-агенты, тесты) очереди не имеют — advisor их не трогает вовсе. */
    @Test
    void aRequestWithoutAConversationBypassesTheQueue() {
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        final ChatClientRequest request =
                new ChatClientRequest(new Prompt(List.of(new SystemMessage("sys"))), Map.of());

        advisor.adviseStream(request, chain).blockLast();

        verifyNoInteractions(pendingMessages);
    }

    /** Между памятью (+100) и ToolPreparingAdvisor (MAX) — см. javadoc advisor'а, почему строго. */
    @Test
    void theAdvisorSitsBetweenMemoryAndToolPreparing() {
        assertThat(advisor.getOrder())
                .isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER + 100)
                .isLessThan(new ToolPreparingAdvisor(null).getOrder());
    }

    private static ChatClientRequest request(
            List<Message> instructions, String conversationId, String runId) {
        return new ChatClientRequest(
                new Prompt(instructions),
                Map.of(
                        ChatMemory.CONVERSATION_ID,
                        conversationId,
                        ToolPreparingAdvisor.RUN_ID_PARAM,
                        runId));
    }

    private static ToolResponseMessage toolResponse() {
        return ToolResponseMessage.builder()
                .responses(
                        List.of(
                                new ToolResponseMessage.ToolResponse(
                                        "call-1", "search", "результат")))
                .build();
    }
}
