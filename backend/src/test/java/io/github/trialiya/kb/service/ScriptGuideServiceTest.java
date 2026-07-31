package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ScriptProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

/**
 * The handbook is the difference between a weak model using {@code runScript} and ignoring it, so
 * two things have to hold: it is absent entirely when the tool is off, and its budget numbers come
 * from the configuration rather than from whatever was typed into the markdown.
 */
class ScriptGuideServiceTest {

    @Test
    void saysNothingAboutScriptsWhenTheToolIsDisabled() {
        ScriptProperties disabled =
                new ScriptProperties(false, null, null, null, null, null, null, null);

        assertThat(new ScriptGuideService(disabled).instructions()).isEmpty();
    }

    @Test
    void shipsTheRealHandbookWhenTheToolIsEnabled() {
        String instructions =
                new ScriptGuideService(ScriptProperties.enabledWithDefaults()).instructions();

        assertThat(instructions).contains("runScript", "kb.read", "kb.grep");
        // Every placeholder must have been substituted — a literal {{...}} in the system prompt is
        // the failure mode this test exists for.
        assertThat(instructions).doesNotContain("{{");
    }

    @Test
    void substitutesBudgetsFromTheConfigurationNotFromTheMarkdown() {
        Resource guide =
                new ByteArrayResource(
                        "файлов: {{max_files_read}}, время: {{timeout}}, максимум {{max_timeout}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ScriptProperties properties =
                new ScriptProperties(
                        true,
                        guide,
                        java.time.Duration.ofSeconds(7),
                        java.time.Duration.ofSeconds(42),
                        null,
                        new ScriptProperties.Limits(
                                13,
                                DataSize.ofMegabytes(4),
                                DataSize.ofKilobytes(512),
                                500,
                                2000,
                                20_000,
                                20_000),
                        List.of(),
                        List.of());

        assertThat(new ScriptGuideService(properties).instructions())
                .isEqualTo("файлов: 13, время: 7 с, максимум 42 с");
    }
}
