package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.repository.AttachmentEmbeddingRepository;
import io.github.trialiya.kb.repository.AttachmentRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link AttachmentService}'s upload-time outline: a cheap structural preview (markdown headings,
 * source symbol names) computed once and stored alongside the file, cheaper than the AI-generated
 * {@code summary} — see {@code ContextItemService} for how it reaches the model.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-attachment-outline-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
class AttachmentOutlineTest {

    @Autowired private AttachmentRepository attachmentRepo;
    @Autowired private ChatTopicRepository chatTopicRepo;

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        // AttachmentEmbeddingRepository — обычный @Repository-класс, а не Spring Data JDBC
        // интерфейс, поэтому в срез @DataJdbcTest не попадает; индексация всё равно уходит на
        // отдельный виртуальный поток и здесь не проверяется — мок безопасен.
        service =
                new AttachmentService(
                        attachmentRepo,
                        mock(AttachmentEmbeddingRepository.class),
                        mock(EmbeddingService.class),
                        new ChatTopicService(chatTopicRepo),
                        new OutlineService(),
                        mock(OpenAiChatModel.class));
    }

    private MockMultipartFile file(String name, String contentType, String content) {
        return new MockMultipartFile(
                "file", name, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void markdownHeadingsBecomeTheOutline() {
        Attachment uploaded =
                service.uploadForChat(
                        UUID.randomUUID().toString(),
                        file(
                                "notes.md",
                                "text/markdown",
                                "# Title\n\nSome text.\n\n## Section\n\nMore text.\n"));

        assertThat(uploaded.outline()).isEqualTo("# Title / ## Section");
    }

    @Test
    void supportedSourceLanguageYieldsSymbolNames() {
        Attachment uploaded =
                service.uploadForChat(
                        UUID.randomUUID().toString(),
                        file(
                                "Greeter.java",
                                "text/x-java-source",
                                "public class Greeter {\n"
                                        + "    public String hello() { return \"hi\"; }\n"
                                        + "}\n"));

        assertThat(uploaded.outline()).contains("Greeter").contains("hello");
    }

    /** Plain text has neither headings nor a supported language — no outline to show. */
    @Test
    void plainTextHasNoOutline() {
        Attachment uploaded =
                service.uploadForChat(
                        UUID.randomUUID().toString(),
                        file("notes.txt", "text/plain", "just some text"));

        assertThat(uploaded.outline()).isNull();
    }
}
