package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * Дерево коммита для файлового браузера и инструментов: содержимое файла ({@code git show
 * <rev>:<path>}), листинг каталога и тип пути — всё точечными чтениями, без обхода дерева целиком.
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
     *     коммите нет — в том числе когда по нему лежит символьная ссылка, подмодуль или
     *     неназываемое имя, которых для браузера не существует; по пути лежит каталог, а не файл;
     *     объект больше {@code MAX_BLOB_SIZE}
     */
    static Blob read(Repository repository, String rev, String path) {
        try (Commit commit = Commit.open(repository, rev)) {
            Entry entry = commit.entry(path);
            if (entry.kind() == Kind.MISSING) {
                throw new IllegalArgumentException(
                        "File not found in " + commit.name() + ": " + path);
            }
            if (entry.kind() != Kind.FILE) {
                // Каталог: содержимого, которое имеет смысл показывать как файл, у него нет.
                throw new IllegalArgumentException("Not a file in " + commit.name() + ": " + path);
            }
            return commit.blob(entry, path);
        }
    }

    /**
     * Коммит, открытый на время одного ответа: ревизия разобрана один раз, и один читатель объектов
     * обслуживает все обращения — тип пути, листинги каталогов-предков, содержимое. Иначе ответ
     * браузера на глубокий путь набирал бы по разбору ревизии и читателю на каждый уровень.
     *
     * <p>Ничего из дерева заранее не читается: каждый вопрос стоит ровно того, о чём спросили —
     * {@code forPath} встаёт на путь, листинг читает один каталог, — и цена ответа не зависит от
     * размера репозитория.
     */
    static final class Commit implements AutoCloseable {
        private final RevWalk walk;
        private final RevCommit rev;

        private Commit(RevWalk walk, RevCommit rev) {
            this.walk = walk;
            this.rev = rev;
        }

        /**
         * @param rev что угодно, что git понимает как коммит: хеш, ветка, тег, {@code HEAD~2}
         * @throws IllegalArgumentException коммит не найден или неоднозначен
         */
        // RevWalk живёт столько же, сколько этот объект: его закрывает close().
        static Commit open(Repository repository, String rev) {
            RevWalk walk = new RevWalk(repository);
            try {
                return new Commit(walk, walk.parseCommit(resolve(repository, rev)));
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                walk.close();
                throw new IllegalArgumentException("Commit not found: " + rev, e);
            } catch (AmbiguousObjectException e) {
                walk.close();
                throw new IllegalArgumentException("Ambiguous commit reference: " + rev, e);
            } catch (IOException e) {
                walk.close();
                throw new IllegalStateException("Failed resolving " + rev, e);
            } catch (RuntimeException e) {
                walk.close();
                throw e;
            }
        }

        /** Полный хеш коммита. */
        String name() {
            return rev.name();
        }

        /**
         * Что лежит по пути: файл, каталог или ничего. Символьная ссылка, подмодуль и неназываемое
         * имя отвечают «ничего» — по тому же правилу, по которому они не показываются в листингах:
         * обещать путь, который на клик ответит отказом, нельзя. Каталог отвечает каталогом, даже
         * если открывать под ним нечего: это видно по его пустому {@link #children листингу}, и
         * платить за отдельный обход поддерева здесь незачем.
         *
         * @param path нормализованный путь, {@code ""} — корень
         */
        Entry entry(String path) {
            if (path.isEmpty()) {
                return new Entry(Kind.DIRECTORY, null);
            }
            try (TreeWalk tree = TreeWalk.forPath(reader(), path, rev.getTree())) {
                if (tree == null) {
                    return Entry.MISSING;
                }
                FileMode mode = tree.getFileMode(0);
                ObjectId id = tree.getObjectId(0);
                if (mode == FileMode.TREE) {
                    return new Entry(Kind.DIRECTORY, id);
                }
                if ((mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE)
                        && RepoPaths.isNameable(path)) {
                    return new Entry(Kind.FILE, id);
                }
                return Entry.MISSING;
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading " + path + " at " + rev.name(), e);
            }
        }

        /**
         * Прямые потомки одного каталога — то, что запрашивает шеврон в браузере. Читается только
         * сам каталог: раскрытие одного узла не должно стоить обхода всего дерева коммита, иначе N
         * раскрытий — это N полных обходов. Порядок здесь git'овый, порядок выдачи назначает {@link
         * RepoBrowse}.
         *
         * @param dir нормализованный путь каталога, {@code ""} — корень
         * @return потомки каталога; пустой список, если такого каталога в коммите нет или по этому
         *     пути лежит файл
         */
        List<Child> children(String dir) {
            try {
                ObjectId root = dir.isEmpty() ? rev.getTree() : subtree(reader(), rev, dir);
                return root == null ? List.of() : list(root, dir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed listing " + dir + " at " + rev.name(), e);
            }
        }

        /**
         * То же самое для каталога, который уже нашёл {@link #entry}: его объект известен, дерево
         * второй раз не читается.
         *
         * @return потомки каталога; пустой список, если запись — не каталог
         */
        List<Child> children(Entry entry, String dir) {
            ObjectId tree = entry.blob();
            if (entry.kind() != Kind.DIRECTORY) {
                return List.of();
            }
            if (tree == null) {
                return children(dir);
            }
            try {
                return list(tree, dir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed listing " + dir + " at " + rev.name(), e);
            }
        }

        /** Потомки каталога, объект которого уже известен. */
        private List<Child> list(ObjectId root, String dir) throws IOException {
            ObjectReader reader = reader();
            List<Child> children = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(reader)) {
                tree.addTree(root);
                tree.setRecursive(false);
                while (tree.next()) {
                    String name = tree.getNameString();
                    String path = dir.isEmpty() ? name : dir + "/" + name;
                    FileMode mode = tree.getFileMode(0);
                    if (mode == FileMode.TREE) {
                        // Каталог виден ровно потому, что под ним лежит файл, который мы умеем
                        // открыть и назвать обратно в API. Каталог из одних символьных ссылок,
                        // подмодулей и неназываемых имён раскрылся бы пустым — обещание,
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
        }

        /**
         * Содержимое файла, найденного {@link #entry}: объект уже известен, дерево второй раз не
         * читается.
         *
         * @throws IllegalArgumentException объект больше {@code MAX_BLOB_SIZE}
         */
        Blob blob(Entry entry, String path) {
            ObjectId id = entry.blob();
            if (entry.kind() != Kind.FILE || id == null) {
                throw new IllegalArgumentException("Not a file in " + rev.name() + ": " + path);
            }
            try {
                return load(reader(), id, rev.name(), path);
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading " + path + " at " + rev.name(), e);
            }
        }

        // Читатель принадлежит RevWalk и закрывается вместе с ним в close(): закрыть его раньше
        // значило бы вынуть его из-под ещё живого обхода.
        private ObjectReader reader() {
            return walk.getObjectReader();
        }

        @Override
        public void close() {
            walk.close();
        }
    }

    /** Чем путь является в коммите. */
    enum Kind {
        FILE,
        DIRECTORY,
        MISSING
    }

    /**
     * Запись по пути в коммите.
     *
     * @param blob объект за путём: блоб у файла, дерево у каталога, null у корня и у отсутствующего
     */
    record Entry(Kind kind, @Nullable ObjectId blob) {
        static final Entry MISSING = new Entry(Kind.MISSING, null);
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
