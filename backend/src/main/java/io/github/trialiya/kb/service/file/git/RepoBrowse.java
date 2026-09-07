package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitPathView;
import io.github.trialiya.kb.model.git.dto.GitTreeLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Раскладка плоского списка путей в дерево файлового браузера.
 *
 * <p>Источник у списка бывает разный — индекс рабочего дерева (плюс {@code allow-globs}) или дерево
 * коммита, — а раскладка одна: браузер показывает те же узлы, тот же порядок и те же предки, чем бы
 * ни был снимок. Поэтому здесь нет ни репозитория, ни ревизии: только пути, признак «git про этот
 * путь знает» и способ узнать размер файла. Кто эти пути собрал, решает вызывающий.
 */
final class RepoBrowse {

    /** Tree listing order: directories first, then by name, case-insensitively. */
    private static final Comparator<GitFileNode> NODE_ORDER =
            Comparator.<GitFileNode, Boolean>comparing(n -> FileEntryType.DIRECTORY != n.type())
                    .thenComparing(GitFileNode::name, String.CASE_INSENSITIVE_ORDER);

    private RepoBrowse() {}

    /**
     * Снимок, который раскладывается в дерево.
     *
     * @param paths каждый видимый путь
     * @param tracked те из них, про которые знает git — в снимке коммита это все
     * @param sizeOf размер файла в байтах; спрашивается только у путей, попавших в выдачу, потому
     *     что у рабочего дерева это обращение к диску, а у коммита — к базе объектов
     */
    record Snapshot(List<String> paths, Set<String> tracked, ToLongFunction<String> sizeOf) {}

    /** Узлы одного листинга в порядке браузера: каталоги, потом файлы, внутри — по имени. */
    static List<GitFileNode> ordered(List<GitFileNode> nodes) {
        return nodes.stream().sorted(NODE_ORDER).toList();
    }

    /** Прямые потомки одного каталога ({@code ""} — корень). */
    static List<GitFileNode> tree(Snapshot snapshot, String base) {
        return listDirectories(snapshot, Set.of(base)).getOrDefault(base, List.of());
    }

    /**
     * Lists several directories in a single pass over the paths.
     *
     * <p>Each path is walked segment by segment; whenever a prefix of it is one of the requested
     * {@code bases}, the child at that level (a subdirectory or the file itself) is added to that
     * base's listing. So the whole ancestor chain of a deeply nested file costs one scan instead of
     * one full scan per level.
     *
     * <p>A node is reported as untracked when nothing tracked passes through it: the file itself is
     * only admitted by {@code allow-globs}, or the directory holds no tracked file at all. That is
     * what the file browser greys out, and what tells the model the path carries no history.
     *
     * @param bases directory paths to list ("" — repo root); paths that are not directories simply
     *     come back with an empty listing
     */
    static Map<String, List<GitFileNode>> listDirectories(Snapshot snapshot, Set<String> bases) {
        Set<String> tracked = snapshot.tracked();
        // Directory nodes de-duplicate by path (many files share one subdirectory), hence the
        // LinkedHashMap per base rather than a plain list.
        Map<String, LinkedHashMap<String, GitFileNode>> acc = new LinkedHashMap<>();
        for (String base : bases) {
            acc.put(base, new LinkedHashMap<>());
        }

        for (String path : snapshot.paths()) {
            boolean isTracked = tracked.contains(path);
            int from = 0;
            while (true) {
                int slash = path.indexOf('/', from);
                String dir = from == 0 ? "" : path.substring(0, from - 1);
                LinkedHashMap<String, GitFileNode> bucket = acc.get(dir);
                if (bucket != null) {
                    if (slash >= 0) {
                        String name = path.substring(from, slash);
                        String dirPath = dir.isEmpty() ? name : dir + "/" + name;
                        GitFileNode node =
                                new GitFileNode(
                                        dirPath, name, FileEntryType.DIRECTORY, null, isTracked);
                        // A directory counts as tracked as soon as one tracked file runs through
                        // it, whichever order the paths arrive in.
                        bucket.merge(dirPath, node, (old, fresh) -> old.tracked() ? old : fresh);
                    } else {
                        String name = path.substring(from);
                        bucket.putIfAbsent(
                                path,
                                new GitFileNode(
                                        path,
                                        name,
                                        FileEntryType.FILE,
                                        snapshot.sizeOf().applyAsLong(path),
                                        isTracked));
                    }
                }
                if (slash < 0) break;
                from = slash + 1;
            }
        }

        Map<String, List<GitFileNode>> result = new LinkedHashMap<>();
        acc.forEach(
                (base, nodes) ->
                        result.put(base, nodes.values().stream().sorted(NODE_ORDER).toList()));
        return result;
    }

