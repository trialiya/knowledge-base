package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for read-only Git repository operations.
 *
 * <p>All commands run against the repository at {@code kb.project-path}. Files matched by {@code
 * .gitignore} are excluded from tree/search results via {@code git ls-files} and {@code git log}
 * (which inherently respect ignore rules).
 */
@Slf4j
@Service
public class GitService {

    private static final long MAX_FILE_SIZE = 512 * 1024; // 512 KB — skip huge files
    private static final int MAX_DIFF_LINES = 500; // truncate very large diffs

    /** Bytes inspected when sniffing for binary content (a NUL byte ⇒ binary). */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /** File names to always exclude from uncommitted changes (OS/IDE junk). */
    private static final java.util.Set<String> IGNORED_FILES =
            java.util.Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".directory");

    /** File extensions to always exclude from uncommitted changes. */
    private static final java.util.Set<String> IGNORED_EXTENSIONS =
            java.util.Set.of(
                    ".class", ".jar", ".war", ".ear", ".o", ".so", ".dylib", ".dll", ".exe", ".pyc",
                    ".pyo", ".swp", ".swo", ".bak", ".tmp", ".orig");

    /** Record separator used in custom git log format. */
    private static final String RS = "\u001e";

    /** Unit separator for fields inside one record. */
    private static final String US = "\u001f";

    private final Path repoPath;

    public GitService(@Value("${kb.project-path}") String projectPath) {
        this.repoPath = Path.of(projectPath).toAbsolutePath().normalize();
        log.info("GitService initialised for repo: {}", repoPath);
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Returns tracked files/directories under {@code subPath} (or repo root if null). Uses {@code
     * git ls-files} so .gitignore'd files are automatically excluded.
     */
    public List<GitFileNode> getFileTree(@Nullable String subPath) {
        String base = normalizeSub(subPath);
        // git ls-files lists tracked files; untracked/ignored are excluded.
        List<String> args = new ArrayList<>(List.of("git", "ls-files", "--full-name"));
        if (!base.isEmpty()) {
            args.add(base);
        }
        List<String> lines = exec(args);

        // Build a de-duplicated list: files + their direct parent directories
        // relative to the requested subPath.
        var nodes = new java.util.LinkedHashMap<String, GitFileNode>();
        int prefixLen = base.isEmpty() ? 0 : base.length() + 1; // +1 for trailing '/'

        for (String line : lines) {
            if (line.length() <= prefixLen) continue;
            String relative = line.substring(prefixLen);
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                // It's inside a subdirectory — add directory node
                String dirName = relative.substring(0, slash);
                String dirPath = base.isEmpty() ? dirName : base + "/" + dirName;
                nodes.putIfAbsent(dirPath, new GitFileNode(dirPath, dirName, "directory", null));
            } else {
                // Direct file
                long size = fileSize(line);
                nodes.putIfAbsent(line, new GitFileNode(line, relative, "file", size));
            }
        }
        return new ArrayList<>(nodes.values());
    }

    // ── Commit history ───────────────────────────────────────────────────────

    /**
     * Returns recent commit history.
     *
     * @param maxCount max commits to return (default 20, capped at 100)
     * @param filePath optional — limit history to a specific file
     */
    public List<GitCommit> getCommitLog(int maxCount, @Nullable String filePath) {
        int limit = Math.min(Math.max(maxCount, 1), 100);
        // Format: hash, short hash, author, email, ISO date, subject — separated by US
        String format = String.join(US, "%H", "%h", "%an", "%ae", "%aI", "%s");
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "git",
                                "log",
                                "--format=" + RS + format,
                                "-n",
                                String.valueOf(limit)));
        if (filePath != null && !filePath.isBlank()) {
            args.add("--");
            args.add(filePath.strip());
        }
        String raw = String.join("\n", exec(args));
        List<GitCommit> commits = new ArrayList<>();
        for (String record : raw.split(RS)) {
            if (record.isBlank()) continue;
            String[] f = record.strip().split(US, -1);
            if (f.length < 6) continue;
            commits.add(new GitCommit(f[0], f[1], f[2], f[3], parseDate(f[4]), f[5], null));
        }
        return commits;
    }

    // ── Diff for commit(s) ──────────────────────────────────────────────────

    /**
     * Returns changed files with optional unified diff for one or more commits.
     *
     * @param commitHashes comma-separated commit hashes
     * @param includePatch whether to include unified diff text
     */
    public List<GitCommit> getCommitDiff(@NonNull String commitHashes, boolean includePatch) {
        List<GitCommit> result = new ArrayList<>();
        for (String hash : commitHashes.split(",")) {
            String h = hash.strip();
            if (h.isEmpty()) continue;
            result.add(diffForSingleCommit(h, includePatch));
        }
        return result;
    }

    private GitCommit diffForSingleCommit(String hash, boolean includePatch) {
        // Commit metadata
        String format = String.join(US, "%H", "%h", "%an", "%ae", "%aI", "%s");
        List<String> metaLines = exec(List.of("git", "log", "-1", "--format=" + format, hash));
        String meta = String.join("", metaLines).strip();
        String[] f = meta.split(US, -1);
        if (f.length < 6) {
            throw new IllegalArgumentException("Commit not found: " + hash);
        }

        // Changed files with stats
        List<String> statLines =
                exec(
                        List.of(
                                "git",
                                "diff-tree",
                                "--no-commit-id",
                                "-r",
                                "--numstat",
                                "--find-renames",
                                hash));

        // Optionally get patches
        List<String> patchLines =
                includePatch
                        ? exec(
                                List.of(
                                        "git",
                                        "diff-tree",
                                        "--no-commit-id",
                                        "-r",
                                        "-p",
                                        "--find-renames",
                                        hash))
                        : Collections.emptyList();
        var patches =
                includePatch
                        ? parsePatchBlocks(patchLines)
                        : Collections.<String, String>emptyMap();

        List<GitDiffEntry> entries = new ArrayList<>();
        for (String line : statLines) {
            if (line.isBlank()) continue;
            // numstat format: additions\tdeletions\tpath  (or old\tnew for renames)
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int add = parseStat(parts[0]);
            int del = parseStat(parts[1]);
            String pathPart = parts[2];
            String oldPath = null;
            String filePath;
            // Rename: "old => new" or "{old => new}/rest"
            if (pathPart.contains(" => ")) {
                String[] rp = parseRenamePath(pathPart);
                oldPath = rp[0];
                filePath = rp[1];
            } else {
                filePath = pathPart;
            }
            String status =
                    oldPath != null
                            ? "R"
                            : (add > 0 && del == 0 ? "A" : (add == 0 && del > 0 ? "D" : "M"));
            String patch = patches.getOrDefault(filePath, patches.get(oldPath));
            if (patch != null && patch.lines().count() > MAX_DIFF_LINES) {
                patch =
                        patch.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                                + "\n... (truncated)";
            }
            entries.add(new GitDiffEntry(status, filePath, oldPath, add, del, patch));
        }

        return new GitCommit(f[0], f[1], f[2], f[3], parseDate(f[4]), f[5], entries);
    }

    // ── File search ─────────────────────────────────────────────────────────

    /**
     * Searches tracked file names by glob/substring.
     *
     * @param pattern search pattern (substring match, case-insensitive)
     * @param maxResults capped at 50
     */
    public List<GitFileNode> searchFiles(@NonNull String pattern, int maxResults) {
        int limit = Math.min(Math.max(maxResults, 1), 50);
        List<String> allFiles = exec(List.of("git", "ls-files", "--full-name"));
        String lower = pattern.toLowerCase();
        return allFiles.stream()
                .filter(f -> f.toLowerCase().contains(lower))
                .limit(limit)
                .map(
                        f -> {
                            String name = f.contains("/") ? f.substring(f.lastIndexOf('/') + 1) : f;
                            return new GitFileNode(f, name, "file", fileSize(f));
                        })
                .toList();
    }

    // ── File content ────────────────────────────────────────────────────────

    /**
     * Returns the content of a <b>tracked</b> file, optimised for AI/LLM consumption.
     *
     * <p>Only files tracked by Git are served. Untracked files (those returned by {@code git
     * ls-files --others}) are explicitly rejected even though they exist on disk: their content is
     * unreviewed working-tree state and serving it would both leak arbitrary local files and feed
     * unverified data to the model. Ignored files (via {@code .gitignore}) are untracked by
     * definition and therefore rejected too.
     *
     * <p>Content retrieval is single-pass: the file is read once into a byte buffer, binary content
     * is detected inline (NUL-byte heuristic) instead of spawning an extra {@code git diff
     * --no-index} subprocess, and the bytes are decoded as UTF-8 only when textual. Binary and
     * oversized files return no content so they never bloat the model's context.
     */
    public GitFileContent getFileContent(@NonNull String filePath) {
        String normalized = filePath.strip();

        // Security: confine to the repo before touching the filesystem.
        Path absolute = repoPath.resolve(normalized).normalize();
        if (!absolute.startsWith(repoPath)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }

        // Only tracked files may be served. `git ls-files -- <path>` prints the path on stdout
        // iff Git tracks it, and prints nothing otherwise (untracked, ignored, or missing) — with
        // no stderr noise, which matters because exec() merges stderr into its returned lines. We
        // then disambiguate untracked-but-present from genuinely-missing so the caller (and the
        // AI) gets a precise, intentional rejection rather than a vague "not found".
        List<String> tracked =
                exec(List.of("git", "ls-files", "--full-name", "-z", "--", normalized));
        boolean isTracked =
                tracked.stream()
                        .flatMap(l -> java.util.Arrays.stream(l.split("\0")))
                        .anyMatch(p -> p.equals(normalized));
        if (!isTracked) {
            if (Files.exists(absolute)) {
                // Exists on disk but Git does not track it → untracked or .gitignore'd.
                throw new IllegalArgumentException(
                        "Refusing to read untracked file: " + normalized);
            }
            throw new IllegalArgumentException("File not found: " + normalized);
        }

        long size;
        try {
            size = Files.size(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file size: " + normalized, e);
        }

        if (size > MAX_FILE_SIZE) {
            return new GitFileContent(
                    normalized, "(file too large: " + size + " bytes)", false, size);
        }

        // Single read: detect binary inline, decode text only if appropriate. This avoids the
        // extra `git diff --no-index` process the previous implementation spawned per call.
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }

        if (isBinary(bytes)) {
            return new GitFileContent(normalized, null, true, size);
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        return new GitFileContent(normalized, content, false, size);
    }

    /**
     * Heuristic binary detection matching Git's own behaviour: a file is treated as binary if a NUL
     * byte appears within the first {@value #BINARY_SNIFF_BYTES} bytes. Cheap and allocation-free,
     * and accurate for the source/text files an AI assistant is asked to read.
     */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // ── Uncommitted changes ────────────────────────────────────────────────

    /**
     * Returns uncommitted changes in the working tree, excluding files matched by {@code
     * .gitignore}.
     *
     * <p>Covers three categories:
     *
     * <ul>
     *   <li><b>Modified/deleted tracked files</b> (both staged and unstaged) — via {@code git diff
     *       HEAD --numstat}
     *   <li><b>New untracked files</b> not ignored — via {@code git ls-files --others
     *       --exclude-standard}
     * </ul>
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch) {
        List<GitDiffEntry> entries = new ArrayList<>();

        // 1) Staged + unstaged modifications vs HEAD
        List<String> statLines =
                exec(List.of("git", "diff", "HEAD", "--numstat", "--find-renames"));

        List<String> patchLines =
                includePatch
                        ? exec(List.of("git", "diff", "HEAD", "-p", "--find-renames"))
                        : Collections.emptyList();
        var patches =
                includePatch
                        ? parsePatchBlocks(patchLines)
                        : Collections.<String, String>emptyMap();

        for (String line : statLines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int add = parseStat(parts[0]);
            int del = parseStat(parts[1]);
            String pathPart = parts[2];
            String oldPath = null;
            String filePath;
            if (pathPart.contains(" => ")) {
                String[] rp = parseRenamePath(pathPart);
                oldPath = rp[0];
                filePath = rp[1];
            } else {
                filePath = pathPart;
            }
            if (isJunkFile(filePath)) continue;

            String status;
            if (oldPath != null) {
                status = "R";
            } else {
                // Check if file exists on disk to distinguish delete from modify
                boolean existsOnDisk = Files.exists(repoPath.resolve(filePath));
                status = !existsOnDisk ? "D" : (add > 0 && del == 0 ? "A" : "M");
            }

            String patch = patches.getOrDefault(filePath, patches.get(oldPath));
            if (patch != null && patch.lines().count() > MAX_DIFF_LINES) {
                patch =
                        patch.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                                + "\n... (truncated)";
            }
            entries.add(new GitDiffEntry(status, filePath, oldPath, add, del, patch));
        }

        // 2) Untracked files (not ignored by .gitignore)
        List<String> untrackedFiles =
                exec(List.of("git", "ls-files", "--others", "--exclude-standard"));

        for (String file : untrackedFiles) {
            if (file.isBlank()) continue;
            // Skip if already covered by diff HEAD (shouldn't happen, but be safe)
            String f = file.strip();
            if (isJunkFile(f)) continue;
            if (entries.stream().anyMatch(e -> f.equals(e.path()))) continue;

            long size = fileSize(f);
            int lineCount = 0;
            if (size > 0 && size <= MAX_FILE_SIZE) {
                try {
                    lineCount =
                            (int) Files.lines(repoPath.resolve(f), StandardCharsets.UTF_8).count();
                } catch (IOException | UncheckedIOException ignored) {
                    // binary or unreadable — leave lineCount as 0
                }
            }
            entries.add(new GitDiffEntry("A", f, null, lineCount, 0, null));
        }

        return entries;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns {@code true} for OS/IDE artefacts that should never appear in results. */
    private static boolean isJunkFile(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (IGNORED_FILES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IGNORED_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    private List<String> exec(List<String> command) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(command)
                            .directory(repoPath.toFile())
                            .redirectErrorStream(true);
            Process process = pb.start();
            List<String> lines;
            try (var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                String output = String.join("\n", lines);
                log.warn("Git command exited {}: {} → {}", exit, command, output);
                // For some commands non-zero is expected (e.g. diff --no-index)
                // so we still return whatever output we got.
            }
            return lines;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    private String normalizeSub(@Nullable String sub) {
        if (sub == null || sub.isBlank()) return "";
        String s = sub.strip().replaceAll("^/+|/+$", "");
        if (s.contains("..")) throw new IllegalArgumentException("Path traversal not allowed");
        return s;
    }

    private long fileSize(String relativePath) {
        try {
            return Files.size(repoPath.resolve(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }

    private static OffsetDateTime parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseStat(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses git rename path formats: "a => b", "{a => b}/rest", "prefix/{a => b}/suffix" */
    private static String[] parseRenamePath(String raw) {
        Pattern p = Pattern.compile("^(.*?)\\{(.+?) => (.+?)}(.*)$");
        Matcher m = p.matcher(raw);
        if (m.matches()) {
            String prefix = m.group(1);
            String suffix = m.group(4);
            return new String[] {prefix + m.group(2) + suffix, prefix + m.group(3) + suffix};
        }
        // Simple "old => new"
        String[] parts = raw.split(" => ", 2);
        return parts.length == 2 ? parts : new String[] {raw, raw};
    }

    /**
     * Splits unified diff output into per-file patch blocks. Key = new file path from "b/..."
     * header.
     */
    private static java.util.Map<String, String> parsePatchBlocks(List<String> lines) {
        var map = new java.util.LinkedHashMap<String, String>();
        String currentFile = null;
        var buf = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                if (currentFile != null) {
                    map.put(currentFile, buf.toString());
                }
                buf.setLength(0);
                // Extract b/path from "diff --git a/old b/new"
                int bIdx = line.lastIndexOf(" b/");
                currentFile = bIdx >= 0 ? line.substring(bIdx + 3) : null;
            }
            buf.append(line).append('\n');
        }
        if (currentFile != null) {
            map.put(currentFile, buf.toString());
        }
        return map;
    }
}
