package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.orDefault;
import static io.github.trialiya.kb.tools.ToolArgs.positiveOrDefault;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;
import static io.github.trialiya.kb.tools.ToolArgs.requireValue;

import io.github.trialiya.kb.model.git.dto.GitBlameAnswer;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.tool.ToolResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Read-only tools over a repository's history: the commit log, a commit's diff, who last changed
 * lines ({@code getBlame}) and when a text entered a line ({@code getLineOrigin}). Which
 * repository a call reads, and the {@code project} argument every tool takes, are {@link
 * GitReads}.
 */
@Slf4j
public class GitHistoryFunction {

    /** Хешей в одном {@code getCommitDiff}: больше — это уже выгрузка истории, а не вопрос о ней. */
    static final int MAX_DIFF_COMMITS = 20;

    private final GitReads reads;

    public GitHistoryFunction(GitRegistry gitRegistry) {
        this.reads = new GitReads(gitRegistry);
    }

    // ── Commit history ───────────────────────────────────────────────────────

    /**
     * Returns recent commit history from the repository.
     *
     * @param maxCount maximum number of commits to return (default 20, max {@value GitService#MAX_COMMITS})
     * @param filePath optional — show only commits that touched this file
     * @param query optional — only commits whose message (subject or description) contains it, or
     *     whose hash starts with it
     * @param includeMessageBody include each commit's message below the subject (default false)
     * @return list of commits with hash, author, date, and message
     */
    @Tool(
            description =
                    "Recent commit history (newest first). Commit: hash, shortHash, author, date (ISO-8601), message (subject only; full text in \"body\" with includeMessageBody). With query, only commits whose subject or description contains it (case-insensitive) or whose hash starts with it; the search looks at the latest 20000 commits only (of filePath's history when set); \"truncated\": true means there is more — without query, history goes on past the last commit; with query, a match was found past maxCount or the walk hit that bound before history ended, so an empty or short result means none there, not none ever. Use getCommitDiff to see file changes. When mentioning a commit in your response, link it as [shortHash](/files?rev=HASH&project=ID), where HASH is the full hash and ID is the response's project field.",
            resultConverter = CompactToolResultConverter.class)
    public ToolResult<List<GitCommit>> getCommitLog(
            ToolContext context,
            @ToolParam(
                            description = "Maximum commits to return (1–" + GitService.MAX_COMMITS + ", default 20).",
                            required = false)
                    @Nullable
                    Integer maxCount,
            @ToolParam(
                            description =
                                    "Optional: file path (relative to repo root) to filter commits that touched it.",
                            required = false)
                    @Nullable
                    String filePath,
            @ToolParam(
                            description = "Optional: text to find in commit messages — subject and"
                                    + " description — or a hash prefix. The"
                                    + " description is searched even without"
                                    + " includeMessageBody.",
                            required = false)
                    @Nullable
                    String query,
            @ToolParam(
                            description = "Add each commit's full message body in \"body\". Turn on"
                                    + " carefully, it may contain long bodies.",
                            required = false)
                    @Nullable
                    Boolean includeMessageBody,
            @ToolParam(description = GitReads.PROJECT_PARAM, required = false) @Nullable String project) {
        final int limit = positiveOrDefault(maxCount, 20);
        final boolean withBody = orDefault(includeMessageBody, false);
        log.debug(
                "getCommitLog called: maxCount={}, filePath='{}', query='{}'," + " includeMessageBody={}, project='{}'",
                limit,
                filePath,
                query,
                withBody,
                project);
        GitService git = reads.git(context, project);
        if (query == null || query.isBlank()) {
            GitCommitSearchResult commitLog = git.getCommitLog(limit, filePath, withBody);
            log.debug("getCommitLog called: commitLog={}", commitLog);
            return GitReads.answer(git, commitLog.commits(), commitLog.truncated());
        }
        GitCommitSearchResult found = git.searchCommitLog(query.strip(), limit, filePath, withBody);
        log.debug("getCommitLog called: found={}", found);
        return GitReads.answer(git, found.commits(), found.truncated());
    }

    // ── Commit diff ─────────────────────────────────────────────────────────

