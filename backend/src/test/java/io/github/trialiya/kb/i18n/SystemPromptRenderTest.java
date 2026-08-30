package io.github.trialiya.kb.i18n;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.SystemPromptProperties;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Renders {@code prompt/sys.md} exactly the way the chat client does, with the values the
 * application actually passes.
 *
 * <p>This is not a formality. The renderer is StringTemplate, whose delimiters are {@code
 * &#123;&#125;} — the same braces the {@code runScript} handbook is full of, since its examples are
 * JavaScript. If a substituted fragment were ever re-parsed, or a placeholder went unfilled, every
 * chat request would fail at runtime the moment scripts were switched on, and no other test in the
 * suite would notice.
 */
class SystemPromptRenderTest {

    private static final StTemplateRenderer RENDERER = StTemplateRenderer.builder().build();

    @Test
    @Timeout(30)
    void rendersWithTheScriptHandbookInjected() throws IOException {
        // With writes on, so the appendix — and its JavaScript examples — is in the fragment too.
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(true);
        String handbook =
                new ScriptGuideService(ScriptProperties.enabledWithDefaults(), policy)
                        .instructions(true);
        assertThat(handbook).contains("kb.grep", "kb.edit", "{", "}");

        String rendered =
                RENDERER.apply(
                        systemPrompt(),
                        Map.of(
                                "mode_instructions",
                                "",
                                "skill_catalogue",
                                "",
                                "script_instructions",
                                handbook,
                                "system_extended",
                                ""));

        // The handbook arrives verbatim: braces inside a substituted value are content, not syntax.
        assertThat(rendered).contains(handbook);
    }

    @Test
    @Timeout(30)
    void rendersWithEveryFragmentEmpty() {
        assertThatCode(
                        () ->
                                RENDERER.apply(
                                        systemPrompt(),
                                        Map.of(
                                                "mode_instructions",
                                                "",
                                                "skill_catalogue",
                                                "",
                                                "script_instructions",
                                                "",
                                                "system_extended",
                                                "")))
                .doesNotThrowAnyException();
    }

    /**
     * Обе стороны, а не одна: раньше здесь проверялось только наличие трёх имён, и плейсхолдер,
     * которого не передавал один из вызывающих, тест проходил. Незаполненный — это отказ на каждом
     * запросе чата, поэтому набор сверяется на равенство.
     */
    @Test
    @Timeout(30)
    void hasExactlyThePlaceholdersTheApplicationFills() {
        assertThat(placeholdersOf(systemPrompt()))
                .containsExactlyInAnyOrderElementsOf(FILLED_BY_THE_APPLICATION);
    }

    /**
     * Набор, который отдаёт единственный собирающий его метод, покрывает шаблон целиком.
     * Незаполненный плейсхолдер — это отказ на каждом запросе чата, а лишний молча ничего не значит
     * до тех пор, пока имя в шаблоне не опечатались.
     *
     * <p>Раньше здесь читались исходники вызывающих: пока набор собирал каждый сам, забытое одним
     * из них имя валило запросы только на его пути. Вызывающих по-прежнему два — чат и {@code
     * /compact} — но собирают они набор общим методом, и это единственное, что имеет смысл
     * проверять.
     */
    @Test
    @Timeout(30)
    void theOnePlaceThatFillsThemCoversTheWholeTemplate() {
        assertThat(placeholders().keySet())
                .containsExactlyInAnyOrderElementsOf(placeholdersOf(systemPrompt()));
    }

    private static Map<String, Object> placeholders() {
        ScriptGuideService guide = mock(ScriptGuideService.class);
        when(guide.instructions(false, null)).thenReturn("");
        SkillService skills = mock(SkillService.class);
        when(skills.catalogue(false, null)).thenReturn("");
        return new SystemPromptService(new SystemPromptProperties(null, null), guide, skills)
                .placeholders(false, null, "");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private static final Set<String> FILLED_BY_THE_APPLICATION =
            Set.of(
                    "mode_instructions",
                    "skill_catalogue",
                    "script_instructions",
                    "system_extended");

    private static Set<String> placeholdersOf(String template) {
        return PLACEHOLDER.matcher(template).results().map(m -> m.group(1)).collect(toSet());
    }

    private static String systemPrompt() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("prompt/sys.md").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompt/sys.md is not on the test classpath", e);
        }
    }
}
