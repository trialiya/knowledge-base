package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

/**
 * The handbook is the difference between a weak model using {@code runScript} and ignoring it, so
 * three things have to hold: it is absent entirely when the tool is off, its budget numbers come
 * from the configuration rather than from whatever was typed into the markdown, and the tutorial
 * half tracks the {@code weak} flag passed to {@link ScriptGuideService#instructions} rather than a
 * deployment-wide switch.
 */
class ScriptGuideServiceTest {

    @Test
    void saysNothingAboutScriptsWhenTheToolIsDisabled() {
        ScriptProperties disabled =
                new ScriptProperties(false, false, null, null, null, null, null, null, null, null);

        ScriptGuideService service = guide(disabled, false);
        assertThat(service.instructions(true)).isEmpty();
        assertThat(service.instructions(false)).isEmpty();
    }

    @Test
    void shipsTheRealHandbookWhenTheToolIsEnabled() {
        String instructions =
                guide(ScriptProperties.enabledWithDefaults(), false).instructions(true);

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
                        guide,
                        null,
                        null,
                        null,
                        java.time.Duration.ofSeconds(7),
                        java.time.Duration.ofSeconds(42),
                        null,
                        new ScriptProperties.Limits(
                                13,
                                DataSize.ofMegabytes(32),
                                2000,
                                20_000,
                                20_000,
                                20,
                                DataSize.ofKilobytes(256)));

        // weak=false: the real script-run-extended.md must not get appended on top of the tiny
        // stand-in guide above, or the equality check below would see its text too.
        assertThat(guide(properties, false).instructions(false))
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
                        "всего: {{max_bytes_read}}, правки: {{max_edited_bytes}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(guide(sized(guide, DataSize.ofKilobytes(512)), false).instructions(false))
                .isEqualTo("всего: 512 КБ, правки: 256 КБ");
        assertThat(guide(sized(guide, DataSize.ofMegabytes(32)), false).instructions(false))
                .isEqualTo("всего: 32 МБ, правки: 256 КБ");
    }

    @Test
    void mentionsTheWriteMethodsOnlyWhenTheyAreActuallyBound() {
        ScriptProperties properties = ScriptProperties.enabledWithDefaults();

        assertThat(guide(properties, false).instructions(true))
                .doesNotContain("kb.edit", "kb.create");
        assertThat(guide(properties, true).instructions(true)).contains("kb.edit", "kb.create");
    }

    /**
     * The split is only worth having if the two halves are the ones described: a strong model
     * losing the tutorial must cost the examples and nothing else. A model that lost the {@code kb}
     * reference or the edit rules along with them would be worse off than with no handbook at all.
     */
    @Test
    void dropsTheTutorialForAStrongModelButKeepsTheReferenceForBoth() {
        ScriptGuideService service = guide(ScriptProperties.enabledWithDefaults(), true);
        String full = service.instructions(true);
        String reference = service.instructions(false);

        assertThat(reference)
                .contains(
                        "### kb reference", "kb.grep", "### Limits per run", "### Rules", "kb.edit")
                .doesNotContain("### Examples", "### Pitfalls", "### Example: bulk rename");
        assertThat(full).contains("### Examples", "### Pitfalls", "### Example: bulk rename");
        // Substitution runs over the assembled text, so a placeholder left in an appendix would
        // reach the model verbatim.
        assertThat(full).doesNotContain("{{");
        assertThat(reference).doesNotContain("{{");
    }

    @Test
    void readOnlyInstructionsAlsoTracksTheWeakFlag() {
        ScriptGuideService service = guide(ScriptProperties.enabledWithDefaults(), true);

        assertThat(service.readOnlyInstructions(true)).contains("### Examples");
        assertThat(service.readOnlyInstructions(false)).doesNotContain("### Examples");
    }

    @Test
    void neverOffersWritesToTheSearchSubAgentEvenWhereTheMainChatMayEdit() {
        ScriptGuideService service = guide(ScriptProperties.enabledWithDefaults(), true);

        assertThat(service.instructions(true)).contains("kb.edit");
        assertThat(service.readOnlyInstructions(true)).doesNotContain("kb.edit");
    }

    /**
     * {@code edit-enabled} задаётся на проект, а {@code kb} в песочницу связывает {@code
     * ScriptRunner} по проекту прогона — справочник обязан описывать тот же объект.
     */
    @Test
    void theWriteAppendixFollowsTheRunsProjectNotTheDefaultOne() {
        ScriptProperties properties = ScriptProperties.enabledWithDefaults();
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(nullable(String.class))).thenReturn(true);
        when(policy.enabled("readonly")).thenReturn(false);
        ScriptGuideService service = new ScriptGuideService(properties, policy);

        assertThat(service.instructions(true, "writable")).contains("kb.edit");
        assertThat(service.instructions(true, "readonly")).doesNotContain("kb.edit");
    }

    /** {@code properties} with one guide and one byte budget varied; the rest stay at defaults. */
    private static ScriptProperties sized(Resource guide, DataSize maxBytesRead) {
        return new ScriptProperties(
                true,
                false,
                guide,
                null,
                null,
                null,
                null,
                null,
                null,
                new ScriptProperties.Limits(
                        2000, maxBytesRead, 2000, 20_000, 20_000, 20, DataSize.ofKilobytes(256)));
    }

    private static ScriptGuideService guide(ScriptProperties properties, boolean editEnabled) {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled()).thenReturn(editEnabled);
        when(policy.enabled(nullable(String.class))).thenReturn(editEnabled);
        return new ScriptGuideService(properties, policy);
    }
}
