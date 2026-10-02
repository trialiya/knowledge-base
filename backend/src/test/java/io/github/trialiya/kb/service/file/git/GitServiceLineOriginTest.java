package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin.Status;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Где подстрока появилась в строке файла — через настоящий {@code git blame}: цепочка по версиям
 * строки, переименование, перенос в другой файл, и все способы, которыми цепочка кончается.
 */
class GitServiceLineOriginTest {

    @TempDir
    Path repoDir;

    @TempDir
    Path cloneDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit(repoDir, "init", "-q");
        runGit(repoDir, "config", "user.email", "test@example.com");
        runGit(repoDir, "config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
    }

    /**
     * Строку правили трижды; подстрока вошла во второй правке. Цепочка проходит версии от новой к
     * старой и останавливается на первой, где подстроки нет, — она и есть {@code before}.
     */
    @Test
    void theSubstringIsTracedToTheEditThatBroughtItIntoTheLine() {
        writeFile("f.txt", "head\nint x = 1;\ntail\n");
        String first = commitAll("first");
        writeFile("f.txt", "head\nint x = computeTotal();\ntail\n");
        String second = commitAll("second");
        writeFile("f.txt", "head\nfinal int x = computeTotal();\ntail\n");
        String third = commitAll("third");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "COMPUTETOTAL");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps())
                .extracting(GitLineOrigin.Step::hash, GitLineOrigin.Step::text)
                .containsExactly(
                        tuple(third, "final int x = computeTotal();"), tuple(second, "int x = computeTotal();"));
        assertThat(origin.before()).isNotNull();
        assertThat(origin.before().hash()).isEqualTo(first);
        assertThat(origin.before().text()).isEqualTo("int x = 1;");
        assertThat(origin.steps().getLast().summary()).isEqualTo("second");
    }

    /** Строка, добавленная целиком, кончает цепочку на своём коммите, и «до» у неё нет. */
    @Test
    void aLineAddedWholeEndsTheChainWithNothingBefore() {
        writeFile("f.txt", "alpha\nomega\n");
        commitAll("first");
        writeFile("f.txt", "alpha\nbeta total\nomega\n");
        String added = commitAll("added");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "total");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(added);
        assertThat(origin.before()).isNull();
    }

    /**
     * Переформатирование, названное в {@code .git-blame-ignore-revs} (и в {@code
     * blame.ignoreRevsFile} конфига), цепочка не пропускает:
     * если подстроку внесло оно, оно и ответ. Колонка blame его пропускает, а здесь пропуск
     * приписал бы строку версии без подстроки и потерял бы находку.
     */
    @Test
    void aReformatCommitThatBroughtTheSubstringInIsTheAnswerDespiteTheIgnoreFile() {
        writeFile("f.txt", "foo(a,b);\n");
        commitAll("written");
        writeFile("f.txt", "foo(a, b);\n");
        String reformat = commitAll("reformat");
        writeFile(GitBlameRunner.IGNORE_REVS_FILE, reformat + "\n");
        commitAll("ignore the reformat");

        // Тот же файл, названный ещё и в git config, — так его подхватывает и сам git blame.
        runGit(repoDir, "config", "blame.ignoreRevsFile", GitBlameRunner.IGNORE_REVS_FILE);

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 1, "a, b");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(reformat);
        assertThat(origin.before().text()).isEqualTo("foo(a,b);");
    }

    /** Переименование файла цепочку не рвёт: шаг называет путь, каким он был в том коммите. */
    @Test
    void aRenameIsFollowedAndTheStepNamesTheOldPath() {
        writeFile("old.txt", "keep\nneedle here\n");
        String written = commitAll("written");
        runGit(repoDir, "mv", "old.txt", "new.txt");
        commitAll("renamed");

        GitLineOrigin origin = service.getLineOrigin(null, "new.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps().getLast().hash()).isEqualTo(written);
        assertThat(origin.steps().getLast().path()).isEqualTo("old.txt");
        assertThat(origin.steps().getLast().line()).isEqualTo(2);
    }

    /**
     * Код, вынесенный в новый файл тем же коммитом, что убрал его из старого, прослеживается до
     * того, кто его написал, а не до выноса ({@code -C}).
     */
    @Test
    void codeMovedIntoANewFileIsFollowedBackToWhereItWasWritten() {
        String block = "int computeInvoiceTotalWithDiscountsApplied(Order order) {\n"
                + "    return order.linesWithoutReturnedItems().sumOfPricesAfterTax();\n"
                + "}\n";
        writeFile("Big.java", "class Big {\n" + block + "}\n");
        String written = commitAll("written");
        writeFile("Big.java", "class Big {\n}\n");
        writeFile("Invoice.java", "class Invoice {\n" + block + "}\n");
        commitAll("extracted");

        GitLineOrigin origin = service.getLineOrigin(null, "Invoice.java", 3, "sumOfPricesAfterTax");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps().getLast().hash()).isEqualTo(written);
        assertThat(origin.steps().getLast().path()).isEqualTo("Big.java");
    }

    /**
     * Подстрока вошла незакоммиченной правкой: шагов нет, а «было» — строка в HEAD, которую правка
     * заменила.
     */
    @Test
    void aSubstringBroughtInByAnUncommittedEditNamesTheLineInHead() {
        writeFile("f.txt", "head\none\n");
        String first = commitAll("first");
        writeFile("f.txt", "head\none needle\n");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.UNCOMMITTED);
        assertThat(origin.steps()).isEmpty();
        assertThat(origin.before()).isNotNull();
        assertThat(origin.before().hash()).isEqualTo(first);
        assertThat(origin.before().text()).isEqualTo("one");
    }

    /** Строка, добавленная незакоммиченной правкой, — тоже незакоммиченное появление, без «было». */
    @Test
    void aLineAddedByAnUncommittedEditHasNothingBefore() {
        writeFile("f.txt", "head\n");
        commitAll("first");
        writeFile("f.txt", "head\nnew needle\n");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.UNCOMMITTED);
        assertThat(origin.before()).isNull();
    }

    /**
     * Строка изменена в рабочем дереве, но подстрока в ней была и в HEAD: незакоммиченная правка
     * — не её появление, обход идёт по истории.
     */
    @Test
    void anUncommittedEditThatKeptTheSubstringIsWalkedThroughIntoHistory() {
        writeFile("f.txt", "head\nint x = needle();\n");
        String written = commitAll("written");
        writeFile("f.txt", "head\nfinal int x = needle();\n");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(written);
        assertThat(origin.steps().getFirst().text()).isEqualTo("int x = needle();");
    }

    /** Подстроки в строке нет (файл поменялся после поиска) или строки нет вовсе — не находка. */
    @Test
    void aLineWithoutTheSubstringOrPastTheEndIsNotAFind() {
        writeFile("f.txt", "one\ntwo\n");
        commitAll("first");

        assertThat(service.getLineOrigin(null, "f.txt", 1, "absent").status()).isEqualTo(Status.NOT_IN_LINE);
        assertThat(service.getLineOrigin(null, "f.txt", 9, "one").status()).isEqualTo(Status.NOT_IN_LINE);
    }

    /** От снимка коммита цепочка идёт от его версии строки, а не от рабочего дерева. */
    @Test
    void aRevisionStartsTheWalkAtThatCommit() {
        writeFile("f.txt", "x needle\n");
        String first = commitAll("first");
        writeFile("f.txt", "y\nx needle\n");
        commitAll("second");

        GitLineOrigin origin = service.getLineOrigin(first, "f.txt", 1, "needle");

        assertThat(origin.commit()).isEqualTo(first);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(first);
    }

    /**
     * В неглубоком клоне цепочка упирается в границу истории: это не место появления, а место,
     * где история кончилась, — и ответ говорит именно это.
     */
    @Test
    void theBoundaryOfAShallowCloneIsNotPassedOffAsTheOrigin() {
        writeFile("f.txt", "needle\n");
        commitAll("first");
        writeFile("g.txt", "other\n");
        commitAll("second");
        runGit(cloneDir, "clone", "-q", "--depth", "1", repoDir.toUri().toString(), "shallow");
        GitService shallow = TestProjects.gitService(cloneDir.resolve("shallow"), false);

        GitLineOrigin origin = shallow.getLineOrigin(null, "f.txt", 1, "needle");

        assertThat(origin.status()).isEqualTo(Status.BOUNDARY);
        assertThat(origin.steps()).hasSize(1);
    }

    /**
     * Предел — число шагов, а не повод не проверить последний: строка ровно с {@code MAX_STEPS}
     * версиями находит свой коммит, а с одной версией больше — упирается в предел, отдав пройденное.
     */
    @Test
    void theStepLimitStillFindsAnOriginOnItsLastStepAndStopsOneVersionLater() {
        int limit = LineOrigin.MAX_STEPS;
        String second = null;
        for (int i = 1; i <= limit + 1; i++) {
            writeFile("f.txt", "needle " + i + "\n");
            String hash = commitAll("v" + i);
            if (i == 2) {
                second = hash;
            }
        }

        // От HEAD~1 версий ровно MAX_STEPS (v30…v1): последний шаг — v1, и blame после него
        // говорит, что дальше некуда.
        GitLineOrigin fits = service.getLineOrigin("HEAD~1", "f.txt", 1, "needle");
        assertThat(fits.status()).isEqualTo(Status.FOUND);
        assertThat(fits.steps()).hasSize(limit);

        // От HEAD их на одну больше: пройдено MAX_STEPS (v31…v2), до v1 предел не пустил.
        GitLineOrigin tooMany = service.getLineOrigin(null, "f.txt", 1, "needle");
        assertThat(tooMany.status()).isEqualTo(Status.LIMIT);
        assertThat(tooMany.steps()).hasSize(limit);
        assertThat(tooMany.steps().getLast().hash()).isEqualTo(second);
    }

    /** Срок, кончившийся раньше первого шага, — отказ по таймауту, а не пустой ответ. */
    @Test
    void aWalkThatGotNowhereInTimeIsATimeout() throws IOException {
        writeFile("f.txt", "needle\n");
        commitAll("first");
        RepoPaths paths = new RepoPaths(repoDir);
        try (Repository repository =
                new FileRepositoryBuilder().setWorkTree(repoDir.toFile()).build()) {
            LineOriginTracer tracer = new LineOriginTracer(
                    repository, new VisibleFiles(service.project(), paths, repository), Duration.ZERO);

            assertThatThrownBy(() -> tracer.origin("f.txt", null, 1, "needle"))
                    .isInstanceOf(GitReadTimeoutException.class);
        }
    }

    /**
     * Строка, добавленная рядом с похожей, в которой та же подстрока, — не поздняя версия той
     * похожей: ответ — коммит, который её добавил. Сопоставление по похожести ({@code git blame
     * --ignore-rev}) связало бы их и ушло бы в историю соседки.
     */
    @Test
    void aLineAddedBesideALookAlikeWithTheSameSubstringIsNotThatLookAlike() {
        writeFile("Errors.java", "class Errors {\n    Errors(String message) {\n    }\n}\n");
        commitAll("one constructor");
        writeFile(
                "Errors.java",
                "class Errors {\n    Errors(String message) {\n    }\n"
                        + "    Errors(String message, Throwable cause) {\n    }\n}\n");
        String second = commitAll("second constructor");

        GitLineOrigin origin = service.getLineOrigin(null, "Errors.java", 4, "Errors(");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(second);
        assertThat(origin.before()).isNull();
    }

    /** Строка, пришедшая слиянием ветки, прослеживается в коммит ветки, где её написали. */
    @Test
    void aLineMergedFromABranchIsTracedToTheBranchCommitThatWroteIt() {
        writeFile("f.txt", "base\n");
        commitAll("base");
        String main = runGit(repoDir, "rev-parse", "--abbrev-ref", "HEAD").strip();
        runGit(repoDir, "checkout", "-q", "-b", "feature");
        writeFile("f.txt", "base\nfeature needle\n");
        String onBranch = commitAll("on branch");
        runGit(repoDir, "checkout", "-q", main);
        writeFile("g.txt", "other\n");
        commitAll("on main");
        runGit(repoDir, "merge", "-q", "--no-ff", "-m", "merge", "feature");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(onBranch);
    }

    /**
     * Одна короткая строка, совпавшая с удалённой в другом файле, — случайность, а не перенос:
     * блок легче порога ({@link LineOrigin#MOVE_MIN_ALNUM}), и ответ — коммит, добавивший строку.
     */
    @Test
    void aShortLineThatHappensToMatchADeletedOneIsNotAMove() {
        writeFile("A.java", "class A {\n    int needle;\n}\n");
        commitAll("written");
        writeFile("A.java", "class A {\n}\n");
        writeFile("B.java", "class B {\n    int needle;\n}\n");
        String added = commitAll("both");

        GitLineOrigin origin = service.getLineOrigin(null, "B.java", 2, "needle");

        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(added);
    }

    /** Файл с CRLF в истории: текст версии — без {@code \r}, как его показывает интерфейс. */
    @Test
    void aVersionOfACrlfFileCarriesNoCarriageReturn() {
        writeFile("f.txt", "head\r\nx needle\r\n");
        commitAll("crlf");

        GitLineOrigin origin = service.getLineOrigin("HEAD", "f.txt", 2, "needle");

        assertThat(origin.steps()).extracting(GitLineOrigin.Step::text).containsExactly("x needle");
    }

    /**
     * Прежняя версия файла — бинарная (или больше предела): строк в ней не прочесть, и обход
     * останавливается на пройденном, а не падает.
     */
    @Test
    void aVersionOfTheFileThatCannotBeReadAsLinesStopsTheWalkWithWhatItReached() throws IOException {
        Files.write(repoDir.resolve("f.txt"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, '\n'});
        commitAll("binary");
        writeFile("f.txt", "x needle\n");
        String text = commitAll("text");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 1, "needle");

        assertThat(origin.status()).isEqualTo(Status.LIMIT);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(text);
    }

    /** Метод, переставленный в том же файле, прослеживается до того, кто его написал. */
    @Test
    void codeMovedWithinItsOwnFileIsFollowedBackToWhereItWasWritten() {
        String method = "    int computeInvoiceTotalWithDiscountsApplied(Order order) {\n"
                + "        return order.linesWithoutReturnedItems().sumOfPricesAfterTax();\n"
                + "    }\n";
        // Полей больше, чем строк метода: diff оставит их на месте и покажет переставленным метод.
        String fields = "    int a;\n    int b;\n    int c;\n    int d;\n    int e;\n    int f;\n";
        writeFile("Big.java", "class Big {\n" + fields + method + "}\n");
        String written = commitAll("written");
        writeFile("Big.java", "class Big {\n" + method + fields + "}\n");
        commitAll("moved up");

        GitLineOrigin origin = service.getLineOrigin(null, "Big.java", 3, "sumOfPricesAfterTax");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps().getLast().hash()).isEqualTo(written);
        assertThat(origin.steps().getLast().line()).isEqualTo(9);
    }

    /**
     * Одиночный {@code \r} посреди строки — текст, как его читает весь проект, а не бинарный файл:
     * обход идёт по истории, а не останавливается на нём.
     */
    @Test
    void aLoneCarriageReturnDoesNotMakeAFileBinary() {
        writeFile("f.txt", "a\rb needle\n");
        String written = commitAll("written");
        writeFile("f.txt", "top\na\rb needle\n");
        commitAll("top");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 2, "needle");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps()).extracting(GitLineOrigin.Step::hash).containsExactly(written);
    }

    /** Ревизия с пробелами по краям — та же ревизия, как и у blame. */
    @Test
    void aRevisionWithSurroundingSpacesIsTheSameRevision() {
        writeFile("f.txt", "needle\n");
        String first = commitAll("first");

        assertThat(service.getLineOrigin(" HEAD ", "f.txt", 1, "needle").commit())
                .isEqualTo(first);
    }

    /**
     * Блок, перенесённый в существующий файл на место заглушки: diff сливает вставку с заменой
     * заглушки в один кусок, и прежней строки с подстрокой в нём нет — но это перенос, и обход
     * идёт к тому, кто блок написал.
     */
    @Test
    void aBlockMovedOverAStubIsStillAMove() {
        String block = "    int computeInvoiceTotalWithDiscountsApplied(Order order) {\n"
                + "        return order.linesWithoutReturnedItems().sumOfPricesAfterTax();\n"
                + "    }\n";
        writeFile("A.java", "class A {\n" + block + "}\n");
        writeFile("B.java", "class B {\n    // TODO\n}\n");
        String written = commitAll("written");
        writeFile("A.java", "class A {\n}\n");
        writeFile("B.java", "class B {\n" + block + "}\n");
        commitAll("moved over the stub");

        GitLineOrigin origin = service.getLineOrigin(null, "B.java", 3, "sumOfPricesAfterTax");

        assertThat(origin.status()).isEqualTo(Status.FOUND);
        assertThat(origin.steps().getLast().hash()).isEqualTo(written);
        assertThat(origin.steps().getLast().path()).isEqualTo("A.java");
    }

    /**
     * Правка, дописавшая только хвостовые пробелы, для сравнения с HEAD невидима — но если подстрока
     * в них, она вошла этой правкой: незакоммиченное появление, а не «подстроки нет».
     */
    @Test
    void aSubstringInTrailingWhitespaceAddedByAnUncommittedEditIsUncommitted() {
        writeFile("f.txt", "foo\n");
        String first = commitAll("first");
        writeFile("f.txt", "foo  \n");

        GitLineOrigin origin = service.getLineOrigin(null, "f.txt", 1, "foo ");

        assertThat(origin.status()).isEqualTo(Status.UNCOMMITTED);
        assertThat(origin.before().hash()).isEqualTo(first);
        assertThat(origin.before().text()).isEqualTo("foo");
    }

    @Test
    void aBlankQueryOrALineBelowOneIsTheCallersMistake() {
        writeFile("f.txt", "one\n");
        commitAll("first");

        assertThatThrownBy(() -> service.getLineOrigin(null, "f.txt", 1, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getLineOrigin(null, "f.txt", 0, "one"))
                .isInstanceOf(IllegalArgumentException.class);
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

    /** Коммит всего рабочего дерева; его полный хеш. */
    private String commitAll(String message) {
        runGit(repoDir, "add", "-A");
        runGit(repoDir, "commit", "-q", "-m", message);
        return runGit(repoDir, "rev-parse", "HEAD").strip();
    }

    private static String runGit(Path dir, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + exit + "): " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