    /**
     * Один путь со всем, что нужно браузеру, чтобы его открыть: чем путь является, его содержимое
     * или листинг, и листинги каталогов-предков для раскрытия дерева слева.
     *
     * @param target нормализованный путь ({@code ""} — корень)
     * @param commit хеш коммита, из которого читается снимок, либо null для рабочего дерева
     * @param contentOf содержимое файла — спрашивается, только если путь оказался файлом
     */
    static GitPathView browse(
            Snapshot snapshot,
            String target,
            boolean includeAncestors,
            @Nullable String commit,
            Function<Boolean, @Nullable GitFileContent> contentOf) {
        @Nullable FileEntryType type = resolvePathType(target, snapshot.paths());

        List<String> ancestors = includeAncestors ? ancestorDirs(target) : List.of();
        Set<String> bases = new LinkedHashSet<>(ancestors);
        boolean isDirectory = type == FileEntryType.DIRECTORY;
        if (isDirectory) bases.add(target);
        Map<String, List<GitFileNode>> listings =
                bases.isEmpty() ? Map.of() : listDirectories(snapshot, bases);

        List<GitTreeLevel> tree =
                ancestors.stream()
                        .map(dir -> new GitTreeLevel(dir, listings.getOrDefault(dir, List.of())))
                        .toList();

        boolean targetTracked = snapshot.tracked().contains(target);

        return new GitPathView(
                target,
                type,
                // The path is vouched for by the `tracked` list resolvePathType() just read, so
                // re-checking it via isTracked() would re-read the index for nothing.
                type == FileEntryType.FILE ? contentOf.apply(targetTracked) : null,
                isDirectory ? listings.getOrDefault(target, List.of()) : null,
                tree,
                commit,
                // The root and any missing path count as tracked: there is nothing to warn about.
                target.isEmpty()
                        || type == null
                        || targetTracked
                        || isTrackedPrefix(snapshot.tracked(), target));
    }

    /** Whether any tracked file lives under {@code dir} — the directory form of the membership. */
    private static boolean isTrackedPrefix(Set<String> tracked, String dir) {
        String prefix = dir + "/";
        return tracked.stream().anyMatch(p -> p.startsWith(prefix));
    }

    /**
     * {@code FILE}, {@code DIRECTORY} or {@code null} for missing — the repo root is a directory.
     */
    private static @Nullable FileEntryType resolvePathType(String path, List<String> paths) {
        if (path.isEmpty()) return FileEntryType.DIRECTORY;
        String prefix = path + "/";
        for (String candidate : paths) {
            if (candidate.equals(path)) return FileEntryType.FILE;
            if (candidate.startsWith(prefix)) return FileEntryType.DIRECTORY;
        }
        return null;
    }

    /**
     * Directories from the repo root down to {@code path}'s parent; {@code path} itself is never
     * included — for the root that means no ancestors at all, not a self-reference.
     */
    private static List<String> ancestorDirs(String path) {
        if (path.isEmpty()) return List.of();
        List<String> dirs = new ArrayList<>();
        dirs.add("");
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            dirs.add(path.substring(0, slash));
        }
        return dirs;
    }
}
