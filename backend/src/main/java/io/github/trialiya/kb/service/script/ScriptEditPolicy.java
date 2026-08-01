package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.GitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single answer to "may a script write?", so the two things that must agree cannot drift: the API
 * object bound into the sandbox ({@code ScriptRunner}) and the handbook the model is given ({@code
 * ScriptGuideService}). A model told about {@code kb.edit} that is not bound would burn its
 * attempts on a method that does not exist.
 *
 * <p>Three gates, all required — the first two are the same ones that decide whether the {@code
 * editFile} tool exists at all (see {@code ChatConfig#gitEditFunction}), so scripts can never be a
 * way around a read-only deployment:
 *
 * <ul>
 *   <li>{@code kb.git.edit-enabled=true} — working-tree writes are permitted at all;
 *   <li>the working tree is actually writable — a read-only mount withholds writes regardless;
 *   <li>{@code kb.script.edit-enabled=true} — writes are permitted <em>from scripts</em>, which a
 *       deployment may want to refuse while still allowing the ordinary edit tools.
 * </ul>
 */
@Slf4j
@Service
public class ScriptEditPolicy {

    private final boolean enabled;

    public ScriptEditPolicy(
            GitProperties gitProperties, ScriptProperties scriptProperties, GitService gitService) {
        boolean requested = gitProperties.editEnabled() && scriptProperties.editEnabled();
        this.enabled = requested && gitService.isRepoWritable();
        if (requested && !enabled) {
            log.warn(
                    "Script edits requested, but the repository working tree is not writable "
                            + "(read-only mount?) — kb.edit/kb.create are NOT bound into scripts");
        }
        if (scriptProperties.enabled()) {
            log.info("Script edits {}", enabled ? "enabled (kb.edit/kb.create)" : "disabled");
        }
    }

    public boolean enabled() {
        return enabled;
    }
}
