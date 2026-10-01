package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitGrepResult;
import io.github.trialiya.kb.model.git.dto.GitCommitMatch;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;

/**
 * Поиск коммитов по префиксу хеша и подстроке сообщения, свежие первыми.
 *
 * <p>Git не индексирует ни то, ни другое, поэтому это линейный обход истории, ограниченный {@link
 * #SCAN} коммитами: на длинной истории запрос, которому ничего не соответствует, иначе читал бы
 * весь репозиторий.
 *
 * <p>Где искать и что отдавать, решает вызывающий ({@link Scope}). Пикер плейсхолдера показывает
 * строку на коммит — заголовок, и совпадение в теле, которого в строке не видно, читалось бы как
 * ошибочное. Страница поиска ищет и в теле и показывает его: иначе найденное в нём нечем показать.
 * Модель ищет в теле, но получает его только по просьбе: тело идёт на тысячи символов, а чтобы
 * выбрать коммит, хватает заголовка.
 */
final class CommitSearch {

    /**
     * Сколько коммитов обход просматривает, прежде чем перестать искать новые совпадения.
     *
     * <p>Цена — распаковка сообщений, а не сам обход: на истории, где в сообщение коммита попадает
     * описание PR целиком (в среднем 3 КБ), JGit тратит около 50 мкс на коммит, и промах по всему
     * пределу стоит меньше секунды. Репозитории с короткими сообщениями проходят его быстрее.
     */
    private static final int SCAN = 20_000;

    /** Потолок выдачи и листинга, и поиска; наружу — как {@link GitService#MAX_COMMITS}. */
    static final int MAX_RESULTS = 100;

    private CommitSearch() {}

    /**
     * Где искать и что отдавать.
     *
     * @param inBody искать и в теле сообщения, а не только в заголовке
     * @param withBody отдавать тело в {@link GitCommit#body()}
     * @param rev откуда начинать обход; {@code null} — от HEAD
     * @param path только коммиты, менявшие этот путь (файл или каталог); {@code null} — все. Предел
     *     обхода считает только такие коммиты: остальные отсеивает сам обход, до сравнения
     */
    record Scope(
            boolean inBody,
            boolean withBody,
            @Nullable String rev,
            @Nullable String path) {

        /** Пикер плейсхолдера: только заголовок, от HEAD, без тел. */
        static final Scope SUBJECT = new Scope(false, false, null, null);
    }

