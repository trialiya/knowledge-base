package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.config.model.ServerEnvironment;
import io.github.trialiya.kb.service.GitService;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of how the server itself is wired — profile, database, indexed repository, export
 * folder and the embedding queue's tuning knobs — for the Admin panel. The AI-side configuration
 * lives in {@link SettingsController} instead; the split follows the panels: Settings answers "how
 * does the assistant think", Admin answers "how is this server set up".
 *
 * <p>Same rule as {@link SettingsController}: fields are assembled one by one and secrets are never
 * among them. Every value comes from a bound properties record — {@link ServerEnvironment} for the
 * Spring-owned settings, {@link GitProperties} and {@link EmbeddingConfiguration} for the {@code
 * kb.*} ones — and none of those has a field for a password or an API key. The datasource URL is
 * passed through {@link #sanitizeJdbcUrl} because a JDBC URL may carry {@code user}/{@code
 * password} query parameters of its own.
 */
@RestController
@RequestMapping("/api/admin/system")
@Slf4j
public class SystemInfoController {

    private final ServerEnvironment environment;
    private final DocumentsConfiguration documentsConfiguration;
    private final EmbeddingConfiguration embeddingConfiguration;
    private final SecurityProperties securityProperties;
    private final GitProperties gitProperties;
    private final GitService gitService;
    @Nullable private final Flyway flyway;

    private volatile boolean schemaVersionResolved;
    @Nullable private String cachedSchemaVersion;

    public SystemInfoController(
            ServerEnvironment environment,
            DocumentsConfiguration documentsConfiguration,
            EmbeddingConfiguration embeddingConfiguration,
            SecurityProperties securityProperties,
            GitProperties gitProperties,
            GitService gitService,
            @Nullable Flyway flyway) {
        this.environment = environment;
        this.documentsConfiguration = documentsConfiguration;
        this.embeddingConfiguration = embeddingConfiguration;
        this.securityProperties = securityProperties;
        this.gitProperties = gitProperties;
        this.gitService = gitService;
        this.flyway = flyway;
    }

    @GetMapping
    public SystemInfoResponse getSystemInfo() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        return new SystemInfoResponse(
                new ApplicationInfo(
                        environment.applicationName(),
                        environment.profiles(),
                        environment.port(),
                        System.getProperty("java.version"),
                        Instant.ofEpochMilli(runtime.getStartTime()).toString(),
                        runtime.getUptime() / 1000),
                new DatabaseInfo(
                        sanitizeJdbcUrl(environment.datasourceUrl()),
                        environment.datasourceDriver(),
                        environment.datasourceUsername(),
                        environment.flywayLocations(),
                        schemaVersion()),
                new GitInfo(
                        gitService.repoPath().toString(),
                        gitProperties.editEnabled(),
                        gitService.isRepoWritable()),
                new DocumentsInfo(
                        documentsConfiguration.exportPath(), documentsConfiguration.replace()),
                new SecurityInfo(securityProperties.username()),
                new IndexingInfo(
                        embeddingConfiguration.workers(),
                        embeddingConfiguration.pollBatchSize(),
                        embeddingConfiguration.pollIntervalMs(),
                        embeddingConfiguration.maxAttempts(),
                        embeddingConfiguration.retryBackoffSeconds(),
                        embeddingConfiguration.stuckTimeoutMinutes(),
                        embeddingConfiguration.stuckCheckMs(),
                        embeddingConfiguration.cleanupRetentionDays(),
                        embeddingConfiguration.cache().enabled(),
                        embeddingConfiguration.cache().ttlDays(),
                        embeddingConfiguration.cache().cleanupCron()));
    }

    /**
     * Current schema version according to Flyway. Migrations only run once, at startup, so the
     * result never changes for the life of the process; reading the schema history table is a DB
     * round-trip, so the first successful read is cached instead of repeating it on every panel
     * load. A failure here must not take the whole panel down, hence the broad catch — it also
     * leaves the value unresolved so the next request tries again.
     */
    private @Nullable String schemaVersion() {
        if (schemaVersionResolved) {
            return cachedSchemaVersion;
        }
        if (flyway == null) {
            return null;
        }
        try {
            MigrationInfo current = flyway.info().current();
            cachedSchemaVersion =
                    current == null || current.getVersion() == null
                            ? null
                            : current.getVersion().toString();
            schemaVersionResolved = true;
        } catch (RuntimeException e) {
            log.debug("Could not read the Flyway schema version", e);
        }
        return cachedSchemaVersion;
    }

    /**
     * Strips credentials from a JDBC URL: everything after {@code ?} (which may carry {@code
     * user=}/{@code password=}) and any {@code user:password@} authority prefix. What is left is
     * the host, port and database name — enough to tell Postgres from H2 and one environment from
     * another, with nothing worth hiding.
     */
    static String sanitizeJdbcUrl(String url) {
        if (url.isBlank()) {
            return "";
        }
        String withoutQuery = url.split("[?;]", 2)[0];
        int authority = withoutQuery.indexOf("//");
        int at = withoutQuery.lastIndexOf('@');
        if (authority >= 0 && at > authority) {
            return withoutQuery.substring(0, authority + 2) + withoutQuery.substring(at + 1);
        }
        return withoutQuery;
    }

    public record SystemInfoResponse(
            ApplicationInfo application,
            DatabaseInfo database,
            GitInfo git,
            DocumentsInfo documents,
            SecurityInfo security,
            IndexingInfo indexing) {}

    public record ApplicationInfo(
            String name,
            List<String> profiles,
            int port,
            String javaVersion,
            String startedAt,
            long uptimeSeconds) {}

    public record DatabaseInfo(
            String url,
            String driver,
            String username,
            String flywayLocations,
            @Nullable String schemaVersion) {}

    /**
     * @param editActive is not reported here — it is AI-side and lives in {@code /ai-config}.
     */
    public record GitInfo(String projectPath, boolean editEnabled, boolean writable) {}

    public record DocumentsInfo(String exportPath, boolean replace) {}

    public record SecurityInfo(String username) {}

    /** Tuning knobs of the background embedding queue ({@code kb.embedding.*}). */
    public record IndexingInfo(
            int workers,
            int pollBatchSize,
            long pollIntervalMs,
            int maxAttempts,
            int retryBackoffSeconds,
            int stuckTimeoutMinutes,
            long stuckCheckMs,
            int cleanupRetentionDays,
            boolean cacheEnabled,
            int cacheTtlDays,
            String cacheCleanupCron) {}
}
