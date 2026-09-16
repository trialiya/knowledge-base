package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Командная строка {@code git grep} и разбор его вывода — единственное место, где аргументы уезжают
 * во внешний процесс строками, а ответ приходит текстом, а не через JGit. Проверяется здесь, а не
 * через {@link GitService}: поведение «шаблон и пути не разобрали как опции» по результату поиска
 * не отличить от «ничего не нашлось», а вывод с неудобными именами файлов проще подать готовым, чем
 * складывать под него репозиторий.
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

    /**
     * Путь печатается заголовком, а не в начале каждой строки: иначе разделитель между путём и
     * номером строки неотличим от тех же {@code :} и {@code -} внутри самого имени файла.
     */
    @Test
    void thePathIsAskedForAsAHeadingSoThatItCanBeToldFromTheLineNumber() {
        List<String> args = GitGrep.args("needle", null, false, 0, null, null);

        assertThat(args).containsSubsequence("grep", "--heading", "--break");
    }

    /** Вывод по коммиту теряет префикс {@code <sha>:}; остальные строки остаются как есть. */
    @Test
    void theCommitPrefixIsStrippedFromEveryHeading() {
        List<String> lines = List.of("abc123:src/A.java", "3:needle", "", "abc123:src/B.java");

        assertThat(GitGrep.withoutCommitPrefix(lines, "abc123"))
                .containsExactly("src/A.java", "3:needle", "", "src/B.java");
    }

    /**
     * Дефис с цифрами в имени файла — {@code 2024-01-15-notes.md}, {@code part-2} — выглядит ровно
     * как разделитель перед номером строки. Заголовок снимает вопрос: путь берётся целой строкой.
     */
    @Test
    void aHyphenatedFileNameIsNotMistakenForALineNumber() {
        List<String> lines =
                List.of("2024-01-15-notes.md", "2:needle", "", "part-2", "5:needle", "7:needle");

        assertThat(GitGrep.parse(lines, 0, 50))
                .extracting(GitGrepMatch::path, GitGrepMatch::matchLine, GitGrepMatch::text)
                .containsExactly(
                        tuple("2024-01-15-notes.md", 2, "needle"),
                        tuple("part-2", 5, "needle"),
                        tuple("part-2", 7, "needle"));
    }

    /**
     * С контекстом блок собирается из строк под заголовком: {@code --} разделяет блоки одного
     * файла, пустая строка — сами файлы. Номер блока — первое совпадение в нём, а не первая строка.
     */
    @Test
    void contextLinesAreFoldedIntoOneBlockPerRunOfLines() {
        List<String> lines =
                List.of(
                        "step-01-init.sh",
                        "1-set -e",
                        "2:needle",
                        "--",
                        "8-echo",
                        "9:needle",
                        "",
                        "my-file.java",
                        "3:needle",
                        "4-}");

        assertThat(GitGrep.parse(lines, 1, 50))
                .extracting(GitGrepMatch::path, GitGrepMatch::matchLine, GitGrepMatch::text)
                .containsExactly(
                        tuple("step-01-init.sh", 2, "-1-set -e\n:2:needle\n"),
                        tuple("step-01-init.sh", 9, "-8-echo\n:9:needle\n"),
                        tuple("my-file.java", 3, ":3:needle\n-4-}\n"));
    }

    /** Лимит считается в блоках и останавливает разбор: остаток вывода уже некуда класть. */
    @Test
    void parsingStopsAtTheBlockLimit() {
        List<String> lines = List.of("a-1-b.txt", "1:needle", "2:needle", "3:needle");

        assertThat(GitGrep.parse(lines, 0, 2)).hasSize(2);
    }
}
