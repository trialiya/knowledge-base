package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/** Что из коммита уходит модели: пустые поля в JSON ответа инструмента не печатаются. */
class GitCommitJsonTest {

    private final DefaultToolCallResultConverter converter = new DefaultToolCallResultConverter();

    @Test
    void nullFieldsAreLeftOut() {
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
                                new GitDiffEntry("R", "b.js", "old/b.js", 2, 1, "h", "@@")));

        String json = converter.convert(commit, GitCommit.class);

        assertThat(json)
                .doesNotContain("\"body\"")
                .doesNotContain("null")
                .contains("{\"status\":\"M\",\"path\":\"a.js\",\"additions\":1,\"deletions\":0}")
                .contains("\"oldPath\":\"old/b.js\"")
                .contains("\"patch\":\"@@\"");
    }
}
