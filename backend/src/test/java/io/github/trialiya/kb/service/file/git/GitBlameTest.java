package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Командная строка {@code git blame} и разбор его porcelain-вывода — без репозитория: вывод с
 * нужными особенностями (поля коммита один раз на первое вхождение, незакоммиченные строки) проще
 * подать готовым, чем складывать под него историю.
 */
class GitBlameTest {

    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);

    /** Путь идёт за {@code --}, ревизия — перед ним, игнорируемые коммиты — по опции на каждый. */
    @Test
    void thePathSitsBehindTheSeparatorAndEveryIgnoredRevIsAnOption() {
        List<String> args = GitBlame.args("--others", List.of(A, B), "deadbee");

        assertThat(args)
                .containsExactly(
                        "git",
                        "blame",
                        "--porcelain",
                        "--ignore-rev",
                        A,
                        "--ignore-rev",
                        B,
                        "deadbee",
                        "--",
                        "--others");
    }

    @Test
    void withoutARevisionTheWorkingTreeIsBlamed() {
        assertThat(GitBlame.args("src/App.java", List.of(), null))
                .containsExactly("git", "blame", "--porcelain", "--", "src/App.java");
    }

    /**
     * Из файла берутся только хеши: комментарии, пустые строки и опечатки git отверг бы целиком,
     * а колонка без blame из-за одной кривой строки в игнор-файле — не ответ.
     */
    @Test
    void theIgnoreFileYieldsOnlyItsHashes() {
        String file = "# Переход на новый форматтер\n\n" + A + "\n  " + B + "  # комментарий\nnot-a-hash\nabc\n";

        assertThat(GitBlame.ignoredRevs(file)).containsExactly(A, B);
    }

    /**
     * Поля коммита печатаются один раз — на его первом ханке; второй ханк того же коммита
     * приходит одним заголовком и должен получить те же автора и дату.
     */
    @Test
    void aCommitSeenAgainKeepsTheFieldsOfItsFirstAppearance() {
        List<String> out = List.of(
                A + " 1 1 2",
                "author Alice",
                "author-mail <alice@example.com>",
                "author-time 1700000000",
                "author-tz +0300",
                "committer Alice",
                "committer-mail <alice@example.com>",
                "committer-time 1700000000",
                "committer-tz +0300",
                "summary first",
                "filename old.txt",
                "\tline one",
                A + " 2 2",
                "\tline two",
                B + " 1 3 1",
                "author Bob",
                "author-mail <bob@example.com>",
                "author-time 1700003600",
                "author-tz +0000",
                "summary second",
                "previous " + A + " f.txt",
                "filename f.txt",
                "\tline three",
                A + " 3 4 1",
                "\tline four");

        List<GitFileBlame.Hunk> hunks = GitBlame.parse(out);

        assertThat(hunks)
                .extracting(
                        GitFileBlame.Hunk::fromLine,
                        GitFileBlame.Hunk::lineCount,
                        GitFileBlame.Hunk::hash,
                        GitFileBlame.Hunk::author,
                        GitFileBlame.Hunk::summary)
                .containsExactly(
                        tuple(1, 2, A, "Alice", "first"),
                        tuple(3, 1, B, "Bob", "second"),
                        tuple(4, 1, A, "Alice", "first"));
        // Путь — из полей коммита: второй ханк первого коммита приходит одним заголовком, без
        // filename, и путь берёт с первого появления.
        assertThat(hunks).extracting(GitFileBlame.Hunk::path).containsExactly("old.txt", "f.txt", "old.txt");
        // Строка в файле того коммита — из заголовка ханка, не из заголовков его прочих строк.
        assertThat(hunks).extracting(GitFileBlame.Hunk::sourceLine).containsExactly(1, 1, 3);
        assertThat(hunks.get(0).date()).isEqualTo(OffsetDateTime.parse("2023-11-15T01:13:20+03:00"));
        assertThat(hunks.get(1).date()).isEqualTo(OffsetDateTime.parse("2023-11-14T23:13:20Z"));
    }

    /** Строки, которых нет ни в одном коммите, git подписывает нулевым хешем — ханк без коммита. */
    @Test
    void uncommittedLinesMakeAHunkWithoutACommit() {
        List<String> out = List.of(
                GitBlame.UNCOMMITTED + " 1 1 1",
                "author Not Committed Yet",
                "author-mail <not.committed.yet>",
                "author-time 1700000000",
                "author-tz +0000",
                "summary Version of f.txt from f.txt",
                "filename f.txt",
                "\tnew line");

        assertThat(GitBlame.parse(out)).singleElement().satisfies(h -> {
            assertThat(h.fromLine()).isEqualTo(1);
            assertThat(h.hash()).isNull();
            assertThat(h.author()).isNull();
            assertThat(h.date()).isNull();
            assertThat(h.path()).isNull();
            assertThat(h.sourceLine()).isNull();
        });
    }

    /**
     * Коммит, к которому blame пришёл под двумя именами (слияние через переименование), получает
     * {@code filename} на каждом ханке — и каждый ханк оставляет свой, а не последний увиденный.
     */
    @Test
    void aHunkWithItsOwnFilenameKeepsItOverTheCommitsFirstOne() {
        List<String> out = List.of(
                A + " 1 1 1",
                "author Alice",
                "author-mail <alice@example.com>",
                "author-time 1700000000",
                "author-tz +0000",
                "summary first",
                "filename left.txt",
                "\tone",
                B + " 1 2 1",
                "author Bob",
                "author-mail <bob@example.com>",
                "author-time 1700000000",
                "author-tz +0000",
                "summary second",
                "filename f.txt",
                "\ttwo",
                A + " 2 3 1",
                "filename right.txt",
                "\tthree",
                A + " 3 4 1",
                "\tfour");

        assertThat(GitBlame.parse(out))
                .extracting(GitFileBlame.Hunk::path)
                .containsExactly("left.txt", "f.txt", "right.txt", "left.txt");
    }

    @Test
    void anEmptyFileHasNoHunks() {
        assertThat(GitBlame.parse(List.of())).isEmpty();
    }
}
