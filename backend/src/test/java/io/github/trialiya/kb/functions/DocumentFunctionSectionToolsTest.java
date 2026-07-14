package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.DocumentOutline;
import io.github.trialiya.kb.model.doc.dto.DocumentSection;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Markdown-section tools of {@link DocumentFunction}: outline/section reading, the section
 * read-before-write guard (mirrors the {@code updateDocument} guard, but is satisfied by {@code
 * getDocumentSection} of the same section or {@code getDocument} of the whole document), and the
 * server-side splice passed to {@link DocumentService#patchDescription}.
 */
class DocumentFunctionSectionToolsTest {

    private static final long DOC_ID = 42L;
    private static final String MD = "# Гайд\nintro\n## Установка\nold install\n## FAQ\nq&a\n";

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

        when(documentService.getById(DOC_ID)).thenReturn(node(MD));
    }

    private static DocumentNode node(String description) {
        return new DocumentNode(
                DOC_ID,
                "Гайд",
                "document",
                null,
                1,
                description,
                3,
                LocalDateTime.now(),
                List.of(),
                false,
                false,
                null,
                false,
                null);
    }

    private void record(String tool, Map<Object, Object> args, ToolInvocationStatus status) {
        collector.record(
                new ToolInvocation(
                        tool,
                        args,
                        status,
                        null,
                        null,
                        null,
                        "{}",
                        null,
                        collector.nextCallIndex()));
    }

    @Nested
    class ReadTools {

        @Test
        void outlineListsSectionsWithoutContent() {
            DocumentOutline outline = function.getDocumentOutline(String.valueOf(DOC_ID));

            assertThat(outline.id()).isEqualTo(DOC_ID);
            assertThat(outline.descriptionVersion()).isEqualTo(3);
            assertThat(outline.sections())
                    .extracting(DocumentOutline.OutlineSection::path)
                    .containsExactly("Гайд", "Гайд > Установка", "Гайд > FAQ");
        }

        @Test
        void sectionReturnsSubtreeContentAndVersion() {
            DocumentSection section =
                    function.getDocumentSection(String.valueOf(DOC_ID), "Гайд > Установка");

            assertThat(section.content()).isEqualTo("## Установка\nold install\n");
            assertThat(section.descriptionVersion()).isEqualTo(3);
        }

        @Test
        void unknownSectionFailsWithAvailablePaths() {
            assertThatThrownBy(() -> function.getDocumentSection(String.valueOf(DOC_ID), "Нет"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найдена")
                    .hasMessageContaining("Гайд > Установка");
        }

        @Test
        void missingDocumentFails() {
            when(documentService.getById(7L)).thenReturn(null);

            assertThatThrownBy(() -> function.getDocumentOutline("7"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найден");
        }
    }

    @Nested
    class UpdateGuard {

        @Test
        void updateWithoutPriorReadIsRejected() {
            assertThatThrownBy(() -> updateSection("## Установка\nnew"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("НЕ обновлена")
                    .hasMessageContaining("getDocumentSection");
            Mockito.verify(documentService, never()).patchDescription(anyLong(), anyInt(), any());
        }

        @Test
        void readingTheSameSectionSatisfiesTheGuard() {
            stubPatch();
            record(
                    "getDocumentSection",
                    Map.of("documentId", String.valueOf(DOC_ID), "sectionPath", "Гайд > Установка"),
                    ToolInvocationStatus.OK);

            assertThatCode(() -> updateSection("## Установка\nnew")).doesNotThrowAnyException();
        }

        @Test
        void readingTheWholeDocumentSatisfiesTheGuard() {
            stubPatch();
            record("getDocument", Map.of("documentId", DOC_ID), ToolInvocationStatus.OK);

            assertThatCode(() -> updateSection("## Установка\nnew")).doesNotThrowAnyException();
        }

        @Test
        void readingAnotherSectionDoesNotCount() {
            record(
                    "getDocumentSection",
                    Map.of("documentId", String.valueOf(DOC_ID), "sectionPath", "Гайд > FAQ"),
                    ToolInvocationStatus.OK);

            assertThatThrownBy(() -> updateSection("## Установка\nnew"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void failedReadDoesNotCount() {
            record(
                    "getDocumentSection",
                    Map.of("documentId", String.valueOf(DOC_ID), "sectionPath", "Гайд > Установка"),
                    ToolInvocationStatus.ERROR);

            assertThatThrownBy(() -> updateSection("## Установка\nnew"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void missingCollectorSkipsTheCheck() {
            stubPatch();
            ToolContext noCollector = new ToolContext(Map.of());

            assertThatCode(
                            () ->
                                    function.updateDocumentSection(
                                            noCollector,
                                            DOC_ID,
                                            "Гайд > Установка",
                                            "## Установка\nnew",
                                            3))
                    .doesNotThrowAnyException();
        }

        private void updateSection(String newContent) {
            function.updateDocumentSection(context, DOC_ID, "Гайд > Установка", newContent, 3);
        }
    }

    @Nested
    class UpdateSplice {

        @BeforeEach
        void allowUpdate() {
            record("getDocument", Map.of("documentId", DOC_ID), ToolInvocationStatus.OK);
        }

        @Test
        void splicesOnlyTheTargetSection() {
            AtomicReference<String> patched = stubPatch();

            function.updateDocumentSection(
                    context, DOC_ID, "Гайд > Установка", "## Установка\nnew install", 3);

            assertThat(patched.get())
                    .isEqualTo("# Гайд\nintro\n## Установка\nnew install\n\n## FAQ\nq&a\n");
        }

        @Test
        void sectionMissingAtPatchTimeFails() {
            stubPatch();

            assertThatThrownBy(
                            () ->
                                    function.updateDocumentSection(
                                            context, DOC_ID, "Гайд > Нет", "## Нет\nx", 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найдена");
        }

        @Test
        void blankContentIsRejected() {
            assertThatThrownBy(
                            () ->
                                    function.updateDocumentSection(
                                            context, DOC_ID, "Гайд > Установка", "  ", 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("пуст");
        }

        @Test
        void contentWithoutHeadingIsRejected() {
            assertThatThrownBy(
                            () ->
                                    function.updateDocumentSection(
                                            context,
                                            DOC_ID,
                                            "Гайд > Установка",
                                            "просто текст без заголовка",
                                            3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("заголовка");
        }

        @Test
        void preambleContentDoesNotNeedAHeading() {
            String md = "старое вступление\n\n# Гайд\ntext\n";
            when(documentService.getById(DOC_ID)).thenReturn(node(md));
            AtomicReference<String> patched = stubPatch(md);

            function.updateDocumentSection(context, DOC_ID, "_preamble", "новое вступление", 3);

            assertThat(patched.get()).isEqualTo("новое вступление\n\n# Гайд\ntext\n");
        }
    }

    /** Stubs patchDescription to run the splice against {@link #MD} and capture the result. */
    private AtomicReference<String> stubPatch() {
        return stubPatch(MD);
    }

    private AtomicReference<String> stubPatch(String currentDescription) {
        AtomicReference<String> patched = new AtomicReference<>();
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
        when(documentService.patchDescription(anyLong(), anyInt(), any()))
                .thenAnswer(
                        inv -> {
                            UnaryOperator<String> patch = inv.getArgument(2);
                            patched.set(patch.apply(currentDescription));
                            return document;
                        });
        return patched;
    }
}
