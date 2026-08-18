package io.github.trialiya.kb.config.model;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.projects} — the repositories the assistant can work with.
 *
 * <pre>
 * kb:
 *   projects:
 *     - id: default
 *       label: Knowledge Base
 *       path: /project
 *       edit-enabled: false
 * </pre>
 *
 * <p><b>Exactly one entry for now.</b> Everything downstream is already addressed by project id —
 * the repositories live in {@code GitRegistry}, tools resolve theirs from the {@code ToolContext}
 * and a caller that names none gets the first one — but the selection is not wired to a chat, an
 * endpoint or a link yet, so a second entry here would be unreachable and is refused by {@code
 * ProjectCatalog} rather than silently ignored.
 *
 * <p>Left empty, the single project is taken from the legacy {@code kb.git.project-path} (see
 * {@code ProjectCatalog}), so a deployment that only sets {@code PROJECT_PATH} keeps working.
 */
@ConfigurationProperties(prefix = "kb")
public record ProjectProperties(List<ProjectOption> projects) {

    public ProjectProperties {
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    /**
     * One configured repository.
     *
     * @param id stable key used everywhere a project has to be named — the {@code ToolContext}, and
     *     later the chat's stored choice and file links. Restricted to lowercase letters, digits,
     *     {@code . _ -} because it is meant to survive a trip through a URL segment unescaped
     * @param label human-readable name; defaults to {@link #id()} when omitted
     * @param path filesystem path of the Git working tree, as {@code kb.git.project-path} was
     * @param editEnabled whether the working-tree edit tools may touch <em>this</em> repository;
     *     omitted, the deployment-wide {@code kb.git.edit-enabled} applies. Still not sufficient on
     *     its own — a read-only mount withholds writes regardless (see {@code GitRegistry})
     */
    public record ProjectOption(
            String id, @Nullable String label, String path, @Nullable Boolean editEnabled) {

        /** What to show when the config named no label. */
        public String displayLabel() {
            return label == null || label.isBlank() ? id : label;
        }
    }
}
