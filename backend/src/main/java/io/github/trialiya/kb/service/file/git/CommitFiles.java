package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Чтение файла из дерева коммита — то, что в командной строке пишется {@code git show
 * <rev>:<path>}.
 *
 * <p>История отвечает не так, как рабочее дерево: файл существует в одних коммитах и отсутствует в
 * других, у него нет ни размера на диске, ни статуса «отслеживается» (в коммите он по определению
 * отслеживается), а по одному и тому же пути в разных коммитах лежат разные объекты. Поэтому это
 * отдельный путь чтения, а не флаг у чтения с диска.
 */
final class CommitFiles {

    private CommitFiles() {}

    /**
     * Содержимое {@code path} в дереве {@code rev}.
     *
     * @param rev что угодно, что git понимает как коммит: полный или короткий хеш, имя ветки, тег,
     *     {@code HEAD~2}
     * @return байты объекта и хеш коммита, который их отдал
     * @throws IllegalArgumentException коммит не найден или неоднозначен; такого пути в этом
     *     коммите нет; по пути лежит каталог или подмодуль, а не файл
     */
    static Blob read(Repository repository, String rev, String path) {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(resolve(repository, rev));
            // forPath идёт по дереву коммита и встаёт ровно на этот путь (или возвращает null) —
            // обходить дерево целиком, как делает listing, здесь нечего.
            try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (tree == null) {
                    throw new IllegalArgumentException(
                            "File not found in " + commit.name() + ": " + path);
                }
                FileMode mode = tree.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                    // Каталог, подмодуль или символьная ссылка: содержимого, которое имеет смысл
                    // показывать как файл, у них нет.
                    throw new IllegalArgumentException(
                            "Not a file in " + commit.name() + ": " + path);
                }
                ObjectLoader loader = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                // Через поток, а не getBytes(): тот отказывается отдавать объект, который счёл
                // большим, а усечённый ответ строится из начала И конца файла — конец нужен
                // целиком, ровно как при чтении такого же файла с диска.
                return new Blob(
                        commit.name(), loader.openStream().readAllBytes(), loader.getSize());
            }
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + rev, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading " + path + " at " + rev, e);
        }
    }

    private static ObjectId resolve(Repository repository, String rev) throws IOException {
        ObjectId id;
        try {
            id = repository.resolve(rev);
        } catch (RevisionSyntaxException e) {
            throw new IllegalArgumentException("Invalid commit reference: " + rev, e);
        }
        if (id == null) {
            throw new IllegalArgumentException("Commit not found: " + rev);
        }
        return id;
    }

    /**
     * @param commit полный хеш коммита, отдавшего содержимое — короткий хеш или {@code HEAD~2} в
     *     ответе были бы неповторимы завтра
     * @param bytes содержимое объекта
     * @param size размер объекта по данным git — он же длина {@code bytes}
     */
    record Blob(String commit, byte[] bytes, long size) {}
}