    /**
     * Returns the list of changed files (and optionally unified diff patches) for one or more
     * commits.
     *
     * @param commitHashes one or more commit hashes, comma-separated
     * @param includePatch if true, include the unified diff text for each file
     * @return list of commits with their changed files
     */
    @Tool(
            description =
                    "Changed files and diffs for one or more commits (at most 20). A single hash also returns that commit's full message in \"body\". Files include status (A/M/D/R), path, additions, deletions, and optional unified diff; all patches of one answer together stop at 3000 lines, a file past that keeps its counts and says how to get its patch. Link a commit as [shortHash](/files?rev=HASH&project=ID) with the full hash and the response's project field.",
            resultConverter = CompactToolResultConverter.class)
    public ToolResult<List<GitCommit>> getCommitDiff(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Commit hash (full or short) or comma-separated list (e.g., \"abc1234\" or \"abc1234,def5678\").")
                    String commitHashes,
            @ToolParam(
                            description =
                                    "Include unified diff for each file (false=list only, true=includes patch text).",
                            required = false)
                    @Nullable
                    Boolean includePatch,
            @ToolParam(description = "Optional: file path to filter diff output to only that file.", required = false)
                    @Nullable
                    String filePath,
            @ToolParam(description = GitReads.PROJECT_PARAM, required = false) @Nullable String project) {
        requireText(commitHashes, "commitHashes");
        final boolean patch = orDefault(includePatch, false);
        log.debug(
                "getCommitDiff called: hashes='{}', includePatch={}, filePath='{}', project='{}'",
                commitHashes,
                patch,
                filePath,
                project);
        long count = Arrays.stream(commitHashes.split(","))
                .filter(hash -> !hash.isBlank())
                .count();
        if (count > MAX_DIFF_COMMITS) {
            throw new IllegalArgumentException("At most " + MAX_DIFF_COMMITS + " commits per call, got " + count
                    + " — split the list into several calls.");
        }
        GitService git = reads.git(context, project);
        List<GitCommit> commitDiff = PatchBudget.ofCommits(git.getCommitDiff(commitHashes, patch, filePath));
        log.debug("getCommitDiff called: commitDiff={}", commitDiff);
        return GitReads.answer(git, commitDiff);
    }

    /**
     * Who last changed the given lines — {@code git blame -L}, with the revisions {@code
     * .git-blame-ignore-revs} names skipped; see {@link GitService#getBlame(String, String, Integer,
     * Integer)}.
     *
     * <p>Answers with the hunks alone, not the text of the lines: the model comes here about lines
     * it has already read, and the description points it on from a hunk to the commit that wrote
     * it. A long history is cut at {@link BlameBudget#MAX_HUNKS} hunks, with {@code truncated} and
     * the answer's {@code toLine} saying where to go on from. Each commit is described once, not on
     * every hunk ({@link GitBlameAnswer}).
     *
     * <p>In a shallow clone the oldest commit takes every line older than the clone; the commit says
     * {@code boundary} so the model does not name it the author (see {@link GitFileBlame.Hunk}).
     *
     * @param filePath path relative to repo root
     * @param fromLine first line (1-based, inclusive); null for start of file
     * @param toLine last line (1-based, inclusive); null for end of file
     * @param commit revision to blame the file as of; null blames the working tree
     */
    @Tool(
            description = "Who last changed each line of a file (git blame). Answers with hunks —"
                    + " consecutive lines from one commit: fromLine, lineCount, hash, and"
                    + " path + sourceLine (the file's path and the hunk's first line in that"
                    + " commit — they differ after a rename or after edits above; absent means"
                    + " the same as the file's path and fromLine). Each commit is described once"
                    + " in \"commits\", keyed by the full hash: author, date, summary (commit"
                    + " subject). A hunk with no hash is an uncommitted edit."
                    + " \"boundary\": true marks a commit at the cut of a shallow clone: its"
                    + " lines are as old as that commit or older, and who wrote them is not in"
                    + " this clone (no author, no summary) — say so, do not credit the commit,"
                    + " and do not diff it: with no parent here, its diff shows every line added."
                    + " Commits named in .git-blame-ignore-revs (mass reformatting) are skipped,"
                    + " so their lines go to whoever changed them before. Give fromLine/toLine"
                    + " for the lines you care about: the history of a range is walked, not"
                    + " the whole file. lineCount at the top is the whole file's length."
                    + " When a given text entered the line — getLineOrigin, not a chain of"
                    + " blames."
                    + " \"truncated\": true means more hunks past toLine — continue from the"
                    + " next line. Why a change was made (not boundary): getCommitDiff with the hash and"
                    + " filePath=path (the hunk's: after a rename the current one is not in"
                    + " that commit). The lines as that commit wrote them: getFileContent with"
                    + " commit=hash, filePath=path, fromLine=sourceLine. Untracked and binary"
                    + " files have no blame. Link a commit as"
                    + " [shortHash](/files?rev=HASH&project=ID) with the full hash and the"
                    + " response's project field.",
            resultConverter = CompactToolResultConverter.class)
    public ToolResult<GitBlameAnswer> getBlame(
            ToolContext context,
            @ToolParam(description = "File path relative to repo root.") String filePath,
            @ToolParam(description = "First line (1-based, inclusive). Null for start of file.", required = false)
                    @Nullable
                    Integer fromLine,
            @ToolParam(description = "Last line (1-based, inclusive). Null for end of file.", required = false)
                    @Nullable
                    Integer toLine,
            @ToolParam(
                            description = "Optional: blame the file as of this commit instead of the"
                                    + " working tree — a full or short hash, a branch, a"
                                    + " tag, or a revision like HEAD~2. Give the path as"
                                    + " it was spelled in that commit.",
                            required = false)
                    @Nullable
                    String commit,
            @ToolParam(description = GitReads.PROJECT_PARAM, required = false) @Nullable String project) {
        requireText(filePath, "filePath");
        log.debug(
                "getBlame called: filePath='{}', fromLine={}, toLine={}, commit='{}', project='{}'",
                filePath,
                fromLine,
                toLine,
                commit,
                project);
        GitService git = reads.git(context, project);
        GitFileBlame blame = git.getBlame(commit, filePath, fromLine, toLine);
        log.debug("getBlame called: {} hunks over lines {}-{}", blame.hunks().size(), blame.fromLine(), blame.toLine());
        return GitReads.answer(git, GitBlameAnswer.of(BlameBudget.cap(blame)), BlameBudget.cut(blame));
    }

