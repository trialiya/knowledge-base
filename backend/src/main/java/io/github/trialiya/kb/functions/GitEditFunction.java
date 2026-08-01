package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that let the chat model <b>modify</b> the working tree of the repository at
 * {@code kb.git.project-path}: {@link #createFile} and {@link #editFile}. Nothing is ever committed
 * — changes stay uncommitted for the user to review ({@code getUncommittedChanges}) and commit.
 *
 * <p>Registered as a bean only when {@code kb.git.edit-enabled=true} <em>and</em> the working tree
 * is actually writable (see {@code ChatConfig#gitEditFunction}); in read-only mode (e.g. a ro
 * volume mount) these tools are simply absent from the model's tool list.
 *
 * <p><b>Read-before-edit guard.</b> {@code editFile} is rejected unless the target file was "seen"
 * earlier in the same chat-response session (tracked by the request-scoped {@link
 * ToolInvocationCollector}). Deliberately permissive about <em>how</em> it was seen — a partial
 * read (line range / outline) or a search hit is enough, because the exact-match {@code oldString}
 * contract already forces the model to quote real current content:
 *
 * <ul>
 *   <li>a read tool was called with this {@code filePath} argument, or
 *   <li>any successful tool result (grep/search/diff/...) mentions this path.
 * </ul>
 */
@Slf4j
@AllArgsConstructor
public class GitEditFunction {

    /** Tools whose {@code filePath} argument means the model deliberately looked at this file. */
    private static final Set<String> PATH_ARG_READ_TOOLS =
            Set.of("getFileContent", "getFileOutline", "editFile");

    private final GitService gitService;

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
            @ToolParam(
                            description =
                                    "Path of the new file relative to repo root (e.g., "
                                            + "\"src/main/java/com/example/New.java\").")
                    String filePath,
            @ToolParam(description = "Full content of the new file (UTF-8).") String content) {
        log.info("createFile called: filePath='{}', {} chars", filePath, content.length());
        return gitService.createFile(filePath, content);
    }

    @Tool(
            description =
                    """
                    Surgical edit of an existing tracked file: replace oldString with newString. \
                    oldString must appear EXACTLY once (unless replaceAll=true) and match character-for-character, \
                    including whitespace and line breaks — read current content first (getFileContent, getFileOutline, or grep result). \
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
        log.info(
                "editFile called: filePath='{}', old {} chars, new {} chars, replaceAll={}",
                filePath,
                oldString.length(),
                newString.length(),
                replaceAll);
        requireFileSeenInThisResponse(context, filePath);
        return gitService.editFile(filePath, oldString, newString, Boolean.TRUE.equals(replaceAll));
    }

    /**
     * Rejects an edit when nothing in this chat-response session shows the model has actually seen
     * the target file. Counts as "seen": a successful read-tool call with the same {@code filePath}
     * argument, or any successful tool call whose raw result text contains the path (covers grep,
     * search, diffs, tree listings — including partial reads of a single method). Skipped when no
     * {@link ToolInvocationCollector} is present (background jobs, tests).
     */
    private static void requireFileSeenInThisResponse(ToolContext context, String filePath) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null) {
            return;
        }
        final String path = filePath.strip().replace('\\', '/');
        final boolean seen =
                collector.snapshot().stream()
                        .filter(
                                inv ->
                                        ToolInvocationCollector.ToolInvocationStatus.OK
                                                == inv.status())
                        .anyMatch(
                                inv ->
                                        (PATH_ARG_READ_TOOLS.contains(inv.name())
                                                        && path.equals(
                                                                String.valueOf(
                                                                        inv.arguments()
                                                                                .get("filePath"))))
                                                || (inv.resultText() != null
                                                        && inv.resultText().contains(path)));
        if (!seen) {
            throw new IllegalStateException(
                    "File "
                            + path
                            + " was NOT modified: its content was not read in this response. "
                            + "Read the file first — getFileContent(filePath=\""
                            + path
                            + "\") (can be a line range), getFileOutline, or find the needed "
                            + "fragment via grepContent — then retry editFile.");
        }
    }
}
