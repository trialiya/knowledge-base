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
     * Шаблон отделён от опций своим {@code --}, а пути — вторым: иначе шаблон вида {@code -e} стал
     * бы опцией grep'а, а путь {@code --untracked} — командой искать вне индекса.
     */
    @Test
    void thePatternAndThePathspecEachSitBehindTheirOwnSeparator() {
        List<String> args = GitGrep.args("--untracked", "--others", false, 0, null);

        assertThat(args).endsWith("--", "--untracked", "--", "--others");
    }

    /**
     * Путей нет — второго разделителя тоже: пустой pathspec git понял бы как «ничего не искать».
     */
    @Test
    void withoutAPathspecThereIsOnlyTheOneSeparator() {
        List<String> args = GitGrep.args("needle", null, false, 0, null);

        assertThat(args).endsWith("--", "needle");
        assertThat(args.stream().filter("--"::equals)).hasSize(1);
    }

    /**
     * Поиск по неотслеживаемым каталогам добавляет и опции, и сами каталоги как пути — те тоже
     * обязаны стоять за вторым разделителем.
     */
    @Test
    void theUntrackedRootsAreArgumentsOfTheirOwnBehindTheSecondSeparator() {
        List<String> args = GitGrep.args("needle", null, true, 2, List.of("notes"));

        assertThat(args)
                .containsSubsequence("--untracked", "--no-exclude-standard", "-E", "-C", "2");
        assertThat(args).endsWith("--", "needle", "--", "notes");
    }
}
