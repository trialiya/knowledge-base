package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentHistoryRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Integration tests for {@link DocumentService#move}.
 *
 * <p>Runs against in-memory H2 in PostgreSQL mode with the real {@code db/migration-h2} Flyway
 * schema, so the windowed shift queries ({@code shiftWindowUp} / {@code shiftWindowDown} / {@code
 * shiftPositionsFrom}, incl. {@code IS NOT DISTINCT FROM}) are executed for real — that is the
 * whole point of these tests, so the repository is NOT mocked.
 *
 * <p>Migrations may seed system/root rows, therefore every reorder scenario runs inside a folder
 * created by the test itself; root-level assertions are relative (max position), never absolute.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-move-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class})
class DocumentServiceUnitTest {

    @Autowired private DocumentRepository repo;
    @Autowired private DocumentHistoryRepository historyRepo;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        // move/moveToParent never touch summarisation, semantic search or search config —
        // mocks/null keep the test slice free of AI infrastructure.
        service =
                new DocumentService(
                        repo,
                        historyRepo,
                        mock(DocumentSummaryService.class),
                        mock(SemanticSearchService.class),
                        null);
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private DocumentEntity folder(String title, Long parentId, int position) {
        return save(title, "folder", parentId, position, false);
    }

    private DocumentEntity doc(String title, Long parentId, int position) {
        return save(title, "document", parentId, position, false);
    }

    private DocumentEntity systemDoc(String title, Long parentId, int position) {
        return save(title, "document", parentId, position, true);
    }

    private DocumentEntity save(
            String title, String type, Long parentId, int position, boolean system) {
        return repo.save(
                new DocumentEntity(
                        null,
                        title,
                        type,
                        parentId,
                        null,
                        LocalDateTime.now(),
                        position,
                        system,
                        0,
                        null,
                        null,
                        1));
    }

    /** Sibling ids of a level in display order (position asc). */
    private List<Long> orderIn(Long parentId) {
        List<DocumentEntity> list =
                parentId == null ? repo.findRoots() : repo.findByParentId(parentId);
        return list.stream().map(DocumentEntity::getId).toList();
    }

    /** No two siblings of the level may share a position (the core windowed-shift invariant). */
    private void assertUniquePositions(Long parentId) {
        List<DocumentEntity> list =
                parentId == null ? repo.findRoots() : repo.findByParentId(parentId);
        Set<Integer> seen = new HashSet<>();
        for (DocumentEntity e : list) {
            assertThat(seen.add(e.getPosition()))
                    .as("duplicate position %s in level %s", e.getPosition(), parentId)
                    .isTrue();
        }
    }

    private int positionOf(long id) {
        return repo.findById(id).orElseThrow().getPosition();
    }

    private int versionOf(long id) {
        return repo.findById(id).orElseThrow().getVersion();
    }

    private static HttpStatus statusOf(Throwable t) {
        return HttpStatus.valueOf(((ResponseStatusException) t).getStatusCode().value());
    }

    // ── move: reorder within the same level (windowed shift) ─────────────────

    @Nested
    class MoveWithinSameLevel {

        private DocumentEntity home;
        private DocumentEntity a, b, c, d;

        @BeforeEach
        void seedLevel() {
            home = folder("home", null, 0);
            a = doc("a", home.getId(), 0);
            b = doc("b", home.getId(), 1);
            c = doc("c", home.getId(), 2);
            d = doc("d", home.getId(), 3);
        }

        @Test
        void movesUpRightAfterAnchor() {
            // [a b c d] → d after a → [a d b c]
            service.move(d.getId(), home.getId(), a.getId());

            assertThat(orderIn(home.getId()))
                    .containsExactly(a.getId(), d.getId(), b.getId(), c.getId());
            assertUniquePositions(home.getId());
        }

