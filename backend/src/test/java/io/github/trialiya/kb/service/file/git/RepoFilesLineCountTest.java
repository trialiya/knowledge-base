package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Счёт строк так, как его ведёт git: диапазон blame укладывается в файл по этому числу, и строка,
 * которой git не видит, превратилась бы в его отказ.
 */
class RepoFilesLineCountTest {

    @TempDir
    Path dir;

    /** Перевод строки в конце пустой строки за собой не оставляет; хвост без перевода — строка. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"''|0", "'a'|1", "'a\\n'|1", "'a\\nb'|2", "'a\\r\\nb\\r\\n'|2", "'\\n\\n'|2"})
    void linesAreCountedAsGitCountsThem(String escaped, int expected) throws IOException {
        byte[] bytes = escaped.replace("\\n", "\n").replace("\\r", "\r").getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("f.txt"), bytes);

        assertThat(RepoFiles.lineCount(bytes)).isEqualTo(expected);
        assertThat(RepoFiles.lineCount("f.txt", file)).isEqualTo(expected);
    }

    /**
     * Файл на диске читается кусками: последний байт куска, на котором файл не кончился, не должен
     * считаться открытой строкой.
     */
    @Test
    void aFileLongerThanOneChunkIsCountedWhole() throws IOException {
        String line = "x".repeat(1023) + "\n";
        Path file = Files.writeString(dir.resolve("big.txt"), line.repeat(200) + "tail");

        assertThat(RepoFiles.lineCount("big.txt", file)).isEqualTo(201);
    }

    @Test
    void aMissingFileIsTheCallersMistake() {
        assertThatThrownBy(() -> RepoFiles.lineCount("gone.txt", dir.resolve("gone.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
