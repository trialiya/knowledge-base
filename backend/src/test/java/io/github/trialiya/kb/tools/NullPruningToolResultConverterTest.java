package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.tool.ToolResult;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NullPruningToolResultConverterTest {

    private final NullPruningToolResultConverter converter = new NullPruningToolResultConverter();

    @Test
    void dropsFieldsNullInEveryRecordAndKeepsMixedOnes() {
        GitCommit commit =
                new GitCommit(
                        "abcdef0123",
                        "abcdef0",
                        "Ann",
                        "ann@example.com",
                        OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC),
                        "feat: x",
                        null,
                        List.of(
                                new GitDiffEntry("M", "a.js", null, 1, 0, null, null),
                                new GitDiffEntry("R", "b.js", "old/b.js", 2, 1, null, null)));

        String json = converter.convert(new ToolResult<>("kb", List.of(commit)), null);

        assertThat(json)
                .doesNotContain("\"body\"")
                .doesNotContain("\"patch\"")
                .doesNotContain("\"patchHeader\"")
                // oldPath задан у одной записи — у соседней остаётся null, набор ключей общий
                .contains("\"oldPath\":null")
                .contains("\"oldPath\":\"old/b.js\"");
    }

    @Test
    void dropsNullFieldsOfSingleObject() {
        String json =
                converter.convert(new GitDiffEntry("M", "a.js", null, 1, 0, null, null), null);

        assertThat(json)
                .isEqualTo("{\"status\":\"M\",\"path\":\"a.js\",\"additions\":1,\"deletions\":0}");
    }

    @Test
    void passesScalarResultsThrough() {
        assertThat(converter.convert(null, void.class))
                .isEqualTo(new CompactToolResultConverter().convert(null, void.class));
    }

    @Test
    void keepsToolResultWrapperIntact() {
        String json = converter.convert(new ToolResult<>("kb", null), null);

        assertThat(json).isEqualTo("{\"project\":\"kb\",\"result\":null}");
    }
}
