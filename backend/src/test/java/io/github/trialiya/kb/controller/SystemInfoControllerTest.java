package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The admin panel reports the datasource URL so one environment can be told from another. A JDBC
 * URL may carry credentials, and this is the only thing standing between them and the panel — the
 * rest of the response is assembled from properties that hold no secrets.
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
