package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The scripts a project saves: the manifest it declares them in, the list the model chooses from,
 * and the text of the one it picked.
 *
 * <p><b>Read from the working tree at call time</b>, exactly like a project skill: a pull or a
 * branch switch changes the list and the code with no restart, and a branch that carries neither is
 * an empty list rather than a startup failure. What that costs is a stat of the manifest on every
 * prompt build — the parse itself is cached and repeated only when the file's size or timestamp
 * moves.
 *
 * <p><b>Two different files, two different gates</b>, and the difference is who named them. The
 * manifest is named by the deployment ({@code kb.projects[].scripts-manifest}), so it is read
 * straight off the disk like a skill's file, with the tree containment re-checked against symlinks.
 * The scripts are named by the manifest — that is, by the repository — so they are read through
 * {@code GitService} and have to be <b>tracked</b>: the {@code allow-globs} area holds build output
 * and logs, and nothing there went through the review that makes executing a file reasonable.
 */
@Slf4j
@Service
public class SavedScriptCatalog {

    /**
     * Ceiling for both files. A manifest past it is not a declaration any more, and a script past
     * it is not glue code — {@code runScript} exists for what the model can write in one call, and
     * a saved script is the same kind of thing with a name.
     */
    static final long MAX_BYTES = 64 * 1024;

    /** How much of a description reaches the prompt; the rest is documentation for the repo. */
    private static final int MAX_DESC_CHARS = 200;

    private final ProjectCatalog projects;
    private final GitRegistry gitRegistry;

    /**
     * The sandbox's own switch. A saved script is run by the engine {@code kb.script.enabled} turns
     * off, so with scripts off there is nothing to announce — and announcing it anyway would name a
     * tool that no bean registered.
     */
    private final ScriptProperties properties;

    /** Parsed manifests by project id, kept only as long as the file behind one does not move. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SavedScriptCatalog(
            ProjectCatalog projects, GitRegistry gitRegistry, ScriptProperties properties) {
        this.projects = projects;
        this.gitRegistry = gitRegistry;
        this.properties = properties;
    }

    /**
     * Whether any project can have saved scripts at all — a startup constant, and the gate that
     * decides whether {@code runSavedScript} is offered to the model ({@code ChatConfig}). Deployed
     * without a single manifest configured, the tool would be a name with an empty shelf behind it.
     */
    public boolean anyManifests() {
        return properties.enabled()
                && projects.projects().stream()
                        .anyMatch(project -> project.scriptsManifest() != null);
    }

    /** What the project declares right now; empty when it declares nothing or has no manifest. */
    public List<SavedScript> scripts(@Nullable String projectId) {
        return scriptsOf(activeProject(projectId));
    }

