package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DocumentImportService}. {@link DocumentService} is mocked: {@code create}
 * assigns sequential ids and records the requests; {@code update} records the reverse-link rewrites.
 *
 * <pre>
 *   base/.index.md          → Docs, then Top
 *   base/docs/.content.md   → "Folder body [Top](../top.md)"
 *   base/docs/.index.md     → Intro
 *   base/docs/intro.md      → "Intro [Top](../top.md)"
 *   base/top.md             → "Top body"
 * </pre>
 */
class DocumentImportServiceTest {

    @TempDir Path base;

    private final List<CreateDocumentRequest> created = new ArrayList<>();
    private final Map<Long, String> updated = new HashMap<>();

    private DocumentImportService newService() {
        DocumentService docService = mock(DocumentService.class);
        AtomicLong seq = new AtomicLong();

        when(docService.create(any()))
                .thenAnswer(
                        inv -> {
                            CreateDocumentRequest r = inv.getArgument(0);
                            created.add(r);
                            long id = seq.incrementAndGet();
                            return new Document(
                                    id, r.getTitle(), r.getType(), r.getParentId(), 1, 1, null, null,
                                    null, null, false, null);
                        });
        when(docService.update(anyLong(), any()))
                .thenAnswer(
                        inv -> {
                            long id = inv.getArgument(0);
                            UpdateDocumentRequest r = inv.getArgument(1);
                            updated.put(id, r.getDescription());
                            return null;
                        });

        return new DocumentImportService(docService, new DocumentsConfiguration(base.toString(), true));
    }

    private void writeFixture() throws Exception {
        Files.writeString(base.resolve(".index.md"), "- [Docs](docs/.content.md)\n- [Top](top.md)\n");
        Path docs = Files.createDirectory(base.resolve("docs"));
        Files.writeString(docs.resolve(".content.md"), "Folder body [Top](../top.md)");
        Files.writeString(docs.resolve(".index.md"), "- [Intro](intro.md)\n");
        Files.writeString(docs.resolve("intro.md"), "Intro [Top](../top.md)");
        Files.writeString(base.resolve("top.md"), "Top body");
    }

    @Test
    void importsTreePreservingHierarchyOrderAndTitles() throws Exception {
        writeFixture();

        DocumentImportService.ImportResult result = newService().importFromFolder(null);

        assertThat(result.created()).isEqualTo(3);
        assertThat(result.folders()).isEqualTo(1);
        assertThat(result.documents()).isEqualTo(2);

        // Creation order: folder Docs (root) → Intro (child of Docs) → Top (root).
        assertThat(created).hasSize(3);
        assertThat(created.get(0).getTitle()).isEqualTo("Docs");
        assertThat(created.get(0).getType()).isEqualTo("folder");
        assertThat(created.get(0).getParentId()).isNull();

        assertThat(created.get(1).getTitle()).isEqualTo("Intro");
        assertThat(created.get(1).getType()).isEqualTo("document");
        assertThat(created.get(1).getParentId()).isEqualTo(1L); // Docs got id=1

        assertThat(created.get(2).getTitle()).isEqualTo("Top");
        assertThat(created.get(2).getParentId()).isNull();
    }

    @Test
    void rewritesRelativeLinksBackToInternalDocLinks() throws Exception {
        writeFixture();

        newService().importFromFolder(null);

        // Top is created last with id=3; links pointing at ../top.md become /?doc=3.
        assertThat(updated.get(1L)).contains("/?doc=3"); // folder Docs body
        assertThat(updated.get(2L)).contains("/?doc=3"); // Intro body
        assertThat(updated).doesNotContainKey(3L); // Top has no links → no rewrite
    }

    @Test
    void importsUnderGivenParent() throws Exception {
        writeFixture();

        newService().importFromFolder(42L);

        // Top-level imported nodes are parented to 42.
        assertThat(created.get(0).getParentId()).isEqualTo(42L); // Docs
        assertThat(created.get(2).getParentId()).isEqualTo(42L); // Top
    }

    @Test
    void missingFolderThrows() {
        DocumentService docService = mock(DocumentService.class);
        DocumentImportService service =
                new DocumentImportService(
                        docService, new DocumentsConfiguration(base.resolve("nope").toString(), true));

        assertThatThrownBy(() -> service.importFromFolder(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
