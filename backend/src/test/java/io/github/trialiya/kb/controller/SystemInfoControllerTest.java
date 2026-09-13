package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

/**
 * The admin panel reports the datasource URL so one environment can be told from another. A JDBC
 * URL may carry credentials, and this is the only thing standing between them and the panel — the
 * rest of the response is assembled from properties that hold no secrets.
 *
 * <p>The build section is checked here too, for the case the build cannot produce: a run with no
 * {@code build-info.properties} and no {@code git.properties} on the classpath.
 */
class SystemInfoControllerTest {

    @ParameterizedTest
    @CsvSource({
        // Postgres, credentials as query parameters — everything after '?' goes.
        "jdbc:postgresql://localhost:5432/kb?user=admin&password=hunter2,"
                + "jdbc:postgresql://localhost:5432/kb",
        // Credentials in the authority — the user:password@ prefix goes, the host stays.
        "jdbc:postgresql://admin:hunter2@db.internal:5432/kb,jdbc:postgresql://db.internal:5432/kb",
        // H2 uses ';' for its settings, and PASSWORD= can hide among them.
        "jdbc:h2:./local-db/h2;MODE=PostgreSQL;PASSWORD=hunter2,jdbc:h2:./local-db/h2",
        // Nothing to strip — the URL is passed through unchanged.
        "jdbc:postgresql://localhost:5432/knowledgebase,jdbc:postgresql://localhost:5432/knowledgebase",
        // Unset datasource (an empty @Value default) stays empty rather than becoming garbage.
        "'',''"
    })
    @DisplayName("sanitizeJdbcUrl strips credentials and keeps the host/database")
    void stripsCredentials(String raw, String expected) {
        assertThat(SystemInfoController.sanitizeJdbcUrl(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("build info is empty, not invented, when the build left no metadata")
    void buildInfoWithoutMetadata() {
        var build = SystemInfoController.buildInfo(null, null);

        assertThat(build.version()).isNull();
        assertThat(build.builtAt()).isNull();
        assertThat(build.commit()).isNull();
        assertThat(build.branch()).isNull();
        assertThat(build.commitTime()).isNull();
        assertThat(build.dirty()).isNull();
    }

    @Test
    @DisplayName("build info is read from build-info.properties and git.properties")
    void buildInfoFromBothSources() {
        var buildEntries = new Properties();
        buildEntries.setProperty("version", "1.0.0-RC1");
        buildEntries.setProperty("time", "2026-09-13T11:00:28Z");
        // Keys without the "git." prefix: the file has it, but it is stripped on the way in —
        // ProjectInfoAutoConfiguration loads git.properties with that prefix as the namespace, and
        // GitProperties itself knows nothing about it.
        var gitEntries = new Properties();
        gitEntries.setProperty("branch", "main");
        gitEntries.setProperty("commit.id", "15203dcd3738485ac70cb1f0a8807f90e6004b1f");
        gitEntries.setProperty("commit.id.abbrev", "15203dc");
        gitEntries.setProperty("commit.time", "2026-09-13T10:41:34+0000");
        gitEntries.setProperty("dirty", "true");

        var build =
                SystemInfoController.buildInfo(
                        new BuildProperties(buildEntries), new GitProperties(gitEntries));

        assertThat(build.version()).isEqualTo("1.0.0-RC1");
        assertThat(build.builtAt()).isEqualTo("2026-09-13T11:00:28Z");
        assertThat(build.commit()).isEqualTo("15203dc");
        assertThat(build.branch()).isEqualTo("main");
        assertThat(build.commitTime()).isEqualTo("2026-09-13T10:41:34Z");
        assertThat(build.dirty()).isTrue();
    }

    @Test
    @DisplayName("an absent git.dirty stays unknown rather than becoming 'clean'")
    void dirtyIsUnknownWhenNotWrittenDown() {
        var gitEntries = new Properties();
        gitEntries.setProperty("commit.id", "15203dcd3738485ac70cb1f0a8807f90e6004b1f");

        var build = SystemInfoController.buildInfo(null, new GitProperties(gitEntries));

        assertThat(build.dirty()).isNull();
        // No commit.id.abbrev among the entries — GitProperties shortens the full id itself.
        assertThat(build.commit()).isEqualTo("15203dc");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://localhost:5432/kb?user=admin&password=hunter2",
                "jdbc:postgresql://admin:hunter2@db.internal:5432/kb",
                "jdbc:h2:./local-db/h2;MODE=PostgreSQL;PASSWORD=hunter2",
                "jdbc:mysql://root:s3cr3t@10.0.0.5/kb?serverTimezone=UTC"
            })
    @DisplayName("no password survives sanitizing, whatever shape the URL has")
    void neverLeaksASecret(String raw) {
        assertThat(SystemInfoController.sanitizeJdbcUrl(raw))
                .doesNotContain("hunter2")
                .doesNotContain("s3cr3t")
                .doesNotContain("password")
                .doesNotContain("PASSWORD");
    }
}
