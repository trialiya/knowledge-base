package io.github.trialiya.kb.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.ChatMemoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Порог по токенам обязан мерить то, что реально уезжает модели.
 *
 * <p>К вопросу с вложением при чтении истории дописывается блок {@code <attached-context>} — опись
 * приложенного. Она уходит в каждый запрос, но в {@code chat_message.content} её нет, а длина
 * {@code summary} вложения ничем не ограничена: {@code AttachmentService#summarize} кладёт в поле
 * ответ модели как есть. Считать сохранённую колонку значит не видеть описи вовсе, а с ней —
 * произвольную долю окна.
 *
 * <p>Два теста здесь — одна пара: одно и то же окно из 58 вопросов срабатывает по порогу, когда к
 * вопросам приложены документы, и не срабатывает, когда их текст тот же, но вложений нет. По
 * сохранённому тексту оба окна весят ~3 600 токенов при пороге 30 000 — разделить их может только
 * оценка, считающая текст промпта.
 */
class SummarizeTokenBudgetTest {

    private static final String CONV = "conv-1";

    /** Боевые значения из {@code application.yaml}. */
    private static final int TOKEN_THRESHOLD = 30_000;

    private static final int MESSAGE_COUNT_THRESHOLD = 50;
    private static final int OVERLAP_MESSAGES = 30;
    private static final int OVERLAP_USER_MESSAGES = 5;
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 58 вопросов при {@code overlap-messages: 30} дают срез ровно в 28 сообщений — меньше {@code
     * message-count-threshold}, поэтому порог по числу сообщений заведомо молчит и о раунде может
     * попросить только оценка токенов.
     */
    private static final int QUESTIONS = 58;

    private static final int SLICE = QUESTIONS - OVERLAP_MESSAGES;
    private static final int TEXT_CHARS = 500;

    /** Сводка вложения — единственная часть описи, размер которой задаёт модель, а не формат. */
    private static final int ATTACHMENT_SUMMARY_CHARS = 3_800;

    private ChatMessageRepository repository;
    private ChatMemoryService chatMemoryService;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatMemoryService = mock(ChatMemoryService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("gist")))));
    }

    /**
     * К каждому вопросу приложен документ. Срез из 28 сообщений весит по сохранённому тексту ~3 600
     * токенов, а по тому, что уедет модели, — ~31 900: порог перейдён только вместе с описью.
     */
    @Test
    void theTokenTriggerCountsTheAttachmentInventoryTheModelActuallyReceives() {
        givenLive(questions(true));

        service().doSummarize(CONV);

        // Срез — вопросы 0..27, хвост открывается на 28-м.
        verify(repository).updateSummarized(CONV, 0L, (long) SLICE - 1);
    }

    /**
     * То же окно и тот же сохранённый текст, но без вложений: описи нет, и в этом случае раунда
     * действительно быть не должно. Пара с предыдущим тестом: одна оценка обязана различать эти два
     * окна, и различает она их ровно на вес описи.
     */
    @Test
    void theSameWindowWithoutAttachmentsStaysBelowTheTrigger() {
        givenLive(questions(false));

        service().doSummarize(CONV);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
    }

    // -------------------------------------------------------------------------

    private static List<ChatMessageEntity> questions(boolean withAttachment) {
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < QUESTIONS; i++) {
            live.add(question(i, withAttachment));
        }
        return live;
    }

    /**
     * То же, что делает {@code ChatMemoryService#promptRows}: к вопросу с приложенным контекстом
     * дописывается блок описи. Здесь это моделируется явно, потому что именно эта разница между
     * сохранённым content и текстом промпта и есть предмет теста.
     */
    private void givenLive(List<ChatMessageEntity> live) {
        when(chatMemoryService.promptRows(eq(CONV)))
                .thenReturn(
                        live.stream()
                                .map(
                                        entity ->
                                                new PromptRow(
                                                        entity,
                                                        entity.getContextItems().isEmpty()
                                                                ? entity.getContent()
                                                                : entity.getContent()
                                                                        + renderedContextBlock()))
                                .toList());
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

    private static ChatMessageEntity question(long position, boolean withAttachment) {
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
                withAttachment
                        ? ChatMessageMeta.ofContextItems(
                                List.of(
                                        new ContextItem(
                                                ContextItemKind.ATTACHMENT, "1", "spec.md")))
                        : null);
    }

    private SummarizeService service() {
        final ContextItemService contextItemService = mock(ContextItemService.class);
        when(contextItemService.render(anyString(), anyList())).thenReturn(renderedContextBlock());
        return new SummarizeService(
                chatModel,
                repository,
                chatMemoryService,
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
