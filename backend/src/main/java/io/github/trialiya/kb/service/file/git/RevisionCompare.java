package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitComparison;
import io.github.trialiya.kb.model.git.dto.GitComparisonLog;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
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
     * @throws IllegalArgumentException если ревизия не называет коммит
     */
    static GitComparison compare(
            Repository repository,
            String base,
            String head,
            boolean direct,
            boolean includePatch,
            @Nullable String only) {
        ObjectId baseId = CommitFiles.commitOf(repository, base);
        ObjectId headId = CommitFiles.commitOf(repository, head);
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectReader reader = walk.getObjectReader();
            RevCommit baseCommit = walk.parseCommit(baseId);
            RevCommit headCommit = walk.parseCommit(headId);
            @Nullable RevCommit mergeBase = mergeBase(repository, baseId, headId);
            RevCommit diffBase = direct || mergeBase == null ? baseCommit : walk.parseCommit(mergeBase);
            List<GitDiffEntry> files = diff(repository, reader, diffBase, headCommit, includePatch, only);
            return new GitComparison(
                    Diffs.toGitCommit(baseCommit, null, reader, false),
                    Diffs.toGitCommit(headCommit, null, reader, false),
                    mergeBase == null ? null : mergeBase.getName(),
                    diffBase.getName(),
                    only == null ? log(repository, baseId, headId) : null,
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
            @Nullable String only)
            throws IOException {
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, from.getTree());
        CanonicalTreeParser newTree = new CanonicalTreeParser();
        newTree.reset(reader, to.getTree());
        List<GitDiffEntry> entries = new ArrayList<>();
        var patchOut = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(patchOut)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                if (only != null && !only.equals(Diffs.reportedPath(entry))) continue;
                entries.add(Diffs.toGitDiffEntry(entry, formatter, includePatch, patchOut));
            }
        }
        return entries;
    }

    private static GitComparisonLog log(Repository repository, ObjectId base, ObjectId head) throws IOException {
        List<GitCommit> commits = new ArrayList<>();
        Count ahead = new Count();
        try (CommitWalk walk = new CommitWalk(repository)) {
            walk.from(head).exclude(base);
            for (RevCommit commit : walk) {
                if (!ahead.add()) break;
                if (commits.size() < MAX_COMMITS) {
                    commits.add(Diffs.toGitCommit(commit, null, walk.reader(), false));
                }
            }
        }
        Count behind = new Count();
        try (CommitWalk walk = new CommitWalk(repository)) {
            walk.from(base).exclude(head);
            var commit = walk.iterator();
            while (commit.hasNext() && behind.add()) {
                commit.next();
            }
        }
        return new GitComparisonLog(ahead.value, behind.value, ahead.capped || behind.capped, commits);
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