    /**
     * The commit where a substring entered one line of a file — a walk back through that line's
     * versions while each still holds the substring; see {@link GitService#getLineOrigin}.
     *
     * <p>A question {@link #getBlame} answers badly: blame names the last commit to touch the line,
     * and a chain of blames by hand drifts — git pairs a changed line by similarity and follows a
     * neighbour that holds the same text into its history. The walk here picks the earlier version
     * by the substring itself.
     *
     * @param filePath path relative to repo root
     * @param line the line (1-based) as numbered in the working tree, or in {@code commit} when given
     * @param query a literal substring of that line, matched case-insensitively
     * @param commit revision the line is numbered in; null for the working tree
     */
    @Tool(
            description = "When a substring entered one line of a file: walks back through that"
                    + " line's versions while each still holds the substring, and names the commit"
                    + " that put it there. Not who last touched the line (getBlame), and not where"
                    + " the text first appeared anywhere in the repository. query is a literal"
                    + " substring of that line (case-insensitive, no regex) — the identifier,"
                    + " call or value the question is about, not the whole line. status: FOUND —"
                    + " the last of steps is the commit that brought it into the line, before is"
                    + " the line just before (no before: that commit added the line itself);"
                    + " UNCOMMITTED — an uncommitted edit put it there, before is the line in"
                    + " HEAD (no before: the edit added the line); NOT_IN_LINE — the line does not hold it (re-read the file, the"
                    + " numbering may have moved); BOUNDARY — the walk reached the cut of a"
                    + " shallow clone: the substring is at least that old and who added it is not"
                    + " in this clone, say so; LIMIT — stopped at its step or time limit, the"
                    + " last step is the oldest reached. steps run newest to oldest: hash, author,"
                    + " date, summary, and path + line + text — the line as it read in that"
                    + " commit, under that commit's path and numbering. A step with"
                    + " \"boundary\": true sits at a shallow clone's cut and has no author or"
                    + " summary: do not credit that commit. Why it was added: getCommitDiff with"
                    + " the origin's hash and filePath=its path. Link a commit as"
                    + " [shortHash](/files?rev=HASH&project=ID) with the full hash and the"
                    + " response's project field.",
            resultConverter = CompactToolResultConverter.class)
    public ToolResult<GitLineOrigin> getLineOrigin(
            ToolContext context,
            @ToolParam(description = "File path relative to repo root.") String filePath,
            @ToolParam(
                            description = "The line (1-based), numbered as getFileContent or grepContent"
                                    + " showed it — in commit when that is given.")
                    Integer line,
            @ToolParam(description = "Literal substring of that line to trace (case-insensitive).") String query,
            @ToolParam(
                            description = "Optional: the revision the line is numbered in — a full or short"
                                    + " hash, a branch, a tag, or HEAD~2. Omit for the"
                                    + " working tree, uncommitted edits included.",
                            required = false)
                    @Nullable
                    String commit,
            @ToolParam(description = GitReads.PROJECT_PARAM, required = false) @Nullable String project) {
        requireText(filePath, "filePath");
        requireValue(line, "line");
        requireText(query, "query");
        log.debug(
                "getLineOrigin called: filePath='{}', line={}, query='{}', commit='{}', project='{}'",
                filePath,
                line,
                query,
                commit,
                project);
        GitService git = reads.git(context, project);
        GitLineOrigin origin = git.getLineOrigin(commit, filePath, line, query);
        log.debug(
                "getLineOrigin called: {} after {} steps",
                origin.status(),
                origin.steps().size());
        return GitReads.answer(git, origin);
    }
}
