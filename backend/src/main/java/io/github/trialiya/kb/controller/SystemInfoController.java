package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.service.GitService;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * among them. The datasource URL is passed through {@link #sanitizeJdbcUrl} because a JDBC URL may
 * carry {@code user}/{@code password} query parameters, and the password is never reported at all.
 */
@RestController
@RequestMapping("/api/admin/system")
@Slf4j
public class SystemInfoController {

    private final DocumentsConfiguration documentsConfiguration;
    private final EmbeddingConfiguration embeddingConfiguration;
    private final SecurityProperties securityProperties;
    private final GitService gitService;
    private final ObjectProvider<Flyway> flyway;
    private final String applicationName;
    private final String activeProfiles;
    private final int serverPort;
    private final String datasourceUrl;
    private final String datasourceDriver;
    private final String datasourceUsername;
    private final String flywayLocations;
    private final boolean gitEditEnabled;
    private final long pollIntervalMs;
    private final long stuckCheckMs;

    public SystemInfoController(
            DocumentsConfiguration documentsConfiguration,
            EmbeddingConfiguration embeddingConfiguration,
            SecurityProperties securityProperties,
            GitService gitService,
            ObjectProvider<Flyway> flyway,
            @Value("${spring.application.name:knowledge-base}") String applicationName,
            @Value("${spring.profiles.active:}") String activeProfiles,
            @Value("${server.port:8080}") int serverPort,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.driver-class-name:}") String datasourceDriver,
            @Value("${spring.datasource.username:}") String datasourceUsername,
            @Value("${spring.flyway.locations:}") String flywayLocations,
            @Value("${kb.git.edit-enabled:false}") boolean gitEditEnabled,
            @Value("${kb.embedding.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${kb.embedding.stuck-check-ms:300000}") long stuckCheckMs) {
        this.documentsConfiguration = documentsConfiguration;
        this.embeddingConfiguration = embeddingConfiguration;
        this.securityProperties = securityProperties;
        this.gitService = gitService;
        this.flyway = flyway;
        this.applicationName = applicationName;
        this.activeProfiles = activeProfiles;
        this.serverPort = serverPort;
        this.datasourceUrl = datasourceUrl;
        this.datasourceDriver = datasourceDriver;
        this.datasourceUsername = datasourceUsername;
        this.flywayLocations = flywayLocations;
        this.gitEditEnabled = gitEditEnabled;
        this.pollIntervalMs = pollIntervalMs;
        this.stuckCheckMs = stuckCheckMs;
    }

    @GetMapping
    public SystemInfoResponse getSystemInfo() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        return new SystemInfoResponse(
                new ApplicationInfo(
                        applicationName,
                        profiles(),
                        serverPort,
                        System.getProperty("java.version"),
                        Instant.ofEpochMilli(runtime.getStartTime()).toString(),
                        runtime.getUptime() / 1000),
                new DatabaseInfo(
                        sanitizeJdbcUrl(datasourceUrl),
                        datasourceDriver,
                        datasourceUsername,
                        flywayLocations,
                        schemaVersion()),
                new GitInfo(
                        gitService.repoPath().toString(),
                        gitEditEnabled,
                        gitService.isRepoWritable()),
                new DocumentsInfo(
                        documentsConfiguration.exportPath(), documentsConfiguration.replace()),
                new SecurityInfo(securityProperties.username()),
                new IndexingInfo(
                        embeddingConfiguration.workers(),
                        embeddingConfiguration.pollBatchSize(),
                        pollIntervalMs,
                        embeddingConfiguration.maxAttempts(),
                        embeddingConfiguration.retryBackoffSeconds(),
                        embeddingConfiguration.stuckTimeoutMinutes(),
                        stuckCheckMs,
                        embeddingConfiguration.cleanupRetentionDays(),
                        embeddingConfiguration.cache().enabled(),
                        embeddingConfiguration.cache().ttlDays(),
                        embeddingConfiguration.cache().cleanupCron()));
    }

    /** {@code spring.profiles.active} is empty when nothing is set — Spring then runs "default". */
    private List<String> profiles() {
        if (activeProfiles.isBlank()) {
            return List.of("default");
        }
        return Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .filter(p -> !p.isBlank())
                .toList();
    }

    /**
     * Current schema version according to Flyway. Reads the schema history table, so it is a DB
     * round-trip; a failure here must not take the whole panel down, hence the broad catch.
     */
    private @Nullable String schemaVersion() {
        Flyway instance = flyway.getIfAvailable();
        if (instance == null) {
            return null;
        }
        try {
            MigrationInfo current = instance.info().current();
            return current == null || current.getVersion() == null
                    ? null
                    : current.getVersion().toString();
        } catch (RuntimeException e) {
            log.debug("Could not read the Flyway schema version", e);
            return null;
        }
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
