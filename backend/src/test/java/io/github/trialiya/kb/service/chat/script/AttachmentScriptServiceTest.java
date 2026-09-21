package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.attachment.entity.AttachmentOwnerType;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Running an attachment: what is refused before the sandbox is even built.
 *
 * <p>The checks here are not about access — {@code getAttachmentContent} already hands the model
 * the text of any attachment by id, so refusing to run what it may already read would guard
 * nothing. They are about answering the model with a fact it can act on: "this is a PDF" is one,
 * "unexpected token %" is not.
 */
class AttachmentScriptServiceTest {

    private AttachmentService attachments;

    @BeforeEach
    void setUp() {
        attachments = mock(AttachmentService.class);
    }

    @Test
    void runsAJavaScriptAttachment() {
        stub(12, "report.js", "text/javascript", "return 1;");

        ScriptSource source = service(true).source("attachment:12", Map.of("area", "docs"));

        assertThat(source.text()).isEqualTo("return 1;");
        assertThat(source.sourceName()).isEqualTo("report.js");
        assertThat(source.report()).isNotNull();
        assertThat(source.report().kind()).isEqualTo(ScriptRunSource.Kind.ATTACHMENT);
        assertThat(source.report().name()).isEqualTo("attachment:12");
        assertThat(source.report().path()).isEqualTo("report.js");
        assertThat(source.report().sha()).hasSize(12);
        assertThat(source.report().args()).containsEntry("area", "docs");
    }

    /** The content type is enough on its own — an upload may arrive with any name. */
    @Test
    void acceptsAScriptByContentTypeAsWellAsByName() {
        stub(7, "snippet", "application/javascript", "return 1;");

        assertThat(service(true).source("attachment:7", Map.of()).text()).isEqualTo("return 1;");
    }

    @Test
    void refusesWhatIsNotAScript() {
        stub(3, "report.pdf", "application/pdf", "%PDF-1.7");

        assertThatThrownBy(() -> service(true).source("attachment:3", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("report.pdf")
                .hasMessageContaining("application/pdf");
    }

    @Test
    void refusesAnEmptyOrOversizeAttachment() {
        stub(4, "empty.js", "text/javascript", "   ");
        assertThatThrownBy(() -> service(true).source("attachment:4", Map.of()))
                .hasMessageContaining("empty");

        stub(5, "huge.js", "text/javascript", "x".repeat((int) SavedScriptCatalog.MAX_BYTES + 1));
        assertThatThrownBy(() -> service(true).source("attachment:5", Map.of()))
                .hasMessageContaining("too large");
    }

    @Test
    void refusesAMalformedReferenceAndAnUnknownId() {
        assertThatThrownBy(() -> service(true).source("attachment:report.js", Map.of()))
                .hasMessageContaining("attachment:<id>");

        when(attachments.getById(99L)).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> service(true).source("attachment:99", Map.of()))
                .hasMessageContaining("no attachment 99");
    }

    /**
     * The switch exists for a deployment willing to run what its own repository declares but not
     * what sits in its knowledge base — and the refusal has to name the alternative, or the model
     * spends the next call trying the same thing.
     */
    @Test
    void refusesWhenAttachmentRunsAreSwitchedOff() {
        AttachmentScriptService service = service(false);

        assertThat(service.available()).isFalse();
        assertThatThrownBy(() -> service.source("attachment:12", Map.of()))
                .hasMessageContaining("attachment-run=false")
                .hasMessageContaining("runScript");
    }

    @Test
    void knowsWhichNamesItAnswersFor() {
        assertThat(AttachmentScriptService.addresses("attachment:12")).isTrue();
        assertThat(AttachmentScriptService.addresses("locale-diff")).isFalse();
    }

    private AttachmentScriptService service(boolean attachmentRun) {
        return new AttachmentScriptService(
                attachments,
                new ScriptProperties(
                        true, true, attachmentRun, null, null, null, null, null, null, null, null));
    }

    private void stub(long id, String fileName, String contentType, String content) {
        when(attachments.getById(id))
                .thenReturn(
                        new Attachment(
                                id,
                                AttachmentOwnerType.CHAT,
                                null,
                                "conv-1",
                                fileName,
                                contentType,
                                content.length(),
                                null,
                                null,
                                OffsetDateTime.now(),
                                OffsetDateTime.now()));
        when(attachments.getContent(id)).thenReturn(content);
    }
}
