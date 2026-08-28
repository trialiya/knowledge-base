package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.QueuedMessagePayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatPendingMessageEntity;
import io.github.trialiya.kb.repository.ChatPendingMessageRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Очередь доставляется claim-through-delete: строку переносит тот, чей DELETE её застал. Здесь
 * закреплено ровно это — и то, что оба режима доставки ({@code flushMidTurn}/{@code flushPlain})
 * различаются только флагом на ряду и на событии.
 */
class PendingMessageServiceTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final ChatPendingMessageRepository repository =
            mock(ChatPendingMessageRepository.class);
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatEventService events = mock(ChatEventService.class);

    private final PendingMessageService service =
            new PendingMessageService(repository, chatHistory, events);

    @Test
    void enqueuePersistsTheRowAndTellsEveryTab() {
        when(repository.save(any(ChatPendingMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enqueue(
                CONV,
                "admin",
                "и добавь тесты",
                List.of(),
                new PendingMessageService.PendingOptions("gpt-5", null, "kb"),
                RUN,
                "client-1");

        final ArgumentCaptor<ChatPendingMessageEntity> row =
                ArgumentCaptor.forClass(ChatPendingMessageEntity.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getContent()).isEqualTo("и добавь тесты");
        assertThat(row.getValue().getModel()).isEqualTo("gpt-5");
        assertThat(row.getValue().getProject()).isEqualTo("kb");
        verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.MESSAGE_QUEUED),
                        eq(RUN),
                        eq("client-1"),
                        any(QueuedMessagePayload.class));
    }

    @Test
    void flushMidTurnDeliversInOrderWithTheInterjectionFlag() {
        givenQueued(pending(1, "первое"), pending(2, "второе"));
        when(repository.claim(1)).thenReturn(1);
        when(repository.claim(2)).thenReturn(1);
        when(chatHistory.saveDeliveredPending(eq(CONV), anyString(), anyList(), anyBoolean()))
                .thenAnswer(
                        invocation ->
                                deliveredRow(invocation.getArgument(1), invocation.getArgument(3)));
        when(chatHistory.promptMessagesFor(eq(CONV), anyList())).thenReturn(List.of());

        service.flushMidTurn(CONV, RUN);

        final ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(chatHistory, org.mockito.Mockito.times(2))
                .saveDeliveredPending(eq(CONV), texts.capture(), anyList(), eq(true));
        assertThat(texts.getAllValues()).containsExactly("первое", "второе");

        final ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2))
                .publishIfPresent(
                        eq(CONV),
                        eq(ChatEventType.USER_MESSAGE),
                        eq(RUN),
                        any(),
                        payloads.capture());
        assertThat(payloads.getAllValues())
                .allSatisfy(
                        p ->
                                assertThat(((UserMessagePayload) p).interjection())
                                        .isEqualTo(Boolean.TRUE));
    }

    /** Строка, которую успела забрать другая точка доставки, второй ряд истории не получает. */
    @Test
    void aRowClaimedElsewhereIsSkippedSilently() {
        givenQueued(pending(1, "первое"));
        when(repository.claim(1)).thenReturn(0);

        final List<?> injected = service.flushMidTurn(CONV, RUN);

        assertThat(injected).isEmpty();
        verify(chatHistory, never())
                .saveDeliveredPending(anyString(), anyString(), anyList(), anyBoolean());
        verify(events, never())
                .publishIfPresent(eq(CONV), eq(ChatEventType.USER_MESSAGE), any(), any(), any());
    }

    @Test
    void flushPlainDeliversWithoutTheFlagAndReportsIt() {
        givenQueued(pending(1, "первое"));
        when(repository.claim(1)).thenReturn(1);
        when(chatHistory.saveDeliveredPending(eq(CONV), anyString(), anyList(), anyBoolean()))
                .thenAnswer(
                        invocation ->
                                deliveredRow(invocation.getArgument(1), invocation.getArgument(3)));

        assertThat(service.flushPlain(CONV).any()).isTrue();

        verify(chatHistory).saveDeliveredPending(eq(CONV), eq("первое"), anyList(), eq(false));
        final ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events)
                .publishIfPresent(
                        eq(CONV),
                        eq(ChatEventType.USER_MESSAGE),
                        eq(null),
                        any(),
                        payload.capture());
        assertThat(((UserMessagePayload) payload.getValue()).interjection()).isNull();
    }

    @Test
    void anEmptyQueueFlushesToNothing() {
        givenQueued();

        assertThat(service.flushPlain(CONV).any()).isFalse();
        assertThat(service.flushPlain(CONV).options())
                .isEqualTo(PendingMessageService.PendingOptions.NONE);
        assertThat(service.flushMidTurn(CONV, RUN)).isEmpty();
    }

    private void givenQueued(ChatPendingMessageEntity... rows) {
        when(repository.findByConversationIdOrderByIdAsc(CONV)).thenReturn(List.of(rows));
    }

    private static ChatPendingMessageEntity pending(long id, String text) {
        return new ChatPendingMessageEntity(
                id,
                CONV,
                "admin",
                text,
                "client-" + id,
                null,
                null,
                null,
                null,
                LocalDateTime.now());
    }

    private static ChatMessageEntity deliveredRow(String text, boolean interjection) {
        return new ChatMessageEntity(
                10,
                CONV,
                text,
                MessageType.USER,
                10,
                false,
                false,
                LocalDateTime.now(),
                interjection ? ChatMessageMeta.ofInterjection(List.of()) : null);
    }
}
