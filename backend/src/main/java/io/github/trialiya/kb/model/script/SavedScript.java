package io.github.trialiya.kb.model.script;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One script a project saves — an entry of its script manifest, resolved by {@code
 * ScriptManifestReader}.
 *
 * <p>Only the declaration lives here; the code does not. The text is read from the working tree at
 * the moment of the call ({@code SavedScriptCatalog#source}), so a pull or a branch switch changes
 * what runs without a restart, and a branch that does not carry the file costs a tool error rather
 * than a startup failure — the same division {@code ProjectSkill} draws for skills.
 *
 * @param name key the model runs the script by; unique within the manifest
 * @param file path of the JavaScript, relative to the project tree. Must be tracked by git when it
 *     is read: the {@code allow-globs} area holds build output and logs, and there is nothing there
 *     anyone reviewed well enough to execute
 * @param desc the catalogue line — the only thing the model chooses by, so a manifest entry without
 *     one is refused
 * @param params arguments the script declares, in manifest order; empty when it takes none
 * @param write the script edits files. Not a permission — {@code ScriptEditPolicy} still decides
 *     that — but a declaration, so a surface that cannot write at all (the read-only bench, the
 *     search sub-agent) refuses before the run instead of failing somewhere inside it
 * @param timeout wall-clock budget for this script, clamped by {@code kb.script.max-timeout}; null
 *     leaves {@code kb.script.timeout} in charge
 */
public record SavedScript(
        String name,
        String file,
        String desc,
        List<ScriptParam> params,
        boolean write,
        @Nullable Duration timeout) {

    public SavedScript {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
