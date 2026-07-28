package io.github.trialiya.kb.config.model;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How this server instance is wired: the Spring-owned settings the Admin panel reports (see {@code
 * SystemInfoController}).
 *
 * <p>Not a {@code @ConfigurationProperties} record like its neighbours, because these keys live
 * under four different prefixes owned by Spring itself ({@code spring.application}, {@code
 * spring.datasource}, {@code spring.flyway}, {@code server}) and no single prefix covers them. They
 * stay explicit placeholders — but in one bean instead of nine constructor parameters of the
 * controller.
 *
 * <p>The placeholders are deliberately read one by one, never as a bulk {@code Environment} dump:
 * that is what keeps API keys and the datasource password out of the panel by construction. The
 * password has no field here at all, and {@link #datasourceUrl()} is sanitized by the controller
 * before it is reported — a JDBC URL may carry credentials of its own.
 *
 * <p>The canonical constructor is written out so the {@code @Value} annotations sit on its
 * parameters only: on a record component the same annotation would also land on the (final) field,
 * where Spring cannot inject it.
 */
@Component
public record ServerEnvironment(
        String applicationName,
        String activeProfiles,
        int port,
        String datasourceUrl,
        String datasourceDriver,
        String datasourceUsername,
        String flywayLocations) {

    public ServerEnvironment(
            @Value("${spring.application.name:knowledge-base}") String applicationName,
            @Value("${spring.profiles.active:}") String activeProfiles,
            @Value("${server.port:8080}") int port,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.driver-class-name:}") String datasourceDriver,
            @Value("${spring.datasource.username:}") String datasourceUsername,
            @Value("${spring.flyway.locations:}") String flywayLocations) {
        this.applicationName = applicationName;
        this.activeProfiles = activeProfiles;
        this.port = port;
        this.datasourceUrl = datasourceUrl;
        this.datasourceDriver = datasourceDriver;
        this.datasourceUsername = datasourceUsername;
        this.flywayLocations = flywayLocations;
    }

    /** {@code spring.profiles.active} is empty when nothing is set — Spring then runs "default". */
    public List<String> profiles() {
        if (activeProfiles.isBlank()) {
            return List.of("default");
        }
        return Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .filter(p -> !p.isBlank())
                .toList();
    }
}