        @Test
        void movesDownRightAfterAnchor_anchorEndsRightBefore() {
            // [a b c d] → a after c → [b c a d]
            service.move(a.getId(), home.getId(), c.getId());

            assertThat(orderIn(home.getId()))
                    .containsExactly(b.getId(), c.getId(), a.getId(), d.getId());
            // the -1 window pulled the anchor one step up; the moved node took its old slot
            assertThat(positionOf(c.getId())).isEqualTo(positionOf(a.getId()) - 1);
            assertUniquePositions(home.getId());
        }

        @Test
        void insertsFirstWhenAfterIdIsNull() {
            // [a b c d] → c first → [c a b d]
            service.move(c.getId(), home.getId(), null);

            assertThat(orderIn(home.getId()))
                    .containsExactly(c.getId(), a.getId(), b.getId(), d.getId());
            assertUniquePositions(home.getId());
        }

        @Test
        void insertFirstIsNoOpWhenAlreadyFirst() {
            int versionBefore = versionOf(a.getId());

            service.move(a.getId(), home.getId(), null);

            assertThat(orderIn(home.getId()))
                    .containsExactly(a.getId(), b.getId(), c.getId(), d.getId());
            assertThat(versionOf(a.getId())).isEqualTo(versionBefore); // early return, no save
        }

        @Test
        void noOpWhenAlreadyRightAfterAnchor() {
            // dense positions: b is already exactly at a.position + 1
            int versionBefore = versionOf(b.getId());

            service.move(b.getId(), home.getId(), a.getId());

            assertThat(orderIn(home.getId()))
                    .containsExactly(a.getId(), b.getId(), c.getId(), d.getId());
            assertThat(versionOf(b.getId())).isEqualTo(versionBefore);
        }

        @Test
        void worksWithPositionGaps() {
            // Re-seed gappy positions: a=2, b=5, c=9, d=14 (window bounds are real
            // neighbour positions, not ordinal indexes — gaps must not break the shift).
            repo.updatePosition(a.getId(), 2);
            repo.updatePosition(b.getId(), 5);
            repo.updatePosition(c.getId(), 9);
            repo.updatePosition(d.getId(), 14);

            // up across a gap: c after a → [a c b d]
            service.move(c.getId(), home.getId(), a.getId());
            assertThat(orderIn(home.getId()))
                    .containsExactly(a.getId(), c.getId(), b.getId(), d.getId());
            assertUniquePositions(home.getId());

            // down across the level: a after d → [c b d a]
            service.move(a.getId(), home.getId(), d.getId());
            assertThat(orderIn(home.getId()))
                    .containsExactly(c.getId(), b.getId(), d.getId(), a.getId());
            assertUniquePositions(home.getId());
        }

        @Test
        void untouchedSiblingsOutsideWindowKeepPositions() {
            // moving c after a shifts only [a+1, c) — d (outside the window) must not move
            int dBefore = positionOf(d.getId());

            service.move(c.getId(), home.getId(), a.getId());

            assertThat(positionOf(d.getId())).isEqualTo(dBefore);
        }
    }

    // ── move: across levels ───────────────────────────────────────────────────

    @Nested
    class MoveAcrossLevels {

        @Test
        void movesIntoAnotherFolderRightAfterAnchor() {
            DocumentEntity src = folder("src", null, 0);
            DocumentEntity dst = folder("dst", null, 1);
            DocumentEntity moved = doc("moved", src.getId(), 0);
            DocumentEntity x = doc("x", dst.getId(), 0);
            DocumentEntity y = doc("y", dst.getId(), 1);

            Document dto = service.move(moved.getId(), dst.getId(), x.getId());

            assertThat(dto.parentId()).isEqualTo(dst.getId());
            assertThat(orderIn(dst.getId())).containsExactly(x.getId(), moved.getId(), y.getId());
            assertUniquePositions(dst.getId());
        }

