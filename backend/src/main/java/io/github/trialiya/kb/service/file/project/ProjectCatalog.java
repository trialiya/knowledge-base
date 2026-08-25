package io.github.trialiya.kb.service.file.project;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.model.project.Project;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The configured projects, resolved once at startup: {@code kb.projects} with its defaults applied,
 * or — when that list is empty — the single project the legacy {@code kb.git.project-path}
 * describes, so a deployment that only sets {@code PROJECT_PATH} needs no config change.
 *
 * <p>This is also where "no project named" is answered, and it is answered in exactly one place:
 * {@link #defaultProject()} is the <em>first</em> entry of the list. Every caller that may not know
 * the project — a tool call with nothing in its {@code ToolContext}, an endpoint with no parameter
 * for it — resolves through here rather than reaching for a configuration value of its own.
 *
 * <p>Anything that cannot be satisfied fails the context: a bad path is not a project whose tools
 * merely return errors, it is a deployment that was meant to serve a repository and cannot.
 *
 * <p>A project switched off ({@code enabled: false}) is not here at all — not routable, not listed,
 * not opened. A chat that had chosen it keeps the id in {@code chat_topic.project} and runs on the
 * default project instead; the id no longer matching anything in {@link #projects()} is precisely
 * what tells the UI to say the project is gone.
 */
@Slf4j
@Service
public class ProjectCatalog {

    /** Meant to survive a URL segment unescaped, so no spaces, slashes or uppercase. */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** Id of the project synthesized from {@code kb.git.project-path}. */
    private static final String LEGACY_ID = "default";

    private final List<Project> projects;

    public ProjectCatalog(ProjectProperties projectProperties, GitProperties gitProperties) {
        this.projects = resolve(projectProperties.projects(), gitProperties);
        log.info(
                "Projects: {}",
                projects.stream().map(p -> p.id() + " → " + p.path() + mode(p)).toList());
    }

    /**
     * What the startup line says a project permits — the writes and the user's git commands, or
     * nothing at all when it permits neither.
     */
    private static String mode(Project project) {
        List<String> granted = new ArrayList<>();
        if (project.editEnabled()) {
            granted.add("rw");
            if (project.untrackedEditEnabled()) {
                granted.add("untracked rw");
            }
        }
        if (project.gitCommandsEnabled()) {
            granted.add(project.gitPushEnabled() ? "git commands, push" : "git commands");
        }
        return granted.isEmpty() ? "" : " (" + String.join(", ", granted) + ")";
    }

    /** Every configured project, in configuration order. Never empty. */
    public List<Project> projects() {
        return projects;
    }

    /**
     * The project a caller gets when it names none. First in the list — the rule the whole
     * single-project compatibility rests on, so it is stated once here and nowhere else.
     */
    public Project defaultProject() {
        return projects.getFirst();
    }

    /**
     * Whether {@code projectId} names a configured project. Strict, unlike {@link #find}: a blank
     * id is not "the default" here but an id nobody configured, because this answers "is this
     * stored or requested value still usable", and there {@code null} has its own meaning already —
     * the caller stated no project. Mirrors {@code ChatModelProperties#isAllowed}.
     */
    public boolean isAllowed(@Nullable String projectId) {
        return projectId != null && projects.stream().anyMatch(p -> p.id().equals(projectId));
    }

    /** The project {@code projectId} names; empty for an unknown id. {@code null} → the default. */
    public Optional<Project> find(@Nullable String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return Optional.of(defaultProject());
        }
        return projects.stream().filter(p -> p.id().equals(projectId)).findFirst();
    }

    /**
     * As {@link #find}, but an unknown id is a caller error rather than a silent fallback: reading
     * the wrong repository because a project was renamed is worse than a failed call that says so.
     */
    public Project require(@Nullable String projectId) {
        return find(projectId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown project: "
                                                + projectId
                                                + " (configured: "
                                                + projects.stream().map(Project::id).toList()
                                                + ")"));
    }

    private static List<Project> resolve(List<ProjectOption> all, GitProperties gitProperties) {
        // Switched-off entries are dropped before anything else, validation included: a project
        // prepared for later may well point at a path this deployment has not mounted yet.
        List<ProjectOption> options = all.stream().filter(ProjectOption::enabled).toList();
        if (!all.isEmpty() && options.isEmpty()) {
            throw new IllegalStateException(
                    "kb.projects: every configured project is disabled "
                            + all.stream().map(ProjectOption::id).toList()
                            + " — there is no repository left to serve");
        }
        if (options.isEmpty()) {
            String path = gitProperties.projectPath();
            if (!StringUtils.hasText(path)) {
                throw new IllegalStateException(
                        "No project configured: set kb.projects[0].path (or the legacy"
                                + " kb.git.project-path)");
            }
            // The legacy form carries no flags and no globs: edits, untracked access and the
            // user's git commands are per-project opt-ins, and opting in means writing the
            // kb.projects entry out.
            return List.of(
                    new Project(
                            LEGACY_ID,
                            LEGACY_ID,
                            absolute(path),
                            false,
                            false,
                            List.of(),
                            false,
                            false));
        }
        List<Project> resolved = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ProjectOption option : options) {
            String id = option.id();
            if (id == null || !SAFE_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "kb.projects: id \""
                                + id
                                + "\" is not usable — lowercase letters, digits, '.', '_' and '-'"
                                + " only, starting with a letter or a digit");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("kb.projects: duplicate id \"" + id + "\"");
            }
            // Blank rather than absent is the shape an unconfigured deployment arrives in: the
            // shipped yaml fills this from PROJECT_PATH, falling back to kb.git.project-path and
            // then to nothing at all.
            if (!StringUtils.hasText(option.path())) {
                throw new IllegalStateException(
                        "No project configured: kb.projects["
                                + id
                                + "].path is empty — set it, or PROJECT_PATH / the legacy"
                                + " kb.git.project-path it defaults to");
            }
            requireRootedGlobs(id, option.allowGlobs());
            resolved.add(
                    new Project(
                            id,
                            option.displayLabel(),
                            absolute(option.path()),
                            option.editEnabled(),
                            untrackedEdits(option),
                            option.allowGlobs(),
                            option.gitCommands().enabled(),
                            gitPush(option)));
        }
        return List.copyOf(resolved);
    }

    /**
     * Whether this project really lets a write land on an untracked file: {@code
     * untracked-edit-enabled} <em>and</em> {@code edit-enabled}, because the flag only narrows the
     * edits a project already permits.
     *
     * <p>The two configurations it cannot satisfy are logged rather than refused — neither is
     * ambiguous, both are safe (no write becomes possible), and failing a deployment's startup over
     * a flag that grants nothing would be worse than the flag quietly granting nothing:
     *
     * <ul>
     *   <li>edits off for the project — the flag names a permission there is nothing to widen;
     *   <li>no {@code allow-globs} — the project serves no untracked file for it to apply to.
     * </ul>
     */
    private static boolean untrackedEdits(ProjectOption option) {
        if (!option.untrackedEditEnabled()) {
            return false;
        }
        if (!option.editEnabled()) {
            log.warn(
                    "kb.projects[{}]: untracked-edit-enabled is set while edit-enabled is false —"
                            + " the project stays read-only",
                    option.id());
            return false;
        }
        if (option.allowGlobs().isEmpty()) {
            log.warn(
                    "kb.projects[{}]: untracked-edit-enabled is set while allow-globs is empty —"
                            + " the project serves no untracked file to edit",
                    option.id());
        }
        return true;
    }

    /**
     * Whether this project really offers {@code push}: {@code push-enabled} <em>and</em> the
     * commands it is part of. Logged rather than refused, for the reason {@link
     * #untrackedEdits(ProjectOption)} states — the flag grants nothing on its own, and a startup
     * failure over a permission nobody gained would be the worse outcome.
     */
    private static boolean gitPush(ProjectOption option) {
        if (!option.gitCommands().pushEnabled()) {
            return false;
        }
        if (!option.gitCommands().enabled()) {
            log.warn(
                    "kb.projects[{}]: git-commands.push-enabled is set while git-commands.enabled"
                            + " is false — no git command is offered for this project",
                    option.id());
            return false;
        }
        return true;
    }

    /**
     * Refuses an {@code allow-globs} entry whose first path segment is already a wildcard.
     *
     * <p>Two reasons, and the second is why this is a hard failure rather than a warning. The
     * working-tree walk behind these globs is rooted at the glob's literal prefix, so a glob
     * without one would scan the whole tree on every listing. And since these globs override {@code
     * .gitignore}, the blast radius of {@code **}{@code /*} is every secret in the repository — a
     * deployment should have to name the directory it is opening up.
     *
     * <p>Validated here rather than in {@code GitService}, which builds the repository: a project
     * that fails to open is dropped from the catalogue with a logged error, and a misconfigured
     * glob silently losing the project is the opposite of what a refusal is for.
     */
    private static void requireRootedGlobs(String id, List<String> globs) {
        List<String> unrooted = globs.stream().filter(ProjectCatalog::isUnrooted).toList();
        if (!unrooted.isEmpty()) {
            throw new IllegalStateException(
                    "kb.projects["
                            + id
                            + "].allow-globs must start with a directory, not a wildcard: "
                            + String.join(", ", unrooted)
                            + " — these globs override .gitignore, so the area has to be named");
        }
    }

    private static boolean isUnrooted(String glob) {
        int wildcard = -1;
        for (int i = 0; i < glob.length() && wildcard < 0; i++) {
            if (glob.charAt(i) == '*' || glob.charAt(i) == '?') wildcard = i;
        }
        return wildcard >= 0 && glob.lastIndexOf('/', wildcard) <= 0;
    }

    private static Path absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
