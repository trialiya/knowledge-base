package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A line range is bounded by the same size as a whole read — {@code toLine} says where it stopped. */
class FileViewsTest {

    private static GitFileContent read(String text, Integer from, Integer to) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return FileViews.of("f.txt", true, null, bytes, bytes.length, from, to);
    }

    @Test
    void aRangeWithinTheLimitComesBackAsAsked() {
        GitFileContent content = read("a\nb\nc\nd", 2, 3);

        assertThat(content.content()).isEqualTo("b\nc");
        assertThat(content.fromLine()).isEqualTo(2);
        assertThat(content.toLine()).isEqualTo(3);
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void aHugeRangeStopsAtTheLastLineThatFits() {
        // 1000 lines of 1 KiB each: a megabyte, twice the limit.
        String line = "x".repeat(1023);
        String text = IntStream.range(0, 1000).mapToObj(i -> line).collect(Collectors.joining("\n"));

        GitFileContent content = read(text, 1, 999_999);

        assertThat(content.toLine()).isEqualTo((int) (RepoFiles.MAX_FILE_SIZE / 1024));
        assertThat(content.content()).hasSizeLessThanOrEqualTo((int) RepoFiles.MAX_FILE_SIZE);
        assertThat(content.truncated()).isTrue();
        assertThat(content.lineCount()).isEqualTo(1000);
    }

    @Test
    void aSingleLineOverTheLimitIsCutItself() {
        String text = "y".repeat((int) RepoFiles.MAX_FILE_SIZE + 10) + "\nnext";

        GitFileContent content = read(text, 1, 2);

        assertThat(content.toLine()).isEqualTo(1);
        assertThat(content.content()).startsWith("yyy").endsWith("(line cut at " + RepoFiles.MAX_FILE_SIZE + " bytes)");
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void theLimitIsInBytesNotCharacters() {
        // Кириллица — два байта на символ: 1000 строк по 512 символов — это мегабайт.
        String line = "ж".repeat(511);
        String text = IntStream.range(0, 1000).mapToObj(i -> line).collect(Collectors.joining("\n"));

        GitFileContent content = read(text, 1, 1000);

        assertThat(content.content().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo((int) RepoFiles.MAX_FILE_SIZE);
        assertThat(content.toLine()).isEqualTo((int) (RepoFiles.MAX_FILE_SIZE / 1024));
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void aLineOfExactlyTheLimitIsNotCut() {
        GitFileContent content = read("w".repeat((int) RepoFiles.MAX_FILE_SIZE), 1, 1);

        assertThat(content.content()).hasSize((int) RepoFiles.MAX_FILE_SIZE);
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void aWholeSmallFileIsNotTruncated() {
        GitFileContent content = read("a\nb", 1, 2);

        assertThat(content.content()).isEqualTo("a\nb");
        assertThat(content.truncated()).isFalse();
    }
}