        @Test
        void movesIntoAnotherFolderFirst() {
            DocumentEntity src = folder("src", null, 0);
            DocumentEntity dst = folder("dst", null, 1);
            DocumentEntity moved = doc("moved", src.getId(), 0);
            DocumentEntity x = doc("x", dst.getId(), 0);
            DocumentEntity y = doc("y", dst.getId(), 1);

            service.move(moved.getId(), dst.getId(), null);

            assertThat(orderIn(dst.getId())).containsExactly(moved.getId(), x.getId(), y.getId());
            assertUniquePositions(dst.getId());
        }

        @Test
        void movesToRootRightAfterAnchor() {
            DocumentEntity anchor = folder("anchor", null, 5);
            DocumentEntity src = folder("src", null, 6);
            DocumentEntity moved = doc("moved", src.getId(), 0);

            Document dto = service.move(moved.getId(), null, anchor.getId());

            assertThat(dto.parentId()).isNull();
            assertThat(positionOf(moved.getId())).isEqualTo(positionOf(anchor.getId()) + 1);
            assertUniquePositions(null);
        }

        @Test
        void sourceLevelKeepsRelativeOrderAfterDeparture() {
            DocumentEntity src = folder("src", null, 0);
            DocumentEntity dst = folder("dst", null, 1);
            DocumentEntity p = doc("p", src.getId(), 0);
            DocumentEntity moved = doc("moved", src.getId(), 1);
            DocumentEntity q = doc("q", src.getId(), 2);

            service.move(moved.getId(), dst.getId(), null);

            // the vacated slot stays as a gap — relative order of the rest must hold
            assertThat(orderIn(src.getId())).containsExactly(p.getId(), q.getId());
        }

        @Test
        void movedFolderKeepsItsOwnChildren() {
            DocumentEntity dst = folder("dst", null, 0);
            DocumentEntity movedFolder = folder("movedFolder", null, 1);
            DocumentEntity child = doc("child", movedFolder.getId(), 0);

            service.move(movedFolder.getId(), dst.getId(), null);

            assertThat(orderIn(movedFolder.getId())).containsExactly(child.getId());
        }
    }

    // ── move: validation ──────────────────────────────────────────────────────

    @Nested
    class MoveValidation {

        @Test
        void rejectsAfterIdEqualToNode() {
            DocumentEntity home = folder("home", null, 0);
            DocumentEntity a = doc("a", home.getId(), 0);

            assertThatThrownBy(() -> service.move(a.getId(), home.getId(), a.getId()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        void rejectsMissingAfterId() {
            DocumentEntity home = folder("home", null, 0);
            DocumentEntity a = doc("a", home.getId(), 0);

            assertThatThrownBy(() -> service.move(a.getId(), home.getId(), 999_999L))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void rejectsAfterIdFromAnotherLevel() {
            DocumentEntity home = folder("home", null, 0);
            DocumentEntity other = folder("other", null, 1);
            DocumentEntity a = doc("a", home.getId(), 0);
            DocumentEntity foreign = doc("foreign", other.getId(), 0);

            assertThatThrownBy(() -> service.move(a.getId(), home.getId(), foreign.getId()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(
                            t ->
                                    assertThat(statusOf(t))
                                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        void rejectsSystemNode() {
            DocumentEntity home = folder("home", null, 0);
            DocumentEntity sys = systemDoc("sys", home.getId(), 0);

            assertThatThrownBy(() -> service.move(sys.getId(), home.getId(), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        void rejectsNonFolderTarget() {
            DocumentEntity plain = doc("plain", null, 0);
            DocumentEntity moved = doc("moved", null, 1);

            assertThatThrownBy(() -> service.move(moved.getId(), plain.getId(), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(
                            t ->
                                    assertThat(statusOf(t))
                                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        void rejectsCycleIntoOwnDescendant() {
            DocumentEntity top = folder("top", null, 0);
            DocumentEntity mid = folder("mid", top.getId(), 0);

            assertThatThrownBy(() -> service.move(top.getId(), mid.getId(), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
}
