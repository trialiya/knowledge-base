package io.github.trialiya.kb.model.git.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Поиск по содержимому для страницы поиска: совпадения сгруппированы по файлу, как их показывает
 * интерфейс — файл один раз, под ним его строки.
 *
 * <p>Репозиторий ответ не называет: он один на всю выдачу и известен клиенту из запроса.
 *
 * @param total сколько строк с совпадениями вошло в ответ
 * @param truncated выдача упёрлась в лимит запроса — в репозитории есть ещё совпадения, которых
 *     здесь нет
 * @param files файлы в порядке появления в выводе {@code git grep} (то есть по пути)
 */
public record GitGrepResult(int total, boolean truncated, List<File> files) {

    /**
     * Один файл с его совпадениями.
     *
     * @param path относительный путь от корня репозитория
     * @param tracked отслеживается ли файл git'ом. {@code false} — файл найден только потому, что
     *     запрос был с {@code untracked=true}, и попал в зону {@code allow-globs} проекта: истории
     *     у него нет, и строка могла прийти из вывода сборки, а не из исходника
     * @param lines строки с совпадениями в порядке номеров
     */
    public record File(String path, boolean tracked, List<Line> lines) {}

    /**
     * Одна строка с совпадением.
     *
     * @param line номер строки (1-based)
     * @param text текст строки целиком; где именно в ней совпадение, клиент находит сам по запросу
     */
    public record Line(int line, String text) {}

    /**
     * Группирует плоские блоки без контекста ({@code contextLines=0}: один блок — одна строка) по
     * файлу, сохраняя их порядок.
     *
     * @param matches блоки, как их отдаёт {@code GitService.grepContent} с нулевым контекстом
     * @param limit лимит, с которым их запрашивали: выдача ровно такого размера считается
     *     обрезанной
     */
    public static GitGrepResult group(List<GitGrepMatch> matches, int limit) {
        Map<String, List<Line>> byPath = new LinkedHashMap<>();
        Set<String> untracked = new HashSet<>();
        for (GitGrepMatch match : matches) {
            byPath.computeIfAbsent(match.path(), p -> new ArrayList<>())
                    .add(new Line(match.matchLine(), match.text()));
            if (!match.tracked()) {
                untracked.add(match.path());
            }
        }
        List<File> files =
                byPath.entrySet().stream()
                        .map(
                                e ->
                                        new File(
                                                e.getKey(),
                                                !untracked.contains(e.getKey()),
                                                List.copyOf(e.getValue())))
                        .toList();
        return new GitGrepResult(matches.size(), matches.size() >= limit, files);
    }
}
