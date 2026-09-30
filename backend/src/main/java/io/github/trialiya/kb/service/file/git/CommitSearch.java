package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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

    /** Сколько коммитов обход просматривает, прежде чем перестать искать новые совпадения. */
    private static final int SCAN = 2000;

    /** Потолок выдачи — столько, сколько не отдаёт и история ({@code GET /commits}). */
    private static final int MAX_RESULTS = 100;

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
    record Scope(boolean inBody, boolean withBody, @Nullable String rev, @Nullable String path) {

        /** Пикер плейсхолдера: только заголовок, от HEAD, без тел. */
        static final Scope SUBJECT = new Scope(false, false, null, null);
    }

    /**
     * @param query префикс хеша или подстрока сообщения, без учёта регистра
     * @param maxCount сколько коммитов вернуть, не больше {@value #MAX_RESULTS}
     * @return совпадения и признак того, что обход остановился раньше конца истории — на лимите
     *     выдачи или на {@link #SCAN}
     */
    // ObjectReader принадлежит RevWalk и закрывается вместе с ним; закрыть его здесь значило бы
    // выдернуть читатель из-под обхода, который ещё идёт.
    @SuppressWarnings("PMD.CloseResource")
    static GitCommitSearchResult search(
            Repository repository, String query, int maxCount, Scope scope) {
        if (query.isBlank()) return new GitCommitSearchResult(List.of(), false);
        String q = query.strip().toLowerCase(Locale.ROOT);
        int limit = Math.min(Math.max(maxCount, 1), MAX_RESULTS);
        String rev = scope.rev();
        ObjectId start =
                rev == null || rev.isBlank() ? null : CommitFiles.commitOf(repository, rev.strip());
        String path = scope.path();

        // Обход строим сами, а не через git.log(): LogCommand отдаёт свой RevWalk как
        // Iterable, и закрыть его уже нечем — а выходим мы отсюда почти всегда по break.
        try (RevWalk walk = new RevWalk(repository)) {
            if (start == null) start = repository.resolve(Constants.HEAD);
            // Коммитов в репозитории ещё нет — это пустая история, а не ошибка.
            if (start == null) return new GitCommitSearchResult(List.of(), false);
            walk.markStart(walk.parseCommit(start));
            if (path != null && !path.isBlank()) {
                // То же, что делает LogCommand.addPath: коммит, не менявший путь, обход пропускает.
                walk.setTreeFilter(
                        AndTreeFilter.create(
                                PathFilterGroup.createFromStrings(
                                        RepoPaths.toForwardSlashes(path.strip())),
                                TreeFilter.ANY_DIFF));
            }

            ObjectReader reader = walk.getObjectReader();
            List<GitCommit> matches = new ArrayList<>();
            int scanned = 0;
            for (RevCommit commit : walk) {
                if (++scanned > SCAN || matches.size() >= limit) {
                    return new GitCommitSearchResult(matches, true);
                }
                if (matches(commit, q, scope.inBody())) {
                    matches.add(GitService.toGitCommit(commit, null, reader, scope.withBody()));
                }
            }
            return new GitCommitSearchResult(matches, false);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search commit log", e);
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
