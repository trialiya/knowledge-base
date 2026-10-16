package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitComparison;
import io.github.trialiya.kb.model.git.dto.GitComparisonLog;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jspecify.annotations.Nullable;

/**
 * Сравнение двух ревизий: общий предок, история между ними и файлы, которыми одна отличается от
 * другой. Что значит каждое поле ответа — в {@link GitComparison}.
 */
final class RevisionCompare {

    /** Коммитов {@code head}, перечисленных поимённо; дальше — только счётчик. */
    static final int MAX_COMMITS = 100;

    /**
     * Предел счёта коммитов по каждую сторону. Ветки, разошедшиеся на годы, иначе стоили бы обхода
     * всей истории на каждое открытие сравнения, а «больше десяти тысяч» отвечает на вопрос не хуже
     * точного числа.
     */
    static final int MAX_COUNT = 10_000;

    private RevisionCompare() {}

    /**
     * @param base ревизия, с которой сравнивают
     * @param head ревизия, которую сравнивают
     * @param direct сравнивать с самой {@code base}, а не с общим предком
     * @param only оставить запись, отчитанную под этим путём (после поиска переименований — как у
     *     {@code GitService.getCommit}); {@code null} — все файлы и история между ревизиями
     * @param scope файл или каталог, которым сужено сравнение: файлы — те, у кого под ним старый или
     *     новый путь, история — коммиты, которые его меняли; {@code ""} — весь репозиторий
     * @throws IllegalArgumentException если ревизия не называет коммит
     */
    static GitComparison compare(
            Repository repository,
            String base,
            String head,
            boolean direct,
            boolean includePatch,
            @Nullable String only,
            String scope) {
        ObjectId baseId = CommitFiles.commitOf(repository, base);
        ObjectId headId = CommitFiles.commitOf(repository, head);
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectReader reader = walk.getObjectReader();
            RevCommit baseCommit = walk.parseCommit(baseId);
            RevCommit headCommit = walk.parseCommit(headId);
            @Nullable RevCommit mergeBase = mergeBase(repository, baseId, headId);
            RevCommit diffBase = direct || mergeBase == null ? baseCommit : walk.parseCommit(mergeBase);
            List<GitDiffEntry> files = diff(repository, reader, diffBase, headCommit, includePatch, only, scope);
            return new GitComparison(
                    Diffs.toGitCommit(baseCommit, null, reader, false),
                    Diffs.toGitCommit(headCommit, null, reader, false),
                    mergeBase == null ? null : mergeBase.getName(),
                    diffBase.getName(),
                    only == null ? log(repository, baseId, headId, historyPaths(scope, files)) : null,
                    files);
        } catch (IOException e) {
            throw new IllegalStateException("Failed comparing " + base + " with " + head, e);
        }
    }

    /**
     * Лучший общий предок, как его выбирает {@code git merge-base}; у слияний крест-накрест их
     * несколько, и берётся первый — тот же выбор, что у JGit при слиянии.
     */
    private static @Nullable RevCommit mergeBase(Repository repository, ObjectId base, ObjectId head)
            throws IOException {
        // Свой обход: фильтр MERGE_BASE меняет то, что обход отдаёт, и остальным чтениям он не нужен.
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(base));
            walk.markStart(walk.parseCommit(head));
            return walk.next();
        }
    }

    private static List<GitDiffEntry> diff(
            Repository repository,
            ObjectReader reader,
            RevCommit from,
            RevCommit to,
            boolean includePatch,
            @Nullable String only,
            String scope)
            throws IOException {
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, from.getTree());
        CanonicalTreeParser newTree = new CanonicalTreeParser();
        newTree.reset(reader, to.getTree());
        return Diffs.scan(repository, oldTree, newTree, includePatch, null, only, scope);
    }

    /**
     * Пути, по которым считается история суженного сравнения: сам {@code scope} и та сторона каждого
     * переноса через его границу, что лежит снаружи. Иначе список показал бы перенос со всеми
     * правками файла до него, а счётчики — один коммит переноса. Пусто — история всего репозитория.
     */
    private static List<String> historyPaths(String scope, List<GitDiffEntry> files) {
        if (scope.isEmpty()) return List.of();
        List<String> paths = new ArrayList<>();
        paths.add(scope);
        for (GitDiffEntry entry : files) {
            String old = entry.oldPath();
            if (old == null) continue;
            if (!Diffs.within(old, scope)) paths.add(old);
            if (!Diffs.within(entry.path(), scope)) paths.add(entry.path());
        }
        return paths;
    }

    private static GitComparisonLog log(Repository repository, ObjectId base, ObjectId head, List<String> paths)
            throws IOException {
        List<GitCommit> commits = new ArrayList<>();
        Count ahead = new Count();
        try (CommitWalk walk = new CommitWalk(repository)) {
            walk.from(head).exclude(base).paths(paths);
            for (RevCommit commit : walk) {
                if (!ahead.add()) break;
                if (commits.size() < MAX_COMMITS) {
                    commits.add(Diffs.toGitCommit(commit, null, walk.reader(), false));
                }
            }
        }
        Count behind = new Count();
        try (CommitWalk walk = new CommitWalk(repository)) {
            walk.from(base).exclude(head).paths(paths);
            var commit = walk.iterator();
            while (commit.hasNext() && behind.add()) {
                commit.next();
            }
        }
        return new GitComparisonLog(ahead.value, ahead.capped, behind.value, behind.capped, commits);
    }

    /** Счёт коммитов одной стороны до {@link #MAX_COUNT}; лишний коммит не считается, а отмечается. */
    private static final class Count {
        private int value;
        private boolean capped;

        /** Засчитывает ещё один коммит; {@code false} — предел уже набран, и этот за ним. */
        boolean add() {
            if (value == MAX_COUNT) {
                capped = true;
                return false;
            }
            value++;
            return true;
        }
    }
}
