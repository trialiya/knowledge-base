package io.github.trialiya.kb.service.chat.run;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.repository.ChatPendingMessageRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Процесс упал между приёмом сообщения и его доставкой. Пользователь уже получил «принято», поэтому
 * единственный неприемлемый исход — сообщение, которое так и не появилось в чате.
 */
class PendingMessageRecoveryTest {

    private final ChatPendingMessageRepository repository =
            mock(ChatPendingMessageRepository.class);
    private final PendingMessageService pendingMessages = mock(PendingMessageService.class);
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);

    private final PendingMessageRecovery recovery =
            new PendingMessageRecovery(repository, pendingMessages, chatHistory);

    /**
     * Ремонт хвоста — строго до доставки: прогон оборвался вместе с процессом, и записанный вопрос
     * навсегда спрятал бы оборванную пару от ремонта (модель отвечала бы 400 на каждый следующий
     * запрос этого чата).
     */
    @Test
    void everyLeftoverChatIsRepairedThenFlushed() {
        when(repository.conversationIds()).thenReturn(List.of("conv-1"));

        recovery.deliverLeftovers();

        final InOrder order = inOrder(chatHistory, pendingMessages);
        order.verify(chatHistory).repairDanglingToolCalls("conv-1");
        order.verify(pendingMessages).flushPlain("conv-1");
    }

    /** Один сорвавшийся чат не повод оставить остальные с потерянными сообщениями. */
    @Test
    void oneFailingChatDoesNotStopTheRest() {
        when(repository.conversationIds()).thenReturn(List.of("conv-1", "conv-2"));
        doThrow(new IllegalStateException("boom"))
                .when(chatHistory)
                .repairDanglingToolCalls("conv-1");

        recovery.deliverLeftovers();

        verify(pendingMessages).flushPlain("conv-2");
    }

    /** Пустая таблица — обычное состояние: ни одного чтения истории. */
    @Test
    void nothingLeftOverTouchesNothing() {
        when(repository.conversationIds()).thenReturn(List.of());

        recovery.deliverLeftovers();

        verify(chatHistory, org.mockito.Mockito.never()).repairDanglingToolCalls(anyString());
    }
}
