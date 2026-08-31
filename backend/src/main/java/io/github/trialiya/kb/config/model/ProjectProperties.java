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
 *       untracked-edit-enabled: false
 *       allow-globs: []
 *       skills:
 *         - name: release-checklist
 *           trigger: "before preparing a release of this repo"
 *           file: docs/skills/release.md
 *       git-commands:
 *         enabled: false
 *         push-enabled: false
 * </pre>
 *
 * <p>Several entries are served at once: each is a repository of its own with its own mount, and
 * every path that names a repository — addresses, chips, tool arguments and results, the chat's
 * stored project and the switch marker in its history — carries the project id. The deployment has
 * to back them: one mount per project, and {@code git safe.directory} covering each mounted path
 * ({@code git grep} runs as a subprocess), see {@code docs/проект/конфигурация.md}.
 *
 * <p>Entries switched off with {@code enabled: false} do not count and are not validated, which is
 * how a project is prepared before its mount exists: the block sits in the configuration, reachable
 * by no one, until the flag flips.
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
     * @param editEnabled whether the working-tree edit tools may touch <em>this</em> repository —
     *     set per project, there is no deployment-wide default. Still not sufficient on its own — a
     *     read-only mount withholds writes regardless (see {@code GitRegistry}). Defaults to {@code
     *     false}
     * @param untrackedEditEnabled whether those of the edits may land on an <em>untracked</em> file
     *     — one admitted for reading by {@link #allowGlobs()}. Off, that area is served read-only:
     *     the files are listed, read and grepped as before, and every write to one is refused. Only
     *     narrows {@link #editEnabled()} and never widens it, so it means nothing on a project
     *     whose edits are off at all; {@code ProjectCatalog} says so rather than leaving the
     *     configuration to look effective. Defaults to {@code false}
     * @param allowGlobs Ant-style globs naming the <em>untracked</em> files of this repository the
     *     assistant may also work with — read always, edit only with {@link
     *     #untrackedEditEnabled()}, never create, and never staged. Inside the globs the working
     *     tree is the truth and git is not consulted, so {@code .gitignore} hides nothing there:
     *     that is the point (build reports and logs live in an ignored directory), and it is why
     *     every glob has to start with a real directory rather than a wildcard — {@code
     *     ProjectCatalog} refuses to start otherwise. Empty by default — tracked files only, as
     *     everywhere else
     * @param skills the skills this project defines — see {@link SkillOption}. Loadable through
     *     {@code readSkill} only while the project is the chat's active one; the built-in skills
     *     from {@code prompt/skills/} stay available everywhere on top of these. Empty by default
     * @param gitCommands whether the <em>user</em> may run git commands against this repository
     *     from the UI, and which of them — see {@link GitCommandsOption}. A section of its own
     *     because it grants something different from {@link #editEnabled()}: that one says what the
     *     model may write into the working tree, this one says which repository operations a person
     *     driving the UI may perform on it. Absent — every command is off
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
            @DefaultValue("false") boolean editEnabled,
            @DefaultValue("false") boolean untrackedEditEnabled,
            List<String> allowGlobs,
            List<SkillOption> skills,
            @DefaultValue GitCommandsOption gitCommands,
            @DefaultValue("true") boolean enabled) {

        public ProjectOption {
            allowGlobs = allowGlobs == null ? List.of() : List.copyOf(allowGlobs);
            skills = skills == null ? List.of() : List.copyOf(skills);
            gitCommands = gitCommands == null ? GitCommandsOption.OFF : gitCommands;
        }

        /** What to show when the config named no label. */
        public String displayLabel() {
            return label == null || label.isBlank() ? id : label;
        }
    }

    /**
     * One skill a project defines — {@code kb.projects[].skills[]}: an instruction file inside the
     * project tree that the model loads by name with {@code readSkill} while this project is the
     * chat's active one.
     *
     * <p>The description lives in the configuration and the text in the repository, on purpose. The
     * name and trigger go into the prompt on every turn, so they have to be readable without
     * touching the disk and stable while the deployment runs; the text is wanted only at the {@code
     * readSkill} call, and reading it there means a pull or branch switch updates the skill with no
     * restart — which is also why a missing file is a call-time tool error, not a startup check:
     * some branch may legitimately lack it.
     *
     * @param name key the model loads the skill by. Same character rules as a project id, and it
     *     must not collide with a built-in skill's name — {@code SkillService} refuses to start on
     *     either
     * @param trigger when the model should load it — one line shown after the name in the {@code
     *     <active-project>} skill list, e.g. {@code "before preparing a release of this repo"}
     * @param file path of the markdown with the skill's instructions, relative to the project tree;
     *     {@code ProjectCatalog} refuses one that resolves outside it. Tracked-ness does not matter
     *     — the deployment named this exact file, so {@code allow-globs} plays no part
     */
    public record SkillOption(String name, String trigger, String file) {}

    /**
     * The git commands a user may run on one project from the UI — {@code kb.projects[].git-
     * commands}.
     *
     * <p>These are never the model's: the assistant gets no tool for them, and every one of them is
     * a person's explicit action in the interface. The configuration exists because the operations
     * reach past the working tree — they change which commit the checkout sits on, and one of them
     * publishes to a remote — so a deployment has to name the repositories it is opening up.
     *
     * <p>Like {@code edit-enabled}, this is the configured intent and not the answer: a read-only
     * mount withholds all of it regardless, and {@code GitRegistry} is where the two halves meet.
     *
     * @param enabled whether the local commands are offered at all — the branch/status reads, and
     *     the operations that move the working tree (switch, stash, commit, discard, merge --abort)
     *     plus the network reads (fetch, pull). Defaults to {@code false}
     * @param pushEnabled whether {@code push} is offered as well. Separate because it is the one
     *     command that publishes this repository's content outside the deployment: "keep the
     *     checkout up to date" and "may send commits to the remote" are different grants, and the
     *     first is the common one. Only narrows {@link #enabled()} and never widens it — {@code
     *     ProjectCatalog} says so rather than leaving the configuration to look effective. Defaults
     *     to {@code false}
     */
    public record GitCommandsOption(
            @DefaultValue("false") boolean enabled, @DefaultValue("false") boolean pushEnabled) {

        /** What a project that configured no {@code git-commands} section grants: nothing. */
        public static final GitCommandsOption OFF = new GitCommandsOption(false, false);
    }
}
