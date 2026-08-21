package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.service.chat.AttachmentService;
import io.github.trialiya.kb.service.document.DocumentService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * {@code editDocument}: the exact-match fragment replacement, and what it deliberately does not ask
 * for — a prior read of the document and a {@code descriptionVersion}, both of which the other
 * write tools require. The splice is handed to {@code DocumentService.patchDescription} as a pure
 * function, so the tests capture that function and apply it to a text of their own.
 *
 * <p>Note the missing {@link ToolContext} parameter in the calls below: the tool has none, because
 * there is no tool history for it to consult.
 */
class DocumentFunctionEditDocumentTest {

    private static final long DOC_ID = 42L;
    private static final String MD = "# Гайд\nстарый текст\n## FAQ\nстарый текст в FAQ\n";

    private DocumentService documentService;
    private DocumentFunction function;

    /** Text the captured patch produced, or null when the tool never reached the service. */
    private AtomicReference<String> patched;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        function = new DocumentFunction(documentService, mock(AttachmentService.class));
        patched = new AtomicReference<>();
        storedAs(MD);
    }

    /** Runs the captured patch against {@code description} — the document as the service has it. */
    private void storedAs(String description) {
        Document document = mock(Document.class);
        when(document.toDocumentShort())
                .thenReturn(
                        new DocumentShort(
                                DOC_ID,
                                "Гайд",
                                "document",
                                null,
                                2,
                                4,
                                LocalDateTime.now(),
                                false,
                                null));
        when(documentService.patchDescription(anyLong(), any(UnaryOperator.class)))
                .thenAnswer(
                        inv -> {
                            UnaryOperator<String> patch = inv.getArgument(1);
                            patched.set(patch.apply(description));
                            return document;
                        });
    }

    @Test
    void replacesTheUniqueFragmentWithoutAnyPriorRead() {
        DocumentShort result = function.editDocument(DOC_ID, "# Гайд", "# Руководство", false);

        assertThat(result.id()).isEqualTo(DOC_ID);
        assertThat(patched.get())
                .isEqualTo("# Руководство\nстарый текст\n## FAQ\nстарый текст в FAQ\n");
    }

    @Test
    void emptyNewStringDeletesTheFragment() {
        function.editDocument(DOC_ID, "## FAQ\nстарый текст в FAQ\n", "", false);

        assertThat(patched.get()).isEqualTo("# Гайд\nстарый текст\n");
    }

    @Test
    void ambiguousFragmentIsRefusedUntilReplaceAll() {
        assertThatThrownBy(
                        () -> function.editDocument(DOC_ID, "старый текст", "новый текст", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurs 2 times")
                .hasMessageContaining("document id=" + DOC_ID);

        assertThatCode(() -> function.editDocument(DOC_ID, "старый текст", "новый текст", true))
                .doesNotThrowAnyException();
        assertThat(patched.get()).isEqualTo("# Гайд\nновый текст\n## FAQ\nновый текст в FAQ\n");
    }

    @Test
    void matchesAFragmentQuotedWithPlainNewlinesAgainstACrlfDocument() {
        // A document imported from Windows keeps its CRLF endings; a model quoting two of its lines
        // back writes "\n" between them. Refusing that would send the model to re-read a text that
        // comes back looking exactly like what it just quoted.
        String crlf = MD.replace("\n", "\r\n");
        AtomicReference<String> patchedCrlf = new AtomicReference<>();
        when(documentService.patchDescription(anyLong(), any(UnaryOperator.class)))
                .thenAnswer(
                        inv -> {
                            UnaryOperator<String> patch = inv.getArgument(1);
                            patchedCrlf.set(patch.apply(crlf));
                            return null;
                        });

        assertThatThrownBy(
                        () ->
                                function.editDocument(
                                        DOC_ID,
                                        "# Гайд\nстарый текст",
                                        "# Гайд\nновый текст",
                                        false))
                // The stub returns null once the patch has run — the splice is what this pins.
                .isInstanceOf(NullPointerException.class);
        assertThat(patchedCrlf.get())
                .isEqualTo("# Гайд\r\nновый текст\r\n## FAQ\r\nстарый текст в FAQ\r\n");
    }

    @Test
    void missingFragmentTellsTheModelToReRead() {
        assertThatThrownBy(() -> function.editDocument(DOC_ID, "нет такого", "x", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oldString not found")
                .hasMessageContaining("getDocument");
    }

    @Test
    void absentArgumentsAreNamed() {
        assertThatThrownBy(() -> function.editDocument(null, "a", "b", false))
                .hasMessageContaining("documentId");
        assertThatThrownBy(() -> function.editDocument(DOC_ID, null, "b", false))
                .hasMessageContaining("oldString");
        // Absent newString is not the same instruction as an empty one: "" deletes the fragment,
        // absent means the model forgot the replacement.
        assertThatThrownBy(() -> function.editDocument(DOC_ID, "a", null, false))
                .hasMessageContaining("newString");
        assertThat(patched.get()).isNull();
    }
}
