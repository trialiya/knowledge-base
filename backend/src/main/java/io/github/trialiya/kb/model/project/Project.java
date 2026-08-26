package io.github.trialiya.kb.model.project;

import java.nio.file.Path;
import java.util.List;

/**
 * A repository the assistant works with, as resolved from configuration by {@code ProjectCatalog}:
 * the defaults are already applied and the path is absolute, so nothing downstream re-derives
 * either.
 *
 * @param id key the project is named by wherever a project has to be named — {@code GitRegistry},
 *     the {@code ToolContext}, and later the chat's stored choice and file links
 * @param label human-readable name
 * @param path absolute, normalized path of the Git working tree
 * @param editEnabled whether working-tree writes are permitted for this repository at all. The
 *     configured intent, not the answer: a read-only mount withholds writes regardless, which is
 *     why "may the model edit this project" is asked of {@code GitRegistry} instead
 * @param untrackedEditEnabled whether a write may land on an untracked file admitted by {@link
 *     #allowGlobs()}. Already combined with {@link #editEnabled()} here — the configuration only
 *     narrows edits with it, so a project whose edits are off never has this on — and false for
 *     every project that admits no untracked file in the first place. Enforced by {@code
 *     GitWriter}: off, the admitted area is read-only
 * @param allowGlobs Ant-style globs naming the untracked files of this repository that may be read
 *     alongside the tracked ones — edited only with {@link #untrackedEditEnabled()}, never created,
 *     never staged. Inside the globs the working tree is the truth, {@code .gitignore} included;
 *     empty — tracked files only. Enforced by {@code GitService}, rooted-ness checked by {@code
 *     ProjectCatalog}
 * @param gitCommandsEnabled whether a <em>user</em> may run git commands on this repository from
 *     the UI — a different grant from {@link #editEnabled()}, which is about what the model writes
 *     into the working tree. The configured intent again: {@code GitRegistry} combines it with the
 *     tree's own permissions, and a read-only mount withholds the commands whatever this says
 * @param gitPushEnabled whether {@code push} is among them. Already combined with {@link
 *     #gitCommandsEnabled()} here — the configuration only narrows the commands with it, so a
 *     project whose commands are off never has this on
 */
public record Project(
        String id,
        String label,
        Path path,
        boolean editEnabled,
        boolean untrackedEditEnabled,
        List<String> allowGlobs,
        boolean gitCommandsEnabled,
        boolean gitPushEnabled) {

    public Project {
        allowGlobs = allowGlobs == null ? List.of() : List.copyOf(allowGlobs);
    }
}
