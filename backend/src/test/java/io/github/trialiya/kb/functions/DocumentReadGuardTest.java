package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.document.DocumentService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * The two ways out of a read-before-write refusal that {@link DocumentReadGuard} adds on top of "a
 * read in this response": a read earlier in the chat that is still true, and {@code
 * retryDocumentWrite} replaying the refused call once the read is done.
 */
class DocumentReadGuardTest {

    private static final long DOC_ID = 42L;
    private static final int CURRENT_VERSION = 3;
    private static final String CHAT = "chat-1";

    private DocumentService documentService;
    private DocumentFunction function;
    private ToolInvocationCollector collector;
    private ToolContext context;
    private final List<ToolData.Response> window = new ArrayList<>();

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        function =
                new DocumentFunction(
                        documentService,
                        mock(AttachmentService.class),
                        chat -> CHAT.equals(chat) ? List.copyOf(window) : List.of());
        collector = new ToolInvocationCollector();
        context =
                new ToolContext(
                        Map.of(
                                ToolInvocationCollector.KEY,
                                collector,
                                ChatMemory.CONVERSATION_ID,
                                CHAT));

        when(documentService.getById(DOC_ID)).thenReturn(node(CURRENT_VERSION));
        Document updated = mock(Document.class);
        DocumentShort shortDoc =
                new DocumentShort(
                        DOC_ID,
                        "title",
                        "document",
                        null,
                        1,
                        CURRENT_VERSION + 1,
                        LocalDateTime.now(),
                        false,
                        null);
        when(updated.toDocumentShort()).thenReturn(shortDoc);
        when(documentService.update(anyLong(), any())).thenReturn(updated);
        when(documentService.patchDescription(anyLong(), anyInt(), any())).thenReturn(updated);
    }

    private static DocumentNode node(int descriptionVersion) {
        return new DocumentNode(
                DOC_ID,
                "Гайд",
                "document",
                null,
                1,
                "# Гайд\nintro\n## Установка\nold install\n",
                descriptionVersion,
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of(),
                false,
                false,
                null,
                false,
                null);
    }

    /** A TOOL response of the live window, shaped as the result converter writes it. */
    private void earlierRead(String tool, String json) {
        window.add(new ToolData.Response("call-" + window.size(), tool, json));
    }

    @Nested
    class ReadEarlierInTheChat {

        @Test
        void anUnchangedDocumentNeedsNoSecondRead() {
            earlierRead(
                    "getDocument",
                    "{\"id\":42,\"title\":\"Гайд\",\"descriptionVersion\":3,\"description\":\"…\"}");

            assertThatCode(() -> function.updateDocument(context, DOC_ID, null, "new"))
                    .doesNotThrowAnyException();
            verify(documentService).update(anyLong(), any(UpdateDocumentRequest.class));
        }

        @Test
        void aDocumentChangedSinceTheReadMustBeReadAgain() {
            earlierRead("getDocument", "{\"id\":42,\"descriptionVersion\":2}");

            assertThatThrownBy(() -> function.updateDocument(context, DOC_ID, null, "new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("НЕ обновлён");
            verify(documentService, never()).update(anyLong(), any());
        }

        @Test
        void aReadOfAnotherDocumentDoesNotCount() {
            earlierRead("getDocument", "{\"id\":7,\"descriptionVersion\":3}");

            assertThatThrownBy(() -> function.updateDocument(context, DOC_ID, null, "new"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aFailedReadIsPlainTextAndDoesNotCount() {
            earlierRead("getDocument", "Документ id=42 не найден.");

            assertThatThrownBy(() -> function.updateDocument(context, DOC_ID, null, "new"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void anOutlineIsNotAReadOfTheWholeContent() {
            earlierRead("getDocumentOutline", "{\"id\":42,\"descriptionVersion\":3}");

            assertThatThrownBy(() -> function.updateDocument(context, DOC_ID, null, "new"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void anEarlierSectionReadCoversThatSectionOnly() {
            earlierRead(
                    "getDocumentSection",
                    "{\"id\":42,\"path\":\"Гайд > Установка\",\"descriptionVersion\":3}");

            assertThatCode(
                            () ->
                                    function.updateDocumentSection(
                                            context,
                                            DOC_ID,
                                            "Гайд > Установка",
                                            "## Установка\nnew\n",
                                            CURRENT_VERSION))
                    .doesNotThrowAnyException();
            assertThatThrownBy(
                            () ->
                                    function.deleteDocumentSection(
                                            context, DOC_ID, "Гайд", CURRENT_VERSION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("НЕ изменена");
        }

        @Test
        void anEarlierOutlineCoversAStructureChange() {
            earlierRead("getDocumentOutline", "{\"id\":42,\"descriptionVersion\":3}");

            assertThatCode(
                            () ->
                                    function.insertDocumentSection(
                                            context,
                                            DOC_ID,
                                            "Гайд > Установка",
                                            DocumentFunction.InsertPosition.AFTER,
                                            "## FAQ\nq\n",
                                            CURRENT_VERSION))
                    .doesNotThrowAnyException();
        }

        @Test
        void withoutAChatInTheContextOnlyThisResponseCounts() {
            earlierRead("getDocument", "{\"id\":42,\"descriptionVersion\":3}");
            ToolContext noChat = new ToolContext(Map.of(ToolInvocationCollector.KEY, collector));

            assertThatThrownBy(() -> function.updateDocument(noChat, DOC_ID, null, "new"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class RetryOfARefusedWrite {

        private String ref(int index) {
            return collector.callRef(index);
        }

        private ToolCallback recorded(String name) {
            ToolCallback callback =
                    Stream.of(ToolCallbacks.from(function))
                            .filter(cb -> name.equals(cb.getToolDefinition().name()))
                            .findFirst()
                            .orElseThrow();
            return new RecordingToolCallback(callback);
        }

        private void recordRead() {
            collector.record(
                    new ToolInvocation(
                            "getDocument",
                            Map.of("documentId", DOC_ID),
                            OK,
                            null,
                            null,
                            null,
                            "{\"documentId\":42}",
                            null,
                            collector.nextCallIndex(),
                            null));
        }

        @Test
        void theRefusalNamesItsCallRefAndTheRetryReplaysTheSameArguments() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocument")
                                            .call(
                                                    "{\"documentId\":42,\"description\":\"long text\"}",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("retryDocumentWrite(callRef=\"" + ref(0) + "\")");
            verify(documentService, never()).update(anyLong(), any());

            recordRead();
            DocumentShort result = function.retryDocumentWrite(context, ref(0));

            assertThat(result.id()).isEqualTo(DOC_ID);
            verify(documentService)
                    .update(
                            anyLong(),
                            argThat(
                                    (UpdateDocumentRequest req) ->
                                            "long text".equals(req.getDescription())
                                                    && req.getTitle() == null));
        }

        @Test
        void theRetryStillNeedsTheRead() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocument")
                                            .call(
                                                    "{\"documentId\":42,\"description\":\"x\"}",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class);

            assertThatThrownBy(() -> function.retryDocumentWrite(context, ref(0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getDocument");
            verify(documentService, never()).update(anyLong(), any());
        }

        @Test
        void aSectionWriteIsReplayedWithItsVersion() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocumentSection")
                                            .call(
                                                    """
                                                    {"documentId":42,"sectionPath":"Гайд > Установка",\
                                                    "newContent":"## Установка\\nnew\\n",\
                                                    "expectedDescriptionVersion":3}""",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("callRef=\"" + ref(0) + "\"");

            recordRead();
            function.retryDocumentWrite(context, ref(0));

            verify(documentService).patchDescription(anyLong(), eq(3), any());
        }

        @Test
        void aRefusedRetryPointsBackToTheOriginalWrite() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocument")
                                            .call(
                                                    "{\"documentId\":42,\"description\":\"x\"}",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class);
            // Retried before reading: refused again, and this refusal names the retry's own call.
            assertThatThrownBy(
                            () ->
                                    recorded("retryDocumentWrite")
                                            .call("{\"callRef\":\"" + ref(0) + "\"}", context))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("retryDocumentWrite(callRef=\"" + ref(1) + "\")");

            recordRead();
            function.retryDocumentWrite(context, ref(1));

            verify(documentService)
                    .update(
                            anyLong(),
                            argThat(
                                    (UpdateDocumentRequest req) ->
                                            "x".equals(req.getDescription())));
        }

        @Test
        void anAppliedRetryCannotBeAppliedAgain() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocument")
                                            .call(
                                                    "{\"documentId\":42,\"description\":\"old\"}",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class);
            recordRead();
            // Through the recording wrapper, so the successful replay is in the collector.
            recorded("retryDocumentWrite").call("{\"callRef\":\"" + ref(0) + "\"}", context);

            assertThatThrownBy(() -> function.retryDocumentWrite(context, ref(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already been applied");
            verify(documentService, times(1)).update(anyLong(), any());
        }

        @Test
        void aCallRefOfAnotherResponseDoesNotReachThisOne() {
            assertThatThrownBy(
                            () ->
                                    recorded("updateDocument")
                                            .call(
                                                    "{\"documentId\":42,\"description\":\"x\"}",
                                                    context))
                    .isInstanceOf(ToolExecutionException.class);
            recordRead();
            String earlierTurnRef = new ToolInvocationCollector().callRef(0);
            // Collision of the random run tags is 1 in ~1.6M; the assertion would then be vacuous.
            org.junit.jupiter.api.Assumptions.assumeFalse(earlierTurnRef.equals(ref(0)));

            assertThatThrownBy(() -> function.retryDocumentWrite(context, earlierTurnRef))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("earlier turn");
            assertThatThrownBy(() -> function.retryDocumentWrite(context, "0"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(documentService, never()).update(anyLong(), any());
        }

        @Test
        void onlyARefusedWriteOfThisResponseCanBeRetried() {
            recordRead();

            assertThatThrownBy(() -> function.retryDocumentWrite(context, ref(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callRef=" + ref(0));
            assertThatThrownBy(() -> function.retryDocumentWrite(context, ref(99)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
