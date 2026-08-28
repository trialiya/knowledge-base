package io.github.trialiya.kb.service.chat.run;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.repository.ChatPendingMessageRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
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

    private final ConversationSlots slots = mock(ConversationSlots.class);

    private final PendingMessageRecovery recovery =
            new PendingMessageRecovery(repository, pendingMessages, chatHistory, slots);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(slots.claim(anyString())).thenReturn("claim-1");
        when(pendingMessages.flushPlain(anyString()))
                .thenReturn(PendingMessageService.Flushed.NOTHING);
    }

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

    /**
     * Сервер принимает запросы раньше, чем публикуется {@code ApplicationReadyEvent}, поэтому чат
     * может быть уже занят начавшимся прогоном. Ремонт хвоста поверх живого tool-цикла дописал бы
     * второй TOOL-ответ на тот же {@code callId} — а начавшийся прогон и чинит, и опустошает
     * очередь сам.
     */
    @Test
    void aChatClaimedByALiveRunIsSkipped() {
        when(repository.conversationIds()).thenReturn(List.of("conv-1"));
        when(slots.claim("conv-1")).thenThrow(new IllegalStateException("busy"));

        recovery.deliverLeftovers();

        verify(chatHistory, org.mockito.Mockito.never()).repairDanglingToolCalls(anyString());
        verify(pendingMessages, org.mockito.Mockito.never()).flushPlain(anyString());
    }

    /** Заявку возвращаем всегда — иначе сорвавшийся чат остался бы занятым до перезапуска. */
    @Test
    void theClaimIsReleasedEvenWhenRepairFails() {
        when(repository.conversationIds()).thenReturn(List.of("conv-1"));
        doThrow(new IllegalStateException("boom"))
                .when(chatHistory)
                .repairDanglingToolCalls("conv-1");

        recovery.deliverLeftovers();

        verify(slots).release("conv-1", "claim-1");
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
