package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.convert.ChatMessageMetaToJsonConverter;
import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.attachment.entity.AttachmentOwnerType;
import io.github.trialiya.kb.model.chat.dto.ContextItemRequest;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Контекст, приложенный к сообщению: что уходит в {@code chat_message.meta} и что из этого видит
 * модель.
 *
 * <p>Тесты идут через настоящую БД, потому что вся идея схемы — в круге «записали в meta →
 * прочитали историю → собрали блок для промпта». Конвертер меты в этом круге участвует наравне с
 * сервисом, и ломается он молча: элемент просто не доедет до модели.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-context-items-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
class ContextItemsTest {

    private static final String QUESTION = "Посмотри файл";
    private static final long ATTACHMENT_ID = 7L;

    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;
    @Autowired private ToolCallIndexRepository toolCallIndexRepo;
    @Autowired private BackfillStateRepository backfillStateRepo;

    private AttachmentService attachmentService;
    private ContextItemService contextItemService;
    private ChatMemoryService memoryService;

    @BeforeEach
    void setUp() {
        attachmentService = mock(AttachmentService.class);
        contextItemService = new ContextItemService(attachmentService);
        memoryService =
                new ChatMemoryService(
                        topicRepo,
                        messageRepo,
                        new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))),
                        toolCallIndexRepo,
                        backfillStateRepo,
                        contextItemService);
    }

    private static Attachment attachment(String conversationId, String fileName) {
        return new Attachment(
                ATTACHMENT_ID,
                AttachmentOwnerType.CHAT,
                null,
                conversationId,
                fileName,
                "text/markdown",
                1234,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private static ContextItemRequest attachmentRequest() {
        return new ContextItemRequest("ATTACHMENT", String.valueOf(ATTACHMENT_ID));
    }

    // ── Проверка присланного клиентом ────────────────────────────────────────

    @Test
    void labelComesFromTheAttachmentNotFromTheClient() {
        String conversationId = UUID.randomUUID().toString();
        when(attachmentService.getById(ATTACHMENT_ID))
                .thenReturn(attachment(conversationId, "report.md"));

        assertThat(contextItemService.resolve(conversationId, List.of(attachmentRequest())))
                .singleElement()
                .isEqualTo(
                        new ContextItem(
                                ContextItemKind.ATTACHMENT,
                                String.valueOf(ATTACHMENT_ID),
                                "report.md"));
    }

    /** Иначе id чужого вложения был бы способом прочитать его содержимое через свой чат. */
    @Test
    void attachmentOfAnotherChatIsRejected() {
        when(attachmentService.getById(ATTACHMENT_ID))
                .thenReturn(attachment("someone-elses-chat", "secret.md"));

        assertThatThrownBy(
                        () ->
                                contextItemService.resolve(
                                        UUID.randomUUID().toString(), List.of(attachmentRequest())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownKindAndNonNumericRefAreRejected() {
        String conversationId = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                contextItemService.resolve(
                                        conversationId,
                                        List.of(new ContextItemRequest("COMMENT", "1"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                contextItemService.resolve(
                                        conversationId,
                                        List.of(new ContextItemRequest("ATTACHMENT", "../etc"))))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── Круг «записали → прочитали → показали модели» ─────────────────────────

    @Test
    void attachedContextTravelsThroughMetaIntoThePrompt() {
        String conversationId = UUID.randomUUID().toString();
        when(attachmentService.getById(ATTACHMENT_ID))
                .thenReturn(attachment(conversationId, "report.md"));

        var saved =
                memoryService.saveUserMessage(
                        conversationId,
                        QUESTION,
                        contextItemService.resolve(conversationId, List.of(attachmentRequest())));

        // В БД — только ссылка: содержимое файла в историю не разворачивается.
        assertThat(messageRepo.findById(saved.getId()))
                .get()
                .satisfies(
                        row -> {
                            assertThat(row.getContent()).isEqualTo(QUESTION);
                            assertThat(row.getContextItems())
                                    .singleElement()
                                    .satisfies(
                                            item -> {
                                                assertThat(item.kind())
                                                        .isEqualTo(ContextItemKind.ATTACHMENT);
                                                assertThat(item.ref())
                                                        .isEqualTo(String.valueOf(ATTACHMENT_ID));
                                            });
                        });

        // А модель видит опись приложенного, дописанную к вопросу при чтении истории.
        assertThat(memoryService.findByConversationId(conversationId))
                .singleElement()
                .satisfies(
                        message -> {
                            assertThat(message.getMessageType()).isEqualTo(MessageType.USER);
                            assertThat(message.getText())
                                    .startsWith(QUESTION)
                                    .contains("<attached-context>")
                                    .contains("id=" + ATTACHMENT_ID)
                                    .contains("report.md")
                                    .contains("getAttachmentContent");
                        });
    }

    /**
     * Вложение удалили после отправки. Ссылка в мете остаётся, но в промпт не попадает: звать
     * модель читать несуществующий файл — хуже, чем промолчать.
     */
    @Test
    void deletedAttachmentQuietlyLeavesThePrompt() {
        String conversationId = UUID.randomUUID().toString();
        when(attachmentService.getById(ATTACHMENT_ID))
                .thenReturn(attachment(conversationId, "report.md"));
        var items = contextItemService.resolve(conversationId, List.of(attachmentRequest()));
        memoryService.saveUserMessage(conversationId, QUESTION, items);

        when(attachmentService.getById(ATTACHMENT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "gone"));

        assertThat(memoryService.findByConversationId(conversationId))
                .singleElement()
                .satisfies(message -> assertThat(message.getText()).isEqualTo(QUESTION));
    }

    /** Сообщение без приложенного не обрастает ни метой, ни лишним блоком в промпте. */
    @Test
    void messageWithoutContextIsUntouched() {
        String conversationId = UUID.randomUUID().toString();

        var saved = memoryService.saveUserMessage(conversationId, QUESTION, List.of());

        assertThat(saved.getMeta()).isNull();
        assertThat(memoryService.findByConversationId(conversationId))
                .singleElement()
                .satisfies(message -> assertThat(message.getText()).isEqualTo(QUESTION));
    }

    /**
     * Вид контекста, которого эта версия не знает, обязан выпадать из списка, а не ронять чтение.
     * Иначе откат приложения после появления нового вида превращался бы в отказ открыть чат, где
     * такой элемент записан.
     */
    @Test
    void unknownKindInStoredMetaIsDroppedNotFatal() {
        var reader = new ChatMessageMetaToJsonConverter.Reader(new ObjectMapper());

        var meta =
                reader.convert(
                        """
                        {"runId":null,"toolCalls":false,"invocations":[],"contextItems":[
                          {"kind":"COMMENT","ref":"1","label":"из будущего"},
                          {"kind":"ATTACHMENT","ref":"7","label":"report.md"}]}
                        """);

        assertThat(meta.contextItems())
                .singleElement()
                .satisfies(item -> assertThat(item.ref()).isEqualTo("7"));
    }
}
