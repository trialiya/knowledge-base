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
 * @param allowGlobs Ant-style globs naming the untracked (per {@code git status} — gitignored files
 *     stay hidden) files of this repository that may be worked with alongside the tracked ones;
 *     empty — tracked files only. Enforced by {@code GitService}
 */
public record Project(
        String id, String label, Path path, boolean editEnabled, List<String> allowGlobs) {

    public Project {
        allowGlobs = allowGlobs == null ? List.of() : List.copyOf(allowGlobs);
    }
}
