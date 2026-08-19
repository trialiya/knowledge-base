package io.github.trialiya.kb.config.model;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
 * <p><b>Exactly one <em>enabled</em> entry for now.</b> Addresses, chips, tool results and the chat
 * history (the project-switch marker) all carry the project already; what is still missing is the
 * deployment story for several repositories — mounts, {@code git safe.directory}, degradation of a
 * broken mount. {@code ProjectCatalog} refuses such a configuration rather than serve repositories
 * the deployment cannot back (the remaining work is listed in {@code
 * docs/todo/мульти-проекты-исследование.md} §10).
 *
 * <p>Entries switched off with {@code enabled: false} do not count, which is how a second project
 * is prepared before it can be served: its block sits in the configuration, validated by nothing
 * and reachable by no one, until the flag flips.
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
     * @param enabled {@code false} — the project is not served: no repository is opened for it, it
     *     is absent from {@code GET /api/chats/projects}, and a call naming it is refused. Chats
     *     that had chosen it keep the id in {@code chat_topic.project} and run on the default one,
     *     which is what lets the UI say the project is gone instead of silently showing another
     *     repository's files. Defaults to {@code true}
     */
    public record ProjectOption(
            String id,
            @Nullable String label,
            String path,
            @Nullable Boolean editEnabled,
            @DefaultValue("true") boolean enabled) {

        /** What to show when the config named no label. */
        public String displayLabel() {
            return label == null || label.isBlank() ? id : label;
        }
    }
}
