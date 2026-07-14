package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.ERROR;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Read-before-write guard of {@link DocumentFunction#updateDocument}: a content update must be
 * preceded by a successful {@code getDocument} of the same document within the same chat-response
 * session (tracked by the request-scoped {@link ToolInvocationCollector}), unless {@code
 * forceOverwrite=true} is passed explicitly.
 */
class DocumentFunctionUpdateGuardTest {

    private static final long DOC_ID = 42L;

    private DocumentService documentService;
    private DocumentFunction function;
    private ToolInvocationCollector collector;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        function = new DocumentFunction(documentService, mock(AttachmentService.class));
        collector = new ToolInvocationCollector();
        context = new ToolContext(Map.of(ToolInvocationCollector.KEY, collector));

        Document updated = mock(Document.class);
        when(updated.toDocumentShort())
                .thenReturn(
                        new DocumentShort(
                                DOC_ID,
                                "title",
                                "document",
                                null,
                                1,
                                1,
                                LocalDateTime.now(),
                                false,
                                null));
        when(documentService.update(anyLong(), any())).thenReturn(updated);
    }

    @Test
    void contentUpdateWithoutPriorReadIsRejected() {
        assertThatThrownBy(
                        () -> function.updateDocument(context, DOC_ID, null, "new content", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("НЕ обновлён")
                .hasMessageContaining("getDocument")
                .hasMessageContaining("forceOverwrite");
        verify(documentService, never()).update(anyLong(), any(UpdateDocumentRequest.class));
    }

    @Test
    void contentUpdateAfterSuccessfulReadPasses() {
        recordGetDocument(String.valueOf(DOC_ID), OK);

        assertThatCode(() -> function.updateDocument(context, DOC_ID, null, "new content", null))
                .doesNotThrowAnyException();
        verify(documentService).update(anyLong(), any(UpdateDocumentRequest.class));
    }

    @Test
    void numericDocumentIdArgumentAlsoCountsAsRead() {
        recordGetDocument(42, OK);

        assertThatCode(() -> function.updateDocument(context, DOC_ID, null, "new content", null))
                .doesNotThrowAnyException();
    }

    @Test
    void readOfDifferentDocumentDoesNotCount() {
        recordGetDocument("7", OK);

        assertThatThrownBy(
                        () -> function.updateDocument(context, DOC_ID, null, "new content", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedReadDoesNotCount() {
        recordGetDocument(String.valueOf(DOC_ID), ERROR);

        assertThatThrownBy(
                        () -> function.updateDocument(context, DOC_ID, null, "new content", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forceOverwriteSkipsTheCheck() {
        assertThatCode(() -> function.updateDocument(context, DOC_ID, null, "new content", true))
                .doesNotThrowAnyException();
        verify(documentService).update(anyLong(), any(UpdateDocumentRequest.class));
    }

    @Test
    void titleOnlyUpdateDoesNotRequireRead() {
        assertThatCode(() -> function.updateDocument(context, DOC_ID, "new title", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void missingCollectorSkipsTheCheck() {
        ToolContext noCollector = new ToolContext(Map.of());

        assertThatCode(
                        () ->
                                function.updateDocument(
                                        noCollector, DOC_ID, null, "new content", null))
                .doesNotThrowAnyException();
    }

    private void recordGetDocument(Object documentIdArg, ToolInvocationStatus status) {
        collector.record(
                new ToolInvocation(
                        "getDocument",
                        Map.of("documentId", documentIdArg),
                        status,
                        null,
                        null,
                        null,
                        "{}",
                        null,
                        collector.nextCallIndex()));
    }
}
