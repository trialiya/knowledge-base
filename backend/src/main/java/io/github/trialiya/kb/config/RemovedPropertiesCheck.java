package io.github.trialiya.kb.config;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the context when the configuration still sets a key the application no longer reads.
 *
 * <p>Spring binds by name and stays silent about the rest, so a removed key is the one
 * configuration mistake that produces no signal at all — and for the keys below silence means a
 * deployment that believed it had a restriction quietly runs without one. Better to refuse to start
 * with the replacement named than to serve files the operator thought were hidden.
 */
@Component
public class RemovedPropertiesCheck {

    /**
     * Removed key → what to configure instead.
     *
     * <p>{@code kb.git.edit-enabled} is deliberately absent even though it is equally gone: its
     * {@code KB_GIT_EDIT_ENABLED} environment variable is still what {@code
     * kb.projects[0].edit-enabled} reads by default, and relaxed binding would make every such
     * deployment fail to start.
     */
    private static final Map<String, String> REMOVED =
            Map.of(
                    "kb.script.deny-globs",
                    "script visibility now follows the tracked-files rule; keep secrets out"
                            + " of the repository or in .gitignore",
                    "kb.script.allow-globs",
                    "use kb.projects[].allow-globs to admit untracked files per project");

    private final Environment environment;

    public RemovedPropertiesCheck(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        Map<String, String> found = new LinkedHashMap<>();
        REMOVED.forEach(
                (key, replacement) -> {
                    // A non-empty YAML list binds as key[0], key[1], … — the bare key alone is
                    // only ever present when the value came from an environment variable.
                    if (environment.containsProperty(key)
                            || environment.containsProperty(key + "[0]")) {
                        found.put(key, replacement);
                    }
                });
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "Configuration sets properties that no longer exist: "
                        + found.entrySet().stream()
                                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                                .collect(Collectors.joining("; ")));
    }
}
