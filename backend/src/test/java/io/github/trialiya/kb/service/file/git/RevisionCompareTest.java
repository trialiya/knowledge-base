package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitComparison;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Сравнение двух ревизий: ветка {@code feature}, отведённая от {@code main}, и {@code main}, ушедший
 * за это время вперёд своей работой.
 */
class RevisionCompareTest {

    @TempDir
    Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        write("README.md", "one\n");
        write("shared.txt", "base\n");
        git("add", "-A");
        git("commit", "-q", "-m", "first");
        git("switch", "-q", "-c", "feature");
        write("feature.txt", "new\n");
        write("README.md", "one\ntwo\n");
        git("add", "-A");
        git("commit", "-q", "-m", "feature work");
        git("switch", "-q", "main");
        write("shared.txt", "changed on main\n");
        git("commit", "-q", "-am", "main work");
        service = TestProjects.gitService(repoDir, false);
    }

    /**
     * Как ветку читает pull request: от общего предка. Работа, сделанная на {@code main} после
     * развилки, не выдаётся за откат её на {@code feature}.
     */
    @Test
    void byDefaultTheBranchIsReadFromTheCommonAncestor() {
        GitComparison comparison = service.compare("main", "feature", false, false, null);

        assertThat(comparison.files())
                .extracting(GitDiffEntry::status, GitDiffEntry::path)
                .containsExactlyInAnyOrder(tuple("M", "README.md"), tuple("A", "feature.txt"));
        assertThat(comparison.mergeBase()).isEqualTo(comparison.diffBase());
        assertThat(comparison.base().message()).isEqualTo("main work");
        assertThat(comparison.head().message()).isEqualTo("feature work");
    }

    /** Прямое сравнение — разница двух деревьев, и правка {@code main} в ней видна. */
    @Test
    void aDirectComparisonDiffsTheTwoTreesThemselves() {
        GitComparison comparison = service.compare("main", "feature", true, false, null);

        assertThat(comparison.files())
                .extracting(GitDiffEntry::path)
                .containsExactlyInAnyOrder("README.md", "feature.txt", "shared.txt");
        assertThat(comparison.diffBase()).isEqualTo(comparison.base().hash());
        assertThat(comparison.mergeBase()).isNotEqualTo(comparison.diffBase());
    }

    /** Счётчики — как у {@code git rev-list --left-right --count main...feature}. */
    @Test
    void theLogCountsBothSidesAndListsTheCommitsOfHead() {
        commitOn("feature", "feature.txt", "more\n", "feature second");

        var log = service.compare("main", "feature", false, false, null).log();

        assertThat(log).isNotNull();
        assertThat(log.ahead()).isEqualTo(2);
        assertThat(log.behind()).isEqualTo(1);
        assertThat(log.countsTruncated()).isFalse();
        assertThat(log.commits()).extracting(GitCommit::message).containsExactly("feature second", "feature work");
    }

    /** Один файл — с патчем и без истории: её спрашивает только сводка. */
    @Test
    void oneFileComesWithItsPatchAndWithoutTheLog() {
        GitComparison comparison = service.compare("main", "feature", false, true, "README.md");

        assertThat(comparison.log()).isNull();
        assertThat(comparison.files()).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("README.md");
            assertThat(entry.patch()).contains("+two");
        });
    }

    @Test
    void aFileTheRevisionsShareUnchangedIsAnEmptyAnswer() {
        assertThat(service.compare("main", "feature", false, true, "shared.txt").files())
                .isEmpty();
    }

    @Test
    void aRevisionComparedWithItselfHasNothingBetween() {
        GitComparison comparison = service.compare("feature", "feature", false, false, null);

        assertThat(comparison.files()).isEmpty();
        assertThat(comparison.log()).isNotNull();
        assertThat(comparison.log().ahead()).isZero();
        assertThat(comparison.log().behind()).isZero();
    }

    /** Без общего предка сравнивать от развилки нечего — остаётся прямое сравнение. */
    @Test
    void unrelatedHistoriesAreComparedDirectly() {
        git("switch", "-q", "--orphan", "other");
        write("other.txt", "x\n");
        git("add", "-A");
        git("commit", "-q", "-m", "unrelated");

        GitComparison comparison = service.compare("main", "other", false, false, null);

        assertThat(comparison.mergeBase()).isNull();
        assertThat(comparison.diffBase()).isEqualTo(comparison.base().hash());
        assertThat(comparison.files()).extracting(GitDiffEntry::path).contains("other.txt", "README.md");
    }

    @Test
    void anUnknownRevisionIsTheCallersMistake() {
        assertThatThrownBy(() -> service.compare("nosuchtag", "main", false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void commitOn(String branch, String file, String text, String message) {
        git("switch", "-q", branch);
        write(file, text);
        git("add", "-A");
        git("commit", "-q", "-m", message);
        git("switch", "-q", "main");
    }

    private void write(String name, String text) {
        try {
            Files.writeString(repoDir.resolve(name), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void git(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
