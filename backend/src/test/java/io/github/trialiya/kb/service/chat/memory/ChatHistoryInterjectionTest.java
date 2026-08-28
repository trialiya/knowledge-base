package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Вопрос, доставленный посреди прогона ({@code meta.interjection}), — обычный USER-ряд с одним
 * отличием: модель обязана узнать, что пользователь писал его, глядя на ход работы, а не на готовый
 * ответ. Нотис собирается на чтении и в БД не попадает — как у смены проекта и git-команды.
 */
class ChatHistoryInterjectionTest {

    private static final String CONV = "conv-1";

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ContextItemService contextItemService = mock(ContextItemService.class);

    private final ChatHistoryService service =
            new ChatHistoryService(
                    chatMessageRepository,
                    contextItemService,
                    new ToolCallService(chatMessageRepository, mock(ToolCallIndexRepository.class)),
                    new ToolCallEventPublisher(mock(ChatEventService.class)));

    @Test
    void anInterjectionRowIsWrappedInItsNotice() {
        givenStored(List.of(interjection(3, "и добавь тесты")));

        final String text = service.promptRows(CONV).getFirst().text();

        assertThat(text)
                .startsWith("<user-interjection>")
                .contains("while you were still working")
                .contains("и добавь тесты");
    }

    /**
     * Инструкция summarizer'у здесь противоположна маркеру смены проекта: свернуть как обычную
     * реплику, тег не сохранять. Дословная обёртка копила бы служебный текст в каждой сводке.
     */
    @Test
    void theNoticeTellsTheSummarizerToFoldRatherThanPreserve() {
        givenStored(List.of(interjection(3, "и добавь тесты")));

        assertThat(service.promptRows(CONV).getFirst().text())
                .contains("fold its content")
                .doesNotContain("preserve this notice verbatim");
    }

    /** Обычный вопрос нотиса не получает — его текст уезжает модели как есть. */
    @Test
    void anOrdinaryQuestionCarriesNoNotice() {
        givenStored(List.of(question(1, "почини сборку")));

        assertThat(service.promptRows(CONV).getFirst().text()).isEqualTo("почини сборку");
    }

    /** Доставка посреди прогона помечает ряд флагом; вложения переезжают в ту же мету. */
    @Test
    void aMidTurnDeliveryMarksTheRowAsInterjection() {
        givenAppendableHistory(4);
        final List<ContextItem> items =
                List.of(new ContextItem(ContextItemKind.ATTACHMENT, "7", "log.txt"));

        final ChatMessageEntity saved =
                service.saveDeliveredPending(CONV, "смотри лог", items, true);

        assertThat(saved.getMeta()).isNotNull();
        assertThat(saved.getMeta().interjection()).isTrue();
        assertThat(saved.getContextItems()).isEqualTo(items);
        assertThat(saved.getType()).isEqualTo(MessageType.USER);
        assertThat(saved.getPosition()).isEqualTo(5);
    }

    /**
     * Доставка после завершения прогона — обычный вопрос: без флага, а без вложений и вовсе без
     * меты, неотличимо от {@code saveUserMessage}. Follow-up прогон и «Повторить» подберут его
     * штатно.
     */
    @Test
    void aPlainDeliveryIsAnOrdinaryQuestion() {
        givenAppendableHistory(4);

        final ChatMessageEntity saved =
                service.saveDeliveredPending(CONV, "смотри лог", List.of(), false);

        assertThat(saved.getMeta()).isNull();
        assertThat(saved.getPosition()).isEqualTo(5);
    }

    /** Флагованный ряд — настоящий вопрос: оставшись без ответа, он даёт «Повторить». */
    @Test
    void anUnansweredInterjectionStillOffersRetry() {
        final ChatMessageEntity row = interjection(5, "и добавь тесты");
        when(chatMessageRepository.findTop20ByConversationIdOrderByPositionDesc(CONV))
                .thenReturn(List.of(row));

        assertThat(service.unansweredUserMessage(CONV)).contains(row);
    }

    private void givenStored(List<ChatMessageEntity> rows) {
        when(chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                CONV))
                .thenReturn(rows);
        when(contextItemService.renderAll(anyString(), anyList())).thenReturn(Map.of());
    }

    private void givenAppendableHistory(long lastPosition) {
        when(chatMessageRepository.maxPosition(CONV)).thenReturn(lastPosition);
        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ChatMessageEntity interjection(long position, String text) {
        return entity(position, text, ChatMessageMeta.ofInterjection(List.of()));
    }

    private static ChatMessageEntity question(long position, String text) {
        return entity(position, text, null);
    }

    private static ChatMessageEntity entity(
            long position, String text, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                text,
                MessageType.USER,
                position,
                false,
                false,
                LocalDateTime.now(),
                meta);
    }
}
