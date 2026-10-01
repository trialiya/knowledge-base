package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitGrepHits;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.GitGrepResult;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Поиск по содержимому через настоящий {@code git grep}: рабочее дерево против произвольной
 * ревизии, и как ошибки самого git доходят до вызывающего.
 */
class GitServiceGrepTest {

    @TempDir
    Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
    }

    /**
     * Строка, которая была в первом коммите и исчезла из рабочего дерева, находится по ревизии и не
     * находится по индексу — то есть ревизия действительно ушла в git, а префикс {@code <sha>:} из
     * вывода снят, раз пути разобрались.
     */
    @Test
    void grepAtARevisionSearchesThatCommitNotTheWorkingTree() {
        writeFile("src/App.java", "class App {\n  // needle in the first commit\n}\n");
        commitAll("first");
        writeFile("src/App.java", "class App {\n}\n");
        commitAll("second");

        List<GitGrepMatch> atFirst =
                service.grepHits("needle", null, false, "HEAD~1", 0, 50, false).matches();
        List<GitGrepMatch> now =
                service.grepHits("needle", null, false, null, 0, 50, false).matches();

        assertThat(atFirst).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo("src/App.java");
            assertThat(m.matchLine()).isEqualTo(2);
        });
        assertThat(now).isEmpty();
    }

    /**
     * Страница поиска отличает «ровно столько» от «есть ещё»: выдача размером с лимит, на которой
     * совпадения кончились, полная, а на одно совпадение больше — обрезана, и в ответ лишнее не
     * попадает.
     */
    @Test
    void searchPageTellsExactlyTheLimitFromMoreThanIt() {
        writeFile("a.txt", "needle\nneedle\n");
        writeFile("b.txt", "needle\n");
        commitAll("first");

        GitGrepResult exact = service.grepPage("needle", null, false, null, false, 3);
        GitGrepResult cut = service.grepPage("needle", null, false, null, false, 2);

        assertThat(exact.total()).isEqualTo(3);
        assertThat(exact.truncated()).isFalse();
        assertThat(cut.total()).isEqualTo(2);
        assertThat(cut.truncated()).isTrue();
    }

    @Test
    void searchPageAtARevisionTellsTheSame() {
        writeFile("a.txt", "needle\nneedle\n");
        commitAll("first");

        assertThat(service.grepPage("needle", null, false, "HEAD", false, 2).truncated())
                .isFalse();
        assertThat(service.grepPage("needle", null, false, "HEAD", false, 1).truncated())
                .isTrue();
    }

    @Test
    void grepAtARevisionHonoursThePathGlob() {
        writeFile("src/A.java", "needle\n");
        writeFile("docs/A.md", "needle\n");
        commitAll("first");

        List<GitGrepMatch> matches = service.grepHits("needle", "docs/*", false, "HEAD", 0, 50, false)
                .matches();

        assertThat(matches).extracting(GitGrepMatch::path).containsExactly("docs/A.md");
    }

    /**
     * Имя файла с дефисами и цифрами — {@code 2024-01-15-notes.md}, {@code part-2} — в выводе git
     * выглядит так же, как разделитель перед номером строки, и путь резался по первому попавшемуся
     * дефису. Проверяется на настоящем git: ошибка была в том, как читается его вывод, и подменять
     * этот вывод здесь значило бы проверять собственную догадку о нём.
     */
    @Test
    void aPathWithHyphensAndDigitsSurvivesTheRoundTripThroughGit() {
        writeFile("2024-01-15-notes.md", "alpha\nneedle\ngamma\n");
        writeFile("docs/part-2", "needle\n");
        commitAll("first");

        assertThat(service.grepHits("needle", null, false, null, 0, 50, false).matches())
                .extracting(GitGrepMatch::path, GitGrepMatch::matchLine, GitGrepMatch::text)
                .containsExactlyInAnyOrder(
                        tuple("2024-01-15-notes.md", 2, "needle"), tuple("docs/part-2", 1, "needle"));

        assertThat(service.grepHits("needle", null, false, null, 1, 50, false).matches())
                .extracting(GitGrepMatch::path, GitGrepMatch::matchLine, GitGrepMatch::text)
                .containsExactlyInAnyOrder(
                        tuple("2024-01-15-notes.md", 2, "-1-alpha\n:2:needle\n-3-gamma\n"),
                        tuple("docs/part-2", 1, ":1:needle\n"));
    }

    /**
     * Предупреждение git (здесь — битая строка в `.gitattributes`) идёт в stderr и в разбор вывода
     * не попадает: путь теперь стоит отдельной строкой-заголовком, и посторонняя строка из другого
     * потока заняла бы его место, а настоящий заголовок ушёл бы в мусор вместе со своими
     * совпадениями. Сам поиск при этом успешен — предупреждение не отказ.
     */
    @Test
    void aWarningGitPrintsWhileSearchingIsNotMistakenForAPath() {
        writeFile("a.txt", "needle\n");
        commitAll("first");
        writeFile(".gitattributes", "*.txt =bad\n"); // имя атрибута пустое — git ругается и ищет

        assertThat(service.grepHits("needle", null, false, null, 0, 50, false).matches())
                .extracting(GitGrepMatch::path)
                .containsExactly("a.txt");
    }

    /**
     * `color.ui = always` в конфиге хоста красит вывод и тогда, когда на него никто не смотрит
     * терминалом: путь и номер строки приезжают в escape-последовательностях, и разбор не узнаёт ни
     * одной строки — поиск, у которого есть совпадения, вернул бы пустоту. Поэтому цвет выключен в
     * самой команде, а не оставлен на усмотрение конфига.
     */
    @Test
    void colourForcedOnInTheConfigDoesNotReachTheParser() {
        writeFile("a.txt", "needle\n");
        commitAll("first");
        runGit("config", "color.ui", "always");

        assertThat(service.grepHits("needle", null, false, null, 0, 50, false).matches())
                .extracting(GitGrepMatch::path, GitGrepMatch::text)
                .containsExactly(tuple("a.txt", "needle"));
    }

    @Test
    void anUnknownRevisionIsTheCallersMistake() {
        writeFile("a.txt", "needle\n");
        commitAll("first");

        assertThatThrownBy(() -> service.grepHits("needle", null, false, "no-such-branch", 0, 50, false)
                        .matches())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Регулярное выражение разбирает git, не мы: его отказ (exit 128) приходит как {@link
     * IllegalArgumentException} с текстом самого git, а не как сбой сервиса.
     */
    @Test
    void aBrokenRegexIsReportedAsABadArgumentWithGitsOwnWording() {
        writeFile("a.txt", "needle\n");
        commitAll("first");

        assertThatThrownBy(() -> service.grepHits("needle(", null, true, null, 0, 50, false)
                        .matches())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("'needle('");
    }

    /**
     * Git is refused for something other than the pattern — here a pathspec with magic it does not
     * know — and that is not the caller's regex: a failure, not a bad argument.
     */
    @Test
    void aRefusalThatIsNotAboutThePatternIsAFailureNotABadArgument() {
        writeFile("a.txt", "needle\n");
        commitAll("first");

        assertThatThrownBy(() -> service.grepHits("needle", ":(bogus)a.txt", false, null, 0, 50, false)
                        .matches())
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    /**
     * The whole output is not read for a handful of blocks: git is stopped once the cap's worth of
     * lines is in, and what was read is the answer, complete and in order — no exit-code noise from
     * the kill.
     */
    @Test
    void outputIsReadOnlyUpToTheLimit() {
        String body =
                IntStream.range(0, 5_000).mapToObj(i -> "needle " + i).collect(Collectors.joining("\n", "", "\n"));
        writeFile("big.txt", body);
        commitAll("first");

        List<GitGrepMatch> matches =
                service.grepHits("needle", null, false, null, 0, 3, false).matches();

        assertThat(matches).extracting(GitGrepMatch::matchLine).containsExactly(1, 2, 3);
    }

    /**
     * With context the cap falls inside a block, and a cut block is dropped: its context is missing
     * on one side, and a match shown with half of what surrounds it is worse than one not shown. A
     * run whose whole output is a single unfinished block therefore answers with nothing — there is
     * no complete block in it to keep.
     */
    @Test
    void aBlockCutByTheOutputCapIsDroppedRatherThanShownHalfRead() {
        String body = IntStream.range(0, GitGrepRunner.MAX_OUTPUT_LINES + 5_000)
                .mapToObj(i -> "needle " + i)
                .collect(Collectors.joining("\n", "", "\n"));
        writeFile("big.txt", body);
        commitAll("first");

        // Every line matches, so git grep -C1 prints one uninterrupted run with no "--" in it.
        assertThat(service.grepHits("needle", null, false, null, 1, 3, false).matches())
                .isEmpty();
        // Without context every line is a block of its own, so the same cut keeps what it read.
        assertThat(service.grepHits("needle", null, false, null, 0, 3, false).matches())
                .hasSize(3);
    }

    /**
     * The model's search says when its list is not the whole answer: a context run cut at the
     * output ceiling comes back short — here empty — and only {@code truncated} tells that apart
     * from "nothing else matches". A list exactly {@code maxResults} long that git finished is
     * complete.
     */
    @Test
    void theModelsSearchSaysWhenTheOutputCeilingCutItShort() {
        String body = IntStream.range(0, GitGrepRunner.MAX_OUTPUT_LINES + 5_000)
                .mapToObj(i -> "needle " + i)
                .collect(Collectors.joining("\n", "", "\n"));
        writeFile("big.txt", body);
        writeFile("small.txt", "pin\npin\n");
        commitAll("first");

        GitGrepHits cut = service.grepHits("needle", null, false, null, 1, 50, false);
        assertThat(cut.matches()).isEmpty();
        assertThat(cut.truncated()).isTrue();

        assertThat(service.grepHits("pin", null, false, null, 1, 2, false).truncated())
                .isFalse();
        GitGrepHits over = service.grepHits("pin", null, false, null, 0, 1, false);
        assertThat(over.matches()).hasSize(1);
        assertThat(over.truncated()).isTrue();
    }

    /**
     * The deadline is the search's, not one run's: a run that starts with the budget already spent
     * (here, none at all) is refused as timed out before git is even asked. The kill of a run that
     * outlives its budget goes through the same {@code destroyForcibly} and {@code waitFor} as the
     * output cap above; only the timing itself is not pinned down by a test.
     */
    @Test
    void aSearchWhoseBudgetIsSpentIsRefusedAsTimedOut() throws IOException {
        writeFile("a.txt", "needle\n");
        commitAll("first");
        RepoPaths paths = new RepoPaths(repoDir);
        try (Repository repository =
                new FileRepositoryBuilder().setWorkTree(repoDir.toFile()).build()) {
            GitGrepRunner runner = new GitGrepRunner(
                    paths, repository, new VisibleFiles(service.project(), paths, repository), Duration.ZERO);

            assertThatThrownBy(() -> runner.grepHits("needle", null, false, null, 0, 50, false)
                            .matches())
                    .isInstanceOf(GitReadTimeoutException.class);
        }
    }

    private void writeFile(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll(String message) {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", message);
    }

    private void runGit(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
