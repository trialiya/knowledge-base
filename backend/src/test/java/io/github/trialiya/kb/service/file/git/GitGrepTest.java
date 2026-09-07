package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Сборка командной строки {@code git grep} — единственного места, где аргументы уезжают во внешний
 * процесс строками, а не через JGit. Проверяется здесь, а не через {@link GitService}: поведение
 * «шаблон и пути не разобрали как опции» по результату поиска не отличить от «ничего не нашлось».
 */
class GitGrepTest {

    /**
     * Шаблон идёт значением {@code -e}, а пути — за {@code --}: иначе шаблон вида {@code
     * --untracked} стал бы опцией grep'а, а путь {@code --others} — командой искать вне индекса.
     */
    @Test
    void thePatternIsAnOptionValueAndThePathspecSitsBehindTheSeparator() {
        List<String> args = GitGrep.args("--untracked", "--others", false, 0, null, null);

        assertThat(args).endsWith("-e", "--untracked", "--", "--others");
    }

    /** Путей нет — разделителя тоже: пустой pathspec git понял бы как «ничего не искать». */
    @Test
    void withoutAPathspecThereIsNoSeparator() {
        List<String> args = GitGrep.args("needle", null, false, 0, null, null);

        assertThat(args).endsWith("-e", "needle");
        assertThat(args).doesNotContain("--");
    }

    /**
     * Поиск по неотслеживаемым каталогам добавляет и опции, и сами каталоги как пути — те тоже
     * обязаны стоять за разделителем.
     */
    @Test
    void theUntrackedRootsAreArgumentsOfTheirOwnBehindTheSeparator() {
        List<String> args = GitGrep.args("needle", null, true, 2, List.of("notes"), null);

        assertThat(args)
                .containsSubsequence("--untracked", "--no-exclude-standard", "-E", "-C", "2");
        assertThat(args).endsWith("-e", "needle", "--", "notes");
    }

    /** Коммит стоит между шаблоном и разделителем: после {@code --} git принял бы его за путь. */
    @Test
    void theCommitFollowsThePatternAndPrecedesThePathspec() {
        List<String> args = GitGrep.args("needle", "src/*.java", false, 0, null, "abc123");

        assertThat(args).endsWith("-e", "needle", "abc123", "--", "src/*.java");
    }

    /** Вывод по коммиту теряет префикс {@code <sha>:}; разделители блоков остаются как есть. */
    @Test
    void theCommitPrefixIsStrippedFromEveryOutputLine() {
        List<String> lines =
                List.of("abc123:src/A.java:3:needle", "--", "abc123:src/B.java:1:needle again");

        assertThat(GitGrep.withoutCommitPrefix(lines, "abc123"))
                .containsExactly("src/A.java:3:needle", "--", "src/B.java:1:needle again");
    }
}
