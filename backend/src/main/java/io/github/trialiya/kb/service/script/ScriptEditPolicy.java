package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.GitRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single answer to "may a script write to this project?", so the two things that must agree cannot
 * drift: the API object bound into the sandbox ({@code ScriptRunner}) and the handbook the model is
 * given ({@code ScriptGuideService}). A model told about {@code kb.edit} that is not bound would
 * burn its attempts on a method that does not exist.
 *
 * <p>Two gates, both required:
 *
 * <ul>
 *   <li>the project accepts writes at all — configured for it and its working tree writable, the
 *       same answer that decides whether the {@code editFile} tool may act on it (see {@code
 *       GitRegistry#editsAllowed}), so scripts can never be a way around a read-only deployment;
 *   <li>{@code kb.script.edit-enabled=true} — writes are permitted <em>from scripts</em>, which a
 *       deployment may want to refuse while still allowing the ordinary edit tools.
 * </ul>
 */
@Slf4j
@Service
public class ScriptEditPolicy {

    private final GitRegistry gitRegistry;
    private final boolean scriptEditEnabled;

    public ScriptEditPolicy(GitRegistry gitRegistry, ScriptProperties scriptProperties) {
        this.gitRegistry = gitRegistry;
        this.scriptEditEnabled = scriptProperties.editEnabled();
        if (scriptProperties.enabled()) {
            log.info(
                    "Script edits {}",
                    enabled()
                            ? "enabled (kb.edit/kb.create/kb.writeBytes/kb.createBytes)"
                            : "disabled");
        }
    }

    /** Whether scripts may write to {@code projectId}; {@code null} — the default project. */
    public boolean enabled(@Nullable String projectId) {
        return scriptEditEnabled && gitRegistry.editsAllowed(projectId);
    }

    /**
     * The answer for the default project — what the Settings panel reports, that panel being per
     * deployment rather than per chat. A run asks {@link #enabled(String)} with its own project
     * instead: {@code edit-enabled} is configured per project, so the default's answer is not the
     * deployment's.
     */
    public boolean enabled() {
        return enabled(null);
    }
}
