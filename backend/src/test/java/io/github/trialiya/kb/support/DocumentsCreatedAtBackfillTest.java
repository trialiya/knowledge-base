package io.github.trialiya.kb.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for {@code V2026.07.27_00__documents_created_at.sql} — the migration that
 * backfills {@code documents.created_at} for rows that predate the column.
 *
 * <p>{@link SampleDataFixtureTest} and {@code PostgresDocumentIT} both run the full migration
 * chain, but only against an empty {@code documents} table (fixture rows are inserted afterwards,
 * already carrying an explicit {@code created_at}), so the backfill {@code UPDATE} itself is a
 * no-op there — exactly the part most likely to have a subtle bug. This test runs the migrations in
 * two phases against a real H2 database (mirroring how the migration behaves in a deployed
 * environment with existing data): everything up to the migration right before this one, then rows
 * simulating pre-existing documents, then this migration on top.
 */
class DocumentsCreatedAtBackfillTest {

    private static final String MIGRATION_TO_TEST = "V2026.07.27_00__documents_created_at.sql";

    // V2026.06.20_00 needs this placeholder (bound to kb.security.username in application.yaml,
    // "admin" by default) — irrelevant to what this test checks, but required for the migration
    // chain to parse at all.
    private static final Map<String, String> FLYWAY_PLACEHOLDERS = Map.of("default_user", "admin");

    @TempDir private Path earlierMigrationsDir;

    // Unique per test run so re-runs / parallel forks never share an in-memory instance.
    private final String jdbcUrl =
            "jdbc:h2:mem:created-at-backfill-"
                    + System.nanoTime()
                    + ";MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void backfillsFromTheEarliestHistorySnapshotRatherThanTheLastEdit() throws Exception {
        // ── Phase 1: every migration OLDER than the one under test ──────────────────
        copyMigrationsExcept(MIGRATION_TO_TEST, earlierMigrationsDir);
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("filesystem:" + earlierMigrationsDir)
                .placeholders(FLYWAY_PLACEHOLDERS)
                .load()
                .migrate();

        // ── Seed data as it would exist right before this migration ever ran ────────
        //   doc 1: created 2026-01-01, edited since — updated_at now sits on the LAST edit
        //          (2026-06-01), not the creation date. Its earliest history row (version 2,
        //          since V2026.06.01__fix_document_history.sql renumbers everything down by
        //          one) still has the true creation timestamp.
        //   doc 2: created and never edited since — history has exactly one snapshot.
        //   doc 3: no history row at all (defensive fallback path — e.g. a system document
        //          seeded straight into `documents` that was never routed through
        //          DocumentService.create()/update()).
        try (Statement st = connection.createStatement()) {
            st.execute(
                    "INSERT INTO documents (id, title, type, updated_at, position, version) VALUES"
                            + " (1, 'edited since creation', 'document', '2026-06-01 00:00:00+00', 0, 2),"
                            + " (2, 'never edited', 'document', '2026-03-15 12:00:00+00', 1, 1),"
                            + " (3, 'no history row', 'document', '2026-04-01 00:00:00+00', 2, 1)");
            st.execute(
                    "INSERT INTO document_history (id, document_id, version, title, type, updated_at) VALUES"
                            + " (10, 1, 2, 'edited since creation', 'document', '2026-01-01 00:00:00+00'),"
                            + " (11, 1, 3, 'edited since creation', 'document', '2026-06-01 00:00:00+00'),"
                            + " (20, 2, 1, 'never edited', 'document', '2026-03-15 12:00:00+00')");
        }

        // ── Phase 2: the real migration chain, including the migration under test ───
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration-h2")
                .placeholders(FLYWAY_PLACEHOLDERS)
                .load()
                .migrate();

        assertThat(createdAtOf(1))
                .isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(createdAtOf(2))
                .isEqualTo(OffsetDateTime.of(2026, 3, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        // No history at all: falls back to updated_at rather than failing the backfill.
        assertThat(createdAtOf(3))
                .isEqualTo(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        // The point of the whole migration: doc 1's creation date must NOT be its last edit.
        assertThat(createdAtOf(1)).isNotEqualTo(updatedAtOf(1));
    }

    private OffsetDateTime createdAtOf(long documentId) throws Exception {
        return dateColumnOf(documentId, "created_at");
    }

    private OffsetDateTime updatedAtOf(long documentId) throws Exception {
        return dateColumnOf(documentId, "updated_at");
    }

    private OffsetDateTime dateColumnOf(long documentId, String column) throws Exception {
        try (Statement st = connection.createStatement();
                ResultSet rs =
                        st.executeQuery(
                                "SELECT " + column + " FROM documents WHERE id = " + documentId)) {
            assertThat(rs.next()).as("document " + documentId + " exists").isTrue();
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

    /**
     * Copies every {@code db/migration-h2} classpath resource whose filename precedes {@code
     * exclude}.
     */
    private static void copyMigrationsExcept(String exclude, Path targetDir) throws IOException {
        for (String name : migrationFileNames().toList()) {
            if (name.compareTo(exclude) >= 0) continue;
            try (InputStream in =
                    DocumentsCreatedAtBackfillTest.class.getResourceAsStream(
                            "/db/migration-h2/" + name)) {
                Files.copy(in, targetDir.resolve(name));
            }
        }
    }

    /**
     * Lists {@code db/migration-h2} migration filenames sorted lexicographically (which matches
     * Flyway's own version ordering for this project's {@code V<date>_<seq>__} naming scheme).
     * Reads the directory off the filesystem via the test classes' own location rather than
     * scanning the packaged classpath, since the resources are copied there unmodified.
     */
    private static Stream<String> migrationFileNames() throws IOException {
        Path dir;
        try {
            dir =
                    Path.of(
                            DocumentsCreatedAtBackfillTest.class
                                    .getResource("/db/migration-h2")
                                    .toURI());
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList()
                    .stream();
        }
    }
}
