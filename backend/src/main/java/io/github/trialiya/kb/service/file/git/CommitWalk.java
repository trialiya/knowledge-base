package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.util.Iterator;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jspecify.annotations.Nullable;

/**
 * Обход истории, свежие коммиты первыми, — один на все чтения истории: листинг, поиск, исходящие
 * коммиты push. Всегда в {@code try}-with-resources: из обхода почти всегда выходят по {@code
 * break}, а {@code git.log()} отдаёт свой {@link RevWalk} как {@link Iterable}, и закрыть его
 * потом нечем.
 */
final class CommitWalk implements AutoCloseable, Iterable<RevCommit> {

    private final RevWalk walk;

    CommitWalk(Repository repository) {
        this.walk = new RevWalk(repository);
    }

    /** Откуда идти: обход видит этот коммит и всё, что от него достижимо. */
    CommitWalk from(AnyObjectId commit) throws IOException {
        walk.markStart(walk.parseCommit(commit));
        return this;
    }

    /** Что не показывать: этот коммит и всё, что от него достижимо (вторая половина {@code a..b}). */
    CommitWalk exclude(AnyObjectId commit) throws IOException {
        walk.markUninteresting(walk.parseCommit(commit));
        return this;
    }

    /**
     * Только коммиты, менявшие путь — файл или каталог; {@code null} или пустой — все. То же, что
     * {@code LogCommand.addPath}: остальные обход пропускает сам, и счёт вызывающего их не видит.
     */
    CommitWalk path(@Nullable String path) {
        if (path != null && !path.isBlank()) {
            walk.setTreeFilter(AndTreeFilter.create(
                    PathFilterGroup.createFromStrings(RepoPaths.toForwardSlashes(path.strip())), TreeFilter.ANY_DIFF));
        }
        return this;
    }

    /** Читатель объектов обхода; принадлежит обходу и закрывается вместе с ним. */
    ObjectReader reader() {
        return walk.getObjectReader();
    }

    @Override
    public Iterator<RevCommit> iterator() {
        return walk.iterator();
    }

    @Override
    public void close() {
        walk.close();
    }
}
