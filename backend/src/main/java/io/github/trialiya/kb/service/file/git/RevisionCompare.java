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
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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
        @Nullable Pathspec within = scope.isEmpty() ? null : new Pathspec(null, scope);
        ObjectId baseId = CommitFiles.commitOf(repository, base);
        ObjectId headId = CommitFiles.commitOf(repository, head);
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectReader reader = walk.getObjectReader();
            RevCommit baseCommit = walk.parseCommit(baseId);
            RevCommit headCommit = walk.parseCommit(headId);
            @Nullable RevCommit mergeBase = mergeBase(repository, baseId, headId);
            RevCommit diffBase = direct || mergeBase == null ? baseCommit : walk.parseCommit(mergeBase);
            List<GitDiffEntry> files = diff(repository, reader, diffBase, headCommit, includePatch, only, within);
            return new GitComparison(
                    Diffs.toGitCommit(baseCommit, null, reader, false),
                    Diffs.toGitCommit(headCommit, null, reader, false),
                    mergeBase == null ? null : mergeBase.getName(),
                    diffBase.getName(),
                    only == null ? log(repository, baseId, headId, historyPaths(within, files)) : null,
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
            @Nullable Pathspec scope)
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
     * правками файла до него, а счётчики — один коммит переноса. Копия истории источника не несёт:
     * источник живёт дальше своей жизнью, и его правки к сужению отношения не имеют. Пусто — история
     * всего репозитория.
     */
    private static List<String> historyPaths(@Nullable Pathspec scope, List<GitDiffEntry> files) {
        if (scope == null) return List.of();
        List<String> paths = new ArrayList<>();
        paths.add(scope.literal());
        for (GitDiffEntry entry : files) {
            String old = entry.oldPath();
            if (old == null || !"R".equals(entry.status())) continue;
            if (!scope.matches(old)) paths.add(old);
            if (!scope.matches(entry.path())) paths.add(entry.path());
        }
        return paths;
    }

    private static GitComparisonLog log(Repository repository, ObjectId base, ObjectId head, List<String> paths)
            throws IOException {
        Side ahead = side(repository, head, base, paths, MAX_COMMITS);
        Side behind = side(repository, base, head, paths, 0);
        return new GitComparisonLog(
                ahead.count(), ahead.truncated(), behind.count(), behind.truncated(), ahead.commits());
    }

    /** Одна сторона истории: сколько коммитов, упёрся ли счёт в предел, и первые {@code listed} из них. */
    private record Side(int count, boolean truncated, List<GitCommit> commits) {}

    /**
     * Коммиты {@code from}, которых нет в {@code exclude}, — по {@code paths}, если они есть. Предел
     * {@link #MAX_COUNT} — на просмотренные коммиты, а не на подошедшие: фильтр путей у самого обхода
     * JGit ставит впереди любого своего, и редко менявшийся файл заставил бы обойти всю историю
     * диапазона, чтобы найти в ней несколько коммитов. Упёршийся в предел счёт — нижняя граница.
     */
    private static Side side(Repository repository, ObjectId from, ObjectId exclude, List<String> paths, int listed)
            throws IOException {
        List<GitCommit> commits = new ArrayList<>();
        int count = 0;
        int seen = 0;
        try (CommitWalk walk = new CommitWalk(repository);
                TreeWalk changes = new TreeWalk(repository, walk.reader())) {
            walk.from(from).exclude(exclude);
            if (!paths.isEmpty()) {
                changes.setRecursive(true);
                changes.setFilter(AndTreeFilter.create(PathFilterGroup.createFromStrings(paths), TreeFilter.ANY_DIFF));
            }
            for (RevCommit commit : walk) {
                if (seen++ == MAX_COUNT) return new Side(count, true, commits);
                if (!paths.isEmpty() && !touches(walk, changes, commit)) continue;
                count++;
                if (commits.size() < listed) commits.add(Diffs.toGitCommit(commit, null, walk.reader(), false));
            }
        }
        return new Side(count, false, commits);
    }

    /**
     * Менял ли коммит пути фильтра {@code changes}: отличается ли от каждого родителя, как коммит,
     * который {@code git log -- path} не считает совпавшим с историей. Слияние, принёсшее чужую
     * правку, ей не автор — её коммит обход встретит сам.
     */
    private static boolean touches(CommitWalk walk, TreeWalk changes, RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            changes.reset(commit.getTree());
            return changes.next();
        }
        for (RevCommit parent : commit.getParents()) {
            changes.reset(walk.parsed(parent).getTree(), commit.getTree());
            if (!changes.next()) return false;
        }
        return true;
    }
}
