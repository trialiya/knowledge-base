package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

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

    /**
     * Самый большой объект, который поднимается в память ради ответа. Много больше того, что
     * инструмент отдаёт ({@link RepoFiles#MAX_FILE_SIZE}): усечённый ответ показывает начало и
     * конец файла, поэтому прочитать его приходится целиком, — но не настолько, чтобы один блоб из
     * истории (дамп, собранный артефакт, случайно закоммиченный архив) положил бэкенд.
     */
    private static final long MAX_BLOB_SIZE = 32L * 1024 * 1024;

    private CommitFiles() {}

    /**
     * Содержимое {@code path} в дереве {@code rev}.
     *
     * @param rev что угодно, что git понимает как коммит: полный или короткий хеш, имя ветки, тег,
     *     {@code HEAD~2}
     * @return байты объекта и хеш коммита, который их отдал
     * @throws IllegalArgumentException коммит не найден или неоднозначен; такого пути в этом
     *     коммите нет; по пути лежит каталог или подмодуль, а не файл; объект больше {@code
     *     MAX_BLOB_SIZE}
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
                return load(walk.getObjectReader(), tree.getObjectId(0), commit.name(), path);
            }
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + rev, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading " + path + " at " + rev, e);
        }
    }

    /**
     * Все файлы дерева коммита — то, из чего файловый браузер строит дерево на ревизии.
     *
     * <p>Размеры здесь не читаются: у блоба размер лежит в заголовке объекта, но спрашивать его у
     * каждого файла репозитория ради листинга одного каталога — это тысячи обращений к базе
     * объектов на запрос. Поэтому снимок несёт id объектов, а размер узнаётся у тех путей, которые
     * действительно попали в выдачу (см. {@link Snapshot#sizeOf}).
     *
     * @param rev что угодно, что git понимает как коммит: хеш, ветка, тег, {@code HEAD~2}
     * @throws IllegalArgumentException коммит не найден или неоднозначен
     */
    static Snapshot tree(Repository repository, String rev) {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(resolve(repository, rev));
            Map<String, ObjectId> blobs = new LinkedHashMap<>();
            try (TreeWalk tree = new TreeWalk(repository)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    // В снимок попадает ровно то, что read() умеет отдать файлом. Подмодуль
                    // (GITLINK) содержимого в этом репозитории не имеет вовсе; у символьной
                    // ссылки блоб — это путь, на который она указывает, а не содержимое цели,
                    // и открыть её как файл нельзя. Показать их в дереве и отказать по клику
                    // было бы обещанием, которого не сдержать: отказ уносит и само дерево.
                    FileMode mode = tree.getFileMode(0);
                    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue;
                    // Имя, которое нельзя назвать обратно в API, из снимка выпадает — по тому же
                    // правилу, по которому оно выпадает из рабочего дерева (см.
                    // VisibleFiles#all и RepoPaths#isNameable): показать его значило бы
                    // предложить файл, который на клик ответит отказом.
                    String path = tree.getPathString();
                    if (!RepoPaths.isNameable(path)) continue;
                    blobs.put(path, tree.getObjectId(0));
                }
            }
            return new Snapshot(commit.name(), List.copyOf(blobs.keySet()), Map.copyOf(blobs));
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + rev, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed listing the tree at " + rev, e);
        }
    }

    /**
     * Прямые потомки одного каталога в дереве коммита — то, что запрашивает шеврон в браузере.
     *
     * <p>Читается только сам каталог: раскрытие одного узла не должно стоить обхода всего дерева
     * коммита, иначе N раскрытий — это N полных обходов. Порядок здесь git'овый, порядок выдачи
     * назначает {@link RepoBrowse}.
     *
     * @param dir нормализованный путь каталога, {@code ""} — корень
     * @return потомки каталога; пустой список, если такого каталога в коммите нет или по этому пути
     *     лежит файл
     * @throws IllegalArgumentException коммит не найден или неоднозначен
     */
    // Читатель ниже принадлежит RevWalk и закрывается вместе с ним: закрыть его здесь значило бы
    // вынуть его из-под ещё живого обхода.
    @SuppressWarnings("PMD.CloseResource")
    static List<Child> children(Repository repository, String rev, String dir) {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(resolve(repository, rev));
            // Один читатель на весь ответ: иначе их набирается по одному на каждый подкаталог,
            // ради проверки, есть ли под ним файл.
            ObjectReader reader = walk.getObjectReader();
            ObjectId root = dir.isEmpty() ? commit.getTree() : subtree(reader, commit, dir);
            if (root == null) {
                return List.of();
            }
            List<Child> children = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(reader)) {
                tree.addTree(root);
                tree.setRecursive(false);
                while (tree.next()) {
                    String name = tree.getNameString();
                    String path = dir.isEmpty() ? name : dir + "/" + name;
                    FileMode mode = tree.getFileMode(0);
                    if (mode == FileMode.TREE) {
                        // Каталог показывается по тому же правилу, что и в снимке всего дерева
                        // (см. tree()): он там виден ровно потому, что под ним лежит файл, который
                        // мы умеем открыть и назвать обратно в API. Каталог из одних символьных
                        // ссылок, подмодулей и неназываемых имён раскрылся бы пустым — обещание,
                        // которого не сдержать.
                        if (holdsFile(reader, tree.getObjectId(0), path)) {
                            children.add(new Child(path, name, true, -1));
                        }
                    } else if ((mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE)
                            && RepoPaths.isNameable(path)) {
                        children.add(
                                new Child(path, name, false, size(reader, tree.getObjectId(0))));
                    }
                }
            }
            return List.copyOf(children);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + rev, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed listing " + dir + " at " + rev, e);
        }
    }

    /** Объект дерева по пути внутри коммита, либо null — такого каталога там нет. */
    private static @Nullable ObjectId subtree(ObjectReader reader, RevCommit commit, String dir)
            throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(reader, dir, commit.getTree())) {
            if (tree == null || tree.getFileMode(0) != FileMode.TREE) {
                return null;
            }
            return tree.getObjectId(0);
        }
    }

    /**
     * Лежит ли под этим деревом хоть один файл, который мы умеем открыть и назвать. Обход
     * прерывается на первом же таком файле, поэтому у обычного каталога это несколько записей, а не
     * всё поддерево.
     *
     * @param dir путь самого каталога: имя проверяется целиком, а обход знает только имена внутри
     */
    private static boolean holdsFile(ObjectReader reader, ObjectId treeId, String dir)
            throws IOException {
        try (TreeWalk tree = new TreeWalk(reader)) {
            tree.addTree(treeId);
            tree.setRecursive(true);
            while (tree.next()) {
                FileMode mode = tree.getFileMode(0);
                if ((mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE)
                        && RepoPaths.isNameable(dir + "/" + tree.getPathString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Размер блоба по данным git, или -1, если его не удалось спросить. */
    private static long size(ObjectReader reader, ObjectId id) {
        try {
            return reader.getObjectSize(id, Constants.OBJ_BLOB);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Потомок каталога в дереве коммита.
     *
     * @param size размер файла в байтах, у каталога -1
     */
    record Child(String path, String name, boolean directory, long size) {}

    /**
     * Дерево коммита целиком: пути в порядке обхода (он же порядок git — по путям) и объект за
     * каждым из них.
     *
     * @param commit полный хеш коммита, отдавшего снимок
     */
    record Snapshot(String commit, List<String> paths, Map<String, ObjectId> blobs) {

        /**
         * Размер файла по данным git, или -1 у пути, которого в этом коммите нет.
         *
         * <p>Читатель приходит снаружи и им же закрывается: размеры спрашивают по одному, для
         * каждого пути в листинге, и свой читатель на путь означал бы их открытие и закрытие
         * десятками на запрос вместо одного.
         */
        long sizeOf(ObjectReader reader, String path) {
            ObjectId id = blobs.get(path);
            return id == null ? -1 : CommitFiles.size(reader, id);
        }

        /**
         * Содержимое файла из этого же снимка: объект найден обходом дерева, поэтому ни коммит, ни
         * дерево второй раз не разбираются — в отличие от {@link CommitFiles#read}, которому
         * ревизию нужно ещё разрешить.
         *
         * <p>Читатель, как и в {@link #sizeOf}, приходит снаружи и им же закрывается.
         *
         * @throws IllegalArgumentException такого файла в снимке нет или объект больше {@code
         *     MAX_BLOB_SIZE}
         */
        Blob blobAt(ObjectReader reader, String path) {
            ObjectId id = blobs.get(path);
            if (id == null) {
                throw new IllegalArgumentException("File not found in " + commit + ": " + path);
            }
            try {
                return load(reader, id, commit, path);
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading " + path + " at " + commit, e);
            }
        }
    }

    /**
     * Ревизия в коммит: тем же отказом, что и обзор дерева, если названное коммитом не является.
     * Без этого дерево (у него разбор идёт через {@code parseCommit}) отвечало бы на хеш дерева или
     * блоба 400, а история на тот же вход — 500.
     */
    static ObjectId commitOf(Repository repository, String rev) {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(resolve(repository, rev));
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + rev, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed resolving " + rev, e);
        }
    }

    /**
     * Блоб по уже найденному объекту.
     *
     * @param reader читатель объектов; закрывает его тот, кто открыл
     * @param commit полный хеш коммита, из дерева которого взят объект
     * @throws IllegalArgumentException объект больше {@link #MAX_BLOB_SIZE}
     */
    private static Blob load(ObjectReader reader, ObjectId id, String commit, String path)
            throws IOException {
        ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
        long size = loader.getSize();
        if (size > MAX_BLOB_SIZE) {
            // Отказ, а не усечение: ответ строится из начала И конца файла, и прочитать конец, не
            // подняв в память всё остальное, нельзя. Размер объекта известен до чтения, поэтому
            // граница проходит здесь, а не по факту нехватки памяти.
            throw new IllegalArgumentException(
                    "Too large to read from history: "
                            + path
                            + " at "
                            + commit
                            + " is "
                            + size
                            + " bytes (limit "
                            + MAX_BLOB_SIZE
                            + ")");
        }
        // Через поток, а не getBytes(): тот отказывает по своему порогу
        // (core.streamFileThreshold), то есть по настройке репозитория, а не по нашей. Поток
        // закрывается: у большого объекта за ним стоит окно пака и inflater из пула JGit, и они
        // возвращаются в пул только по close().
        try (ObjectStream stream = loader.openStream()) {
            return new Blob(commit, stream.readAllBytes(), size);
        }
    }

    /** Ревизия в объект, либо {@link IllegalArgumentException} — «такой ревизии нет». */
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
