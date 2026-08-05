package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@code token-threshold} объявлен единственным жёстким потолком живого окна и меряет, по
 * собственному javadoc, «то, что уезжает модели в каждом следующем запросе». Этот тест проверяет
 * ровно заявленное — и показывает, что счёт ведётся не по тому тексту.
 *
 * <p>{@code ChatMemoryService#toPromptMessage} дописывает к каждому вопросу с вложением блок {@code
 * <attached-context>} ({@link ContextItemService#render}) — рамку и строку на вложение, внутри
 * которой лежит {@code summary} вложения целиком. Блок собирается при каждом чтении истории и
 * уходит модели в каждом запросе, но в БД не хранится: в {@code chat_message.content} его нет.
 * {@code SummarizeService.messageChars} считает как раз {@code content} плюс {@code tool_data} —
 * описи приложенного он не видит совсем.
 *
 * <p>Длина {@code summary} ничем не ограничена: {@code AttachmentService#summarize} кладёт в поле
 * ответ модели как есть, без обрезки. Поэтому недосчёт не постоянная поправка, а произвольная доля
 * бюджета — окно из вопросов с вложениями может весить кратно больше, чем «видит» пол по бюджету, и
 * тогда сжатие не запускается вовсе.
 */
class SummarizeTokenBudgetTest {

    private static final String CONV = "conv-1";

    /** Боевые значения из {@code application.yaml}. */
    private static final int TOKEN_THRESHOLD = 30_000;

    private static final int MESSAGE_COUNT_THRESHOLD = 50;
    private static final int OVERLAP_MESSAGES = 30;
    private static final int OVERLAP_USER_MESSAGES = 5;
    private static final int CHARS_PER_TOKEN = 4;

    /** Бюджет в символах — та же арифметика, что и в {@code tokenBudgetCutoff}. */
    private static final long BUDGET_CHARS = (long) TOKEN_THRESHOLD * CHARS_PER_TOKEN;

    private static final int QUESTIONS = 40;
    private static final int TEXT_CHARS = 500;

    /** Сводка вложения — единственная часть описи, размер которой задаёт модель, а не формат. */
    private static final int ATTACHMENT_SUMMARY_CHARS = 3_800;

    private ChatMessageRepository repository;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("gist")))));
        when(repository
                        .findChatMessageByConversationIdAndSummarizedFalseAndSummaryTrueOrderByCreatedAtAscPositionAsc(
                                anyString()))
                .thenReturn(List.of());
    }

    /**
     * Диалог, где к каждому вопросу приложен документ со сводкой. По {@code content} окно весит
     * пятую часть бюджета, по тому, что реально уедет модели — полтора бюджета. Пол по бюджету
     * считает первое, поэтому раунд не стартует и живым остаётся окно, которое в бюджет не влезает.
     */
    @Test
    void theBudgetMustCountTheAttachmentInventoryTheModelActuallyReceives() {
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < QUESTIONS; i++) {
            live.add(questionWithAttachment(i));
        }
        givenLive(live);

        service().doSummarize(CONV);

        // Что осталось живым: позиции строго больше последней помеченной summarized. Раунда могло
        // и не быть — тогда живым остаётся всё окно.
        final ArgumentCaptor<Long> endPosition = ArgumentCaptor.forClass(Long.class);
        verify(repository, atMostOnce())
                .updateSummarized(eq(CONV), any(Long.class), endPosition.capture());
        final long lastSummarized =
                endPosition.getAllValues().isEmpty() ? -1L : endPosition.getValue();

        final long tailChars =
                live.stream()
                        .filter(m -> m.getPosition() > lastSummarized)
                        .mapToLong(SummarizeTokenBudgetTest::charsSentToTheModel)
                        .sum();

        assertThat(tailChars)
                .as(
                        "живой хвост, каким его получит модель: %d сообщений по %d символов текста"
                                + " плюс опись приложенного",
                        live.size(), TEXT_CHARS)
                .isLessThanOrEqualTo(BUDGET_CHARS);
    }

    /**
     * Во что превращается сообщение по дороге к модели: {@code content} плюс блок описи, который
     * {@code ChatMemoryService} дописывает при чтении. Протокольную надбавку на сообщение здесь не
     * добавляем — оценка заведомо ниже настоящей, и этого достаточно.
     */
    private static long charsSentToTheModel(ChatMessageEntity message) {
        return message.getText().length() + renderedContextBlock().length();
    }

    /**
     * Слепок того, что вернёт {@link ContextItemService#render} для одного вложения — рамка блока и
     * строка вложения собраны здесь дословно, чтобы вес описи был виден глазами, а не взят
     * константой с потолка.
     */
    private static String renderedContextBlock() {
        return "\n\n<attached-context>\nThe user attached the following to this message:\n"
                + "- attachment id=1 name=\"spec.md\" type=text/markdown size=12345 summary=\""
                + "x".repeat(ATTACHMENT_SUMMARY_CHARS)
                + "\"\nUse getAttachmentContent(attachmentId) to read the full text of an"
                + " attachment.\n</attached-context>";
    }

    private void givenLive(List<ChatMessageEntity> live) {
        when(repository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                eq(CONV)))
                .thenReturn(live);
    }

    private static ChatMessageEntity questionWithAttachment(long position) {
        final String text = "question " + position;
        return new ChatMessageEntity(
                position + 1,
                CONV,
                text + "x".repeat(TEXT_CHARS - text.length()),
                MessageType.USER,
                position,
                false,
                false,
                LocalDateTime.now(),
                ChatMessageMeta.ofContextItems(
                        List.of(new ContextItem(ContextItemKind.ATTACHMENT, "1", "spec.md"))));
    }

    private SummarizeService service() {
        final ContextItemService contextItemService = mock(ContextItemService.class);
        when(contextItemService.render(anyString(), anyList())).thenReturn(renderedContextBlock());
        return new SummarizeService(
                chatModel,
                repository,
                new ByteArrayResource("summarize".getBytes()),
                transactionManager(),
                new SummarizeProperties(
                        TOKEN_THRESHOLD,
                        MESSAGE_COUNT_THRESHOLD,
                        OVERLAP_MESSAGES,
                        OVERLAP_USER_MESSAGES,
                        5,
                        CHARS_PER_TOKEN),
                contextItemService);
    }

    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
