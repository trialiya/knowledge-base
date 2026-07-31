package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.script.ScriptEditPolicy;
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
                new ScriptProperties(
                        false, false, true, null, null, null, null, null, null, null, null, null,
                        null);

        assertThat(guide(disabled, false).instructions()).isEmpty();
    }

    @Test
    void shipsTheRealHandbookWhenTheToolIsEnabled() {
        String instructions = guide(ScriptProperties.enabledWithDefaults(), false).instructions();

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
                        false,
                        false,
                        guide,
                        null,
                        null,
                        null,
                        java.time.Duration.ofSeconds(7),
                        java.time.Duration.ofSeconds(42),
                        null,
                        new ScriptProperties.Limits(
                                13,
                                DataSize.ofMegabytes(4),
                                DataSize.ofKilobytes(512),
                                200,
                                2000,
                                20_000,
                                20_000,
                                20,
                                DataSize.ofKilobytes(256)),
                        List.of(),
                        List.of());

        assertThat(guide(properties, false).instructions())
                .isEqualTo("файлов: 13, время: 7 с, максимум 42 с");
    }

    /**
     * A size is rendered in the largest unit that still spells it exactly. Fixed at megabytes,
     * {@code max-bytes-read: 512KB} reached the model as "0 МБ" — a budget of nothing, which is
     * worse than no number at all.
     */
    @Test
    void rendersEachSizeInAUnitThatDoesNotRoundItAway() {
        Resource guide =
                new ByteArrayResource(
                        "всего: {{max_bytes_read}}, файл: {{max_file_bytes}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(guide(sized(guide, DataSize.ofKilobytes(512)), false).instructions())
                .isEqualTo("всего: 512 КБ, файл: 512 КБ");
        assertThat(guide(sized(guide, DataSize.ofMegabytes(4)), false).instructions())
                .isEqualTo("всего: 4 МБ, файл: 512 КБ");
    }

    @Test
    void mentionsTheWriteMethodsOnlyWhenTheyAreActuallyBound() {
        ScriptProperties properties = ScriptProperties.enabledWithDefaults();

        assertThat(guide(properties, false).instructions()).doesNotContain("kb.edit", "kb.create");
        assertThat(guide(properties, true).instructions()).contains("kb.edit", "kb.create");
    }

    /**
     * The split is only worth having if the two halves are the ones described: switching the
     * tutorial off must cost the examples and nothing else. A model that lost the {@code kb}
     * reference or the edit rules along with them would be worse off than with no handbook at all.
     */
    @Test
    void dropsTheTutorialButKeepsTheReferenceWhenTheExtendedGuideIsOff() {
        String full = guide(ScriptProperties.enabledWithDefaults(), true).instructions();
        String reference = guide(withoutExtendedGuide(), true).instructions();

        assertThat(reference)
                .contains(
                        "### Справочник kb",
                        "kb.grep",
                        "### Лимиты одного запуска",
                        "### Обязательные правила",
                        "kb.edit")
                .doesNotContain(
                        "### Примеры",
                        "### Чего не делать",
                        "### Пример: массовое переименование с проверкой");
        assertThat(full)
                .contains(
                        "### Примеры",
                        "### Чего не делать",
                        "### Пример: массовое переименование с проверкой");
        // Substitution runs over the assembled text, so a placeholder left in an appendix would
        // reach the model verbatim.
        assertThat(full).doesNotContain("{{");
        assertThat(reference).doesNotContain("{{");
    }

    @Test
    void neverOffersWritesToTheSearchSubAgentEvenWhereTheMainChatMayEdit() {
        ScriptGuideService service = guide(ScriptProperties.enabledWithDefaults(), true);

        assertThat(service.instructions()).contains("kb.edit");
        assertThat(service.readOnlyInstructions()).doesNotContain("kb.edit");
    }

    /** The real handbooks, with only the tutorial halves switched off. */
    private static ScriptProperties withoutExtendedGuide() {
        return new ScriptProperties(
                true, true, false, null, null, null, null, null, null, null, null, null, null);
    }

    /** {@code properties} with one guide and one byte budget varied; the rest stay at defaults. */
    private static ScriptProperties sized(Resource guide, DataSize maxBytesRead) {
        return new ScriptProperties(
                true,
                false,
                false,
                guide,
                null,
                null,
                null,
                null,
                null,
                null,
                new ScriptProperties.Limits(
                        200,
                        maxBytesRead,
                        DataSize.ofKilobytes(512),
                        200,
                        2000,
                        20_000,
                        20_000,
                        20,
                        DataSize.ofKilobytes(256)),
                List.of(),
                List.of());
    }

    private static ScriptGuideService guide(ScriptProperties properties, boolean editEnabled) {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled()).thenReturn(editEnabled);
        return new ScriptGuideService(properties, policy);
    }
}