    /**
     * The script this name stands for.
     *
     * @throws IllegalArgumentException no such script in the active project — the message lists
     *     what there is, because that is what the model needs in order to pick again (a script of
     *     another project is indistinguishable from one that does not exist, for the reason {@code
     *     SkillService} gives about skills)
     */
    public SavedScript require(@Nullable String projectId, String name) {
        Project project = activeProject(projectId);
        return scriptsOf(project).stream()
                .filter(script -> script.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown script \""
                                                + name
                                                + "\" in project "
                                                + project.id()
                                                + ". "
                                                + availableList(project)
                                                + " To run something else, write it yourself with"
                                                + " runScript."));
    }

    /**
     * The script's text, as the working tree holds it this second, together with what the result
     * will say about it.
     *
     * @throws IllegalArgumentException the file is missing on this branch, untracked, binary or too
     *     large — every one of them is a state of the tree, so it is the tool's answer and not a
     *     server error
     */
    public ScriptSource source(
            @Nullable String projectId, SavedScript script, Map<String, Object> args) {
        Project project = activeProject(projectId);
        GitFileContent file = readFile(project, script);
        String text = file.content();
        if (text == null || file.binary()) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\" ("
                            + script.file()
                            + ") is not text — the manifest points at a"
                            + " binary file");
        }
        if (!file.tracked()) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\" ("
                            + script.file()
                            + ") is not tracked by git — a saved script has to be a committed file,"
                            + " not something that appeared in the working tree");
        }
        if (file.sizeBytes() > MAX_BYTES || file.truncated()) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\" is too large to run ("
                            + file.sizeBytes()
                            + " bytes, the limit is "
                            + MAX_BYTES
                            + ") — tell the user its file needs splitting");
        }
        ScriptRunSource report =
                new ScriptRunSource(
                        ScriptRunSource.Kind.PROJECT, script.name(), file.path(), sha(text), args);
        return new ScriptSource(text, file.path(), report);
    }

    /**
     * The section of the {@code <active-project>} block that lists this project's scripts; {@code
     * ""} when it has none.
     *
     * <p>Listed there rather than in the system prompt for the reason the skills are: the list is a
     * property of the conversation's project, and in the system prompt it would rewrite the cached
     * prefix on every project switch. Kept short for the other half of the same reason — the block
     * is rebuilt on every iteration of the tool loop and paid for every turn.
     */
    public String projectScripts(Project project) {
        if (!properties.enabled()) {
            return "";
        }
        List<SavedScript> scripts = scriptsOf(project);
        if (scripts.isEmpty()) {
            return "";
        }
        StringBuilder text =
                new StringBuilder(
                        "\n\nScripts this repository saves — run one with `runSavedScript` instead"
                                + " of writing the same script again (the code is in the"
                                + " repository, you do not need to read it first):");
        for (SavedScript script : scripts) {
            text.append("\n- `")
                    .append(script.name())
                    .append("` — ")
                    .append(describe(script))
                    .append(" (")
                    .append(argsLine(script))
                    .append(script.write() ? "; writes files" : "")
                    .append(')');
        }
        text.append(
                "\nOnly these names run; anything else is a script you write yourself with"
                        + " `runScript`.");
        return text.toString();
    }

    private List<SavedScript> scriptsOf(Project project) {
        Path manifest = project.scriptsManifest();
        if (manifest == null) {
            return List.of();
        }
        Stamp stamp = stamp(manifest);
        Cached cached = cache.get(project.id());
        if (cached != null && cached.stamp().equals(stamp)) {
            return cached.scripts();
        }
        List<SavedScript> scripts = stamp.missing() ? List.of() : read(project, manifest);
        cache.put(project.id(), new Cached(stamp, scripts));
        return scripts;
    }

    private List<SavedScript> read(Project project, Path manifest) {
        String where = "kb.projects[" + project.id() + "].scripts-manifest " + manifest;
        try {
            requireInsideTree(project, manifest);
            if (Files.size(manifest) > MAX_BYTES) {
                log.warn("{}: larger than {} bytes — not read", where, MAX_BYTES);
                return List.of();
            }
            return ScriptManifestReader.parse(
                    Files.readString(manifest, StandardCharsets.UTF_8), where);
        } catch (NoSuchFileException e) {
            // The branch does not carry it — a legitimate state, and the stamp already said so.
            return List.of();
        } catch (IOException e) {
            log.warn("{}: cannot be read as UTF-8 text — no saved scripts from it", where, e);
            return List.of();
        }
    }

    /**
     * A symlink past the startup check: {@code ProjectCatalog} compares strings, and a link can be
     * committed — or put in place — long after the deployment started. Same move {@code
     * SkillService} makes for a skill's file, and for the same reason the project root is resolved
     * too: a tree legitimately lives behind a symlink ({@code /tmp} → {@code /private/tmp}).
     */
    private static void requireInsideTree(Project project, Path manifest) throws IOException {
        if (!manifest.toRealPath().startsWith(project.path().toRealPath())) {
            log.warn(
                    "kb.projects[{}].scripts-manifest leaves the project tree through a symlink:"
                            + " {} — not read",
                    project.id(),
                    manifest);
            throw new NoSuchFileException(manifest.toString());
        }
    }

    private GitFileContent readFile(Project project, SavedScript script) {
        try {
            return gitRegistry.forProject(project.id()).getFileContent(script.file());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\" has no file at "
                            + script.file()
                            + " in the working tree right now — the current branch does not carry"
                            + " it, or the manifest points somewhere unreadable",
                    e);
        }
    }

    private static Stamp stamp(Path manifest) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(manifest, BasicFileAttributes.class);
            return new Stamp(attributes.lastModifiedTime().toMillis(), attributes.size());
        } catch (IOException e) {
            return Stamp.MISSING;
        }
    }

    private Project activeProject(@Nullable String projectId) {
        return projects.find(projectId).orElseGet(projects::defaultProject);
    }

    private String availableList(Project project) {
        List<SavedScript> scripts = scriptsOf(project);
        if (scripts.isEmpty()) {
            return "It saves no scripts.";
        }
        return "Saved scripts: "
                + scripts.stream().map(SavedScript::name).toList().toString()
                + ".";
    }

    /** The catalogue line, on one line and bounded: the description comes from the repository. */
    private static String describe(SavedScript script) {
        String desc = script.desc().replaceAll("\\s+", " ").strip();
        return desc.length() > MAX_DESC_CHARS ? desc.substring(0, MAX_DESC_CHARS) + "…" : desc;
    }

    private static String argsLine(SavedScript script) {
        if (script.params().isEmpty()) {
            return "no args";
        }
        return "args: "
                + String.join(
                        ", ",
                        script.params().stream()
                                .map(param -> param.name() + (param.required() ? "" : "?"))
                                .toList());
    }

    private static String sha(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Size and timestamp of the manifest — what says the parse may be reused. */
    private record Stamp(long lastModified, long size) {

        static final Stamp MISSING = new Stamp(-1, -1);

        boolean missing() {
            return size < 0;
        }
    }

    private record Cached(Stamp stamp, List<SavedScript> scripts) {}
}
