package io.github.trialiya.kb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The structural projection queries against a real schema.
 *
 * <p>They exist because {@link DocumentTreeRow} is not the aggregate root: the mapping from columns
 * to record components, the {@code DocumentType} converter and {@code IS NOT DISTINCT FROM} for the
 * root level are all things a mocked repository would happily pretend to do. Export, subtree
 * download and sync all walk the tree through these three queries, so a silent mapping failure here
 * would take every one of them with it.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-tree-row-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({CommonConfig.class})
class DocumentTreeRowQueryTest {

    @Autowired private DocumentRepository repo;

    private long folderId;
    private long docId;

    @BeforeEach
    void setUp() {
        folderId = save("Обзор проекта!", null, DocumentType.FOLDER, 0, null).getId();
        docId = save("Введение", folderId, DocumentType.DOCUMENT, 0, "Тело документа").getId();
        save("Архитектура", folderId, DocumentType.DOCUMENT, 1, null);
    }

    @Test
    void mapsEveryColumnOfALevel() {
        List<DocumentTreeRow> level = repo.findTreeRowsByParent(folderId);

        assertThat(level)
                .extracting(DocumentTreeRow::title)
                .containsExactly("Введение", "Архитектура");
        DocumentTreeRow first = level.getFirst();
        assertThat(first.id()).isEqualTo(docId);
        assertThat(first.parentId()).isEqualTo(folderId);
        assertThat(first.type()).isEqualTo(DocumentType.DOCUMENT);
        assertThat(first.position()).isZero();
        assertThat(first.isSystem()).isFalse();
        assertThat(first.updatedAt()).isNotNull();
    }

    @Test
    void nullParentSelectsTheRootLevel() {
        assertThat(repo.findTreeRowsByParent(null))
                .extracting(DocumentTreeRow::id)
                .contains(folderId);
        assertThat(repo.findTreeRowsByParent(null))
                .extracting(DocumentTreeRow::type)
                .contains(DocumentType.FOLDER);
    }

    @Test
    void readsOneNodeAndOneBodyOnTheirOwn() {
        assertThat(repo.findTreeRowById(folderId))
                .get()
                .extracting(DocumentTreeRow::type)
                .isEqualTo(DocumentType.FOLDER);
        assertThat(repo.findDescriptionById(docId)).contains("Тело документа");
        assertThat(repo.findTreeRowById(-1)).isEmpty();
    }

    @Test
    void maxPositionCoversBothALevelAndAnEmptyOne() {
        assertThat(repo.findMaxPosition(folderId)).isEqualTo(1);
        // An empty level answers -1, so "next position" is 0 without a special case.
        assertThat(repo.findMaxPosition(docId)).isEqualTo(-1);
    }

    private DocumentEntity save(
            String title, Long parentId, DocumentType type, int position, String description) {
        DocumentEntity entity = new DocumentEntity();
        entity.setTitle(title);
        entity.setType(type);
        entity.setParentId(parentId);
        entity.setDescription(description);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setPosition(position);
        entity.setDescriptionVersion(1);
        return repo.save(entity);
    }
}
