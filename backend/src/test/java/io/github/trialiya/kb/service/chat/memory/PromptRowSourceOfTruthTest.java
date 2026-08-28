package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Один источник правды о том, что именно уезжает модели.
 *
 * <p>Опись приложенного дописывается к вопросу при чтении истории и в {@code chat_message.content}
 * не хранится. Пока эту приписку собирал каждый читатель сам, счёт веса окна в {@code
 * SummarizeService} шёл по сохранённой строке и не видел описи вовсе: окно из вопросов с вложениями
 * весило кратно больше, чем «видел» пол по токенному бюджету, и сжатие не запускалось.
 *
 * <p>Тест закрепляет не конкретную утечку, а её механизм: промпт и вес считаются по одному и тому
 * же {@link PromptRow#text()}. Разойтись они смогут только если кто-то заново заведёт второй способ
 * узнать текст сообщения — и тогда упадёт этот тест, а не продакшен.
 */
class PromptRowSourceOfTruthTest {

    private static final String CONV = "conv-1";
    private static final String INVENTORY =
            "\n\n<attached-context>\n- attachment id=1\n</attached-context>";

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ContextItemService contextItemService = mock(ContextItemService.class);

    private final ChatHistoryService service =
            new ChatHistoryService(
                    chatMessageRepository,
                    contextItemService,
                    new ToolCallService(chatMessageRepository, mock(ToolCallIndexRepository.class)),
                    new ToolCallEventPublisher(mock(ChatEventService.class)));

    /** Текст строки — content плюс опись; у сообщений без вложений он равен content. */
    @Test
    void promptRowCarriesTheRenderedInventoryOnTopOfTheStoredContent() {
        final ChatMessageEntity withAttachment = question(0, "смотри файл", true);
        final ChatMessageEntity plain = question(1, "и ещё вопрос", false);
        givenStored(List.of(withAttachment, plain));

        final List<PromptRow> rows = service.promptRows(CONV);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).text()).isEqualTo("смотри файл" + INVENTORY);
        assertThat(rows.get(1).text()).isEqualTo("и ещё вопрос");
        // Сохранённая строка при этом не менялась — опись живёт только в промпте.
        assertThat(rows.get(0).entity().getContent()).isEqualTo("смотри файл");
    }

    /**
     * Промпт строится из тех же строк. Это и есть смысл всей конструкции: то, что меряет бюджет, и
     * то, что уходит модели, — один текст, а не два похожих.
     */
    @Test
    void theModelReceivesExactlyThePromptRowText() {
        final ChatMessageEntity withAttachment = question(0, "смотри файл", true);
        givenStored(List.of(withAttachment));

        final List<PromptRow> rows = service.promptRows(CONV);
        final List<Message> messages = service.promptMessages(CONV);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getText()).isEqualTo(rows.getFirst().text());
        assertThat(messages.getFirst().getText()).isEqualTo("смотри файл" + INVENTORY);
    }

    /**
     * Описи собираются одним запросом на всё окно, а не запросом на сообщение: промпт строится на
     * каждой итерации tool-цикла, и запрос на вопрос превращал бы длинный диалог в N запросов.
     */
    @Test
    void theInventoryIsResolvedOncePerWindowNotOncePerMessage() {
        givenStored(
                List.of(
                        question(0, "первый", true),
                        question(1, "второй", true),
                        question(2, "третий", true)));

        service.promptRows(CONV);

        verify(contextItemService).renderAll(anyString(), anyList());
        verify(contextItemService, never()).render(anyString(), anyList());
    }

    /**
     * Описью обрастает только вопрос — и решает это одно место, до того как строка разойдётся на
     * промпт и на весы. Пока проверка типа стояла ниже по течению, в сборке сообщения, опись
     * приложенного к ответу модели попадала в вес окна и не попадала в промпт: оценка завышалась на
     * длину описи, у которой нет верхнего предела.
     */
    @Test
    void onlyAQuestionCarriesTheInventoryAndBothSidesAgreeOnThat() {
        givenStored(List.of(row(0, "по файлу вижу", MessageType.ASSISTANT, true)));

        final List<PromptRow> rows = service.promptRows(CONV);
        final List<Message> messages = service.promptMessages(CONV);

        assertThat(rows.getFirst().text()).isEqualTo("по файлу вижу");
        assertThat(messages.getFirst().getText()).isEqualTo(rows.getFirst().text());
        assertThat(messages.getFirst().getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }

    private void givenStored(List<ChatMessageEntity> rows) {
        when(chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                CONV))
                .thenReturn(rows);
        when(contextItemService.renderAll(anyString(), anyList()))
                .thenReturn(
                        rows.stream()
                                .filter(row -> !row.getContextItems().isEmpty())
                                .collect(
                                        Collectors.toMap(
                                                ChatMessageEntity::getId, row -> INVENTORY)));
    }

    private static ChatMessageEntity question(long position, String text, boolean withAttachment) {
        return row(position, text, MessageType.USER, withAttachment);
    }

    private static ChatMessageEntity row(
            long position, String text, MessageType type, boolean withAttachment) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                text,
                type,
                position,
                false,
                false,
                LocalDateTime.now(),
                withAttachment
                        ? ChatMessageMeta.ofContextItems(
                                List.of(
                                        new ContextItem(
                                                ContextItemKind.ATTACHMENT, "1", "spec.md")))
                        : null);
    }
}