    /**
     * @param query префикс хеша или подстрока сообщения, без учёта регистра; пустой — пустой ответ:
     *     искать нечего (листинг без запроса — {@link #log})
     * @param maxCount сколько коммитов вернуть, не больше {@value #MAX_RESULTS}
     * @return совпадения и признак того, что за ними могут быть ещё: нашлось совпадение сверх
     *     лимита, или обход остановился на {@link #SCAN} раньше конца истории. Лимит, заполненный
     *     последним совпадением истории, — полная выдача: после него обход идёт дальше, до конца
     *     истории или до {@link #SCAN}, — та же цена, что у запроса без единого совпадения
     */
    static GitCommitSearchResult search(Repository repository, String query, int maxCount, Scope scope) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new GitCommitSearchResult(List.of(), false);
        Page<GitCommit> page = walk(
                repository,
                commit -> matches(commit, q, scope.inBody()),
                (commit, reader) -> Diffs.toGitCommit(commit, null, reader, scope.withBody()),
                maxCount,
                scope);
        return new GitCommitSearchResult(page.items(), page.truncated());
    }

    /**
     * Поиск страницы «Поиск»: и в заголовке, и в описании, от {@code rev} или HEAD, — и с тем, где
     * совпало, вместо описания целиком: строки описания с запросом, совпал ли заголовок, найден ли
     * коммит только по хешу. Те же правила, что у {@link #search}: пустой запрос — пустой ответ,
     * признак обрезки тот же.
     */
    static GitCommitGrepResult grep(Repository repository, String query, int maxCount, @Nullable String rev) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new GitCommitGrepResult(List.of(), false);
        Page<GitCommitMatch> page = walk(
                repository,
                commit -> matches(commit, q, true),
                (commit, reader) -> matchOf(Diffs.toGitCommit(commit, null, reader, true), q),
                maxCount,
                new Scope(true, true, rev, null));
        return new GitCommitGrepResult(page.items(), page.truncated());
    }

    /**
     * История без запроса — каждый коммит обхода совпадение, — с тем же признаком обрезки: он
     * поднят, когда за последним отданным коммитом есть ещё хотя бы один.
     *
     * @param maxCount сколько коммитов вернуть, не больше {@value #MAX_RESULTS}
     */
    static GitCommitSearchResult log(Repository repository, int maxCount, Scope scope) {
        Page<GitCommit> page = walk(
                repository,
                commit -> true,
                (commit, reader) -> Diffs.toGitCommit(commit, null, reader, scope.withBody()),
                maxCount,
                scope);
        return new GitCommitSearchResult(page.items(), page.truncated());
    }

    /** Совпадения одного обхода и признак, что за ними могут быть ещё. */
    private record Page<T>(List<T> items, boolean truncated) {}

    /** Найденный коммит → то, что уходит в ответ; читатель — обхода, закрывать его нельзя. */
    @FunctionalInterface
    private interface Found<T> {
        T of(RevCommit commit, ObjectReader reader) throws IOException;
    }

    /**
     * Где в коммите совпал запрос — тем же сравнением без учёта регистра, каким его нашли. Номер
     * строки — в описании ({@link GitCommit#body()}), считая с 1.
     */
    private static GitCommitMatch matchOf(GitCommit commit, String lowerQuery) {
        boolean subjectMatch = contains(commit.message(), lowerQuery);
        List<GitCommitMatch.Line> lines = new ArrayList<>();
        String body = commit.body();
        if (body != null) {
            String[] split = body.split("\n", -1);
            for (int i = 0; i < split.length; i++) {
                if (contains(split[i], lowerQuery)) lines.add(new GitCommitMatch.Line(i + 1, split[i]));
            }
        }
        boolean hashMatch = !subjectMatch && lines.isEmpty() && commit.hash().startsWith(lowerQuery);
        GitCommit withoutBody = new GitCommit(
                commit.hash(),
                commit.shortHash(),
                commit.author(),
                commit.email(),
                commit.date(),
                commit.message(),
                null,
                commit.files(),
                commit.parents());
        return new GitCommitMatch(withoutBody, subjectMatch, hashMatch, List.copyOf(lines));
    }

    // ObjectReader принадлежит обходу и закрывается вместе с ним; закрыть его здесь значило бы
    // выдернуть читатель из-под обхода, который ещё идёт.
    @SuppressWarnings("PMD.CloseResource")
    private static <T> Page<T> walk(
            Repository repository, Predicate<RevCommit> match, Found<T> found, int maxCount, Scope scope) {
        int limit = Math.min(Math.max(maxCount, 1), MAX_RESULTS);
        String rev = scope.rev();
        ObjectId start = rev == null || rev.isBlank() ? null : CommitFiles.commitOf(repository, rev.strip());

        try (CommitWalk walk = new CommitWalk(repository)) {
            if (start == null) start = repository.resolve(Constants.HEAD);
            // Коммитов в репозитории ещё нет — это пустая история, а не ошибка.
            if (start == null) return new Page<>(List.of(), false);
            walk.from(start).path(scope.path());

            ObjectReader reader = walk.reader();
            List<T> matches = new ArrayList<>();
            int scanned = 0;
            for (RevCommit commit : walk) {
                if (++scanned > SCAN) {
                    return new Page<>(matches, true);
                }
                if (match.test(commit)) {
                    // Только совпадение сверх лимита говорит «есть ещё»: коммит, который просто
                    // лежит дальше по истории, может ни с чем не совпасть.
                    if (matches.size() == limit) {
                        return new Page<>(matches, true);
                    }
                    matches.add(found.of(commit, reader));
                }
            }
            return new Page<>(matches, false);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read commit log", e);
        }
    }

    private static boolean matches(RevCommit commit, String lowerQuery, boolean inBody) {
        // Заголовок проверяется отдельно и в режиме с телом: перенесённый subject JGit склеивает
        // в одну строку, и запрос через перенос находится в нём, но не в полном тексте.
        return commit.getName().startsWith(lowerQuery)
                || contains(commit.getShortMessage(), lowerQuery)
                || inBody && contains(commit.getFullMessage(), lowerQuery);
    }

    private static boolean contains(String text, String lowerQuery) {
        return text.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
