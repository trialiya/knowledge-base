package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.orDefault;
import static io.github.trialiya.kb.tools.ToolArgs.requireContent;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ProjectContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that let the chat model <b>modify</b> the working tree of a configured
 * repository: {@link #createFile} and {@link #editFile}. Nothing is ever committed — changes stay
 * uncommitted for the user to review ({@code getUncommittedChanges}) and commit.
 *
 * <p>Registered as a bean only when at least one project accepts writes — configured for it
 * <em>and</em> its working tree actually writable (see {@code ChatConfig#gitEditFunction}); with
 * none, e.g. a ro volume mount, these tools are simply absent from the model's tool list. Which
 * project a call writes to comes from the run's {@link ToolContext} ({@code ProjectContext}), and
 * one that does not accept writes is refused by name through {@code GitRegistry#requireEditable} —
 * the bean's presence only says <em>some</em> project is writable.
 *
 * <p><b>What makes an edit safe is the exact-match contract, not a prior read.</b> {@code
 * oldString} must occur in the file exactly once (unless {@code replaceAll}), character for
 * character: a model able to quote such a fragment is quoting the current content, and one that
 * cannot is refused with the name of the tool to read with. So {@code editFile} asks nothing of the
 * response's tool history — do not add a read-before-edit check back on top of it, here or in the
 * sandbox's {@code kb.edit}, which follows the same contract. A prior read is required only where
 * no such contract exists: the whole-content {@code kb.writeBytes} ({@code
 * ScriptSession#requireRead}).
 */
@Slf4j
@AllArgsConstructor
public class GitEditFunction {

    private final GitRegistry gitRegistry;

    /** The repository this call writes to; refuses a project that does not accept writes. */
    private GitService editable(@Nullable ToolContext context) {
        return gitRegistry.requireEditable(ProjectContext.from(context));
    }

    @Tool(
            description =
                    """
                    Create a NEW file in the repository working tree and stage it (git add). \
                    Fails if file exists (use editFile for modifications) or path is .gitignore'd. \
                    Changes are NOT committed — user reviews and commits. \
                    Returns: operation, path, additions, lineCount.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitEditResult createFile(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Path of the new file relative to repo root (e.g., "
                                            + "\"src/main/java/com/example/New.java\").")
                    String filePath,
            @ToolParam(description = "Full content of the new file (UTF-8).") String content) {
        requireText(filePath, "filePath");
        // "" is a deliberate empty file; absent means the model forgot the body, and writing the
        // file empty would look like success while losing everything it meant to put there.
        requireContent(content, "content");
        log.info("createFile called: filePath='{}', {} chars", filePath, content.length());
        return editable(context).createFile(filePath, content);
    }

    @Tool(
            description =
                    """
                    Surgical edit of an existing tracked file: replace oldString with newString. \
                    oldString must appear EXACTLY once (unless replaceAll=true) and match character-for-character, \
                    including whitespace and line breaks — quote it from real current content (getFileContent, getFileOutline, or a grep result). \
                    No prior read required — the exact match is the safety check. \
                    Changes are NOT committed. Returns: operation, path, additions, deletions, lineCount, diff.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public GitEditResult editFile(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Exact file path relative to repo root (e.g., "
                                            + "\"src/main/java/com/example/App.java\").")
                    String filePath,
            @ToolParam(
                            description =
                                    "Exact existing text fragment to replace (character-for-character, including whitespace). "
                                            + "Must be unique in the file — add surrounding lines if ambiguous.")
                    String oldString,
            @ToolParam(
                            description =
                                    "New text to replace oldString. Empty string to delete the fragment.")
                    String newString,
            @ToolParam(
                            description =
                                    "Replace ALL occurrences of oldString (true) or exactly one (false, default).",
                            required = false)
                    @Nullable Boolean replaceAll) {
        requireText(filePath, "filePath");
        // Not requireText: a fragment made only of whitespace is a legitimate (if unlikely) edit,
        // and the exactly-once rule below rejects it far more precisely than a blank check would.
        requireContent(oldString, "oldString");
        // Empty newString deletes the fragment — documented, and the reason absent cannot mean the
        // same thing: defaulting it to "" would turn a forgotten argument into a silent deletion.
        requireContent(newString, "newString");
        final boolean all = orDefault(replaceAll, false);
        log.info(
                "editFile called: filePath='{}', old {} chars, new {} chars, replaceAll={}",
                filePath,
                oldString.length(),
                newString.length(),
                all);
        return editable(context).editFile(filePath, oldString, newString, all);
    }
}
