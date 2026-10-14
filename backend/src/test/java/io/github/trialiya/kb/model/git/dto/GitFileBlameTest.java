package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** JSON ханка колонки blame: флаг границы клона печатается только там, где он есть. */
class GitFileBlameTest {

    private static final String HASH = "a1b2c3d" + "0".repeat(33);

    /** Флаг печатается только там, где он есть: у обычного ханка ключа {@code boundary} нет. */
    @Test
    void onlyABoundaryHunkCarriesTheFlagInJson() {
        JsonMapper json = JsonMapper.builder().build();
        GitFileBlame.Hunk plain = new GitFileBlame.Hunk(1, 1, HASH, null, null, null, null, 1);

        assertThat(json.writeValueAsString(plain)).doesNotContain("boundary");
        assertThat(json.writeValueAsString(plain.atBoundary())).contains("\"boundary\":true");
    }
}
