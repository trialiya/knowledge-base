package io.github.trialiya.kb.i18n;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                                "script_instructions",
                                handbook,
                                "system_extended",
                                "",
                                "project_context",
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
                                                "script_instructions",
                                                "",
                                                "system_extended",
                                                "",
                                                "project_context",
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
     * Каждый вызывающий передаёт весь набор — иначе рендер падает именно на его пути, а не на
     * общем. Сейчас запрос к модели с этим промптом собирают в одном месте, и список из одного
     * имени — это приглашение дописать второе, а не признак лишней параметризации.
     */
    @ParameterizedTest
    @ValueSource(classes = {io.github.trialiya.kb.service.chat.run.ChatRunService.class})
    @Timeout(30)
    void everyCallSitePassesThemAll(Class<?> callSite) {
        String source = sourceOf(callSite);
        Set<String> passed =
                PARAM_CALL.matcher(source).results().map(m -> m.group(1)).collect(toSet());

        assertThat(passed).containsAll(FILLED_BY_THE_APPLICATION);
    }

    /** {@code .param("name",} — как плейсхолдеры и передаются в шаблон системного промпта. */
    private static final Pattern PARAM_CALL = Pattern.compile("\\.param\\(\\s*\"(\\w+)\"");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private static final Set<String> FILLED_BY_THE_APPLICATION =
            Set.of(
                    "mode_instructions",
                    "script_instructions",
                    "system_extended",
                    "project_context");

    private static Set<String> placeholdersOf(String template) {
        return PLACEHOLDER.matcher(template).results().map(m -> m.group(1)).collect(toSet());
    }

    private static String sourceOf(Class<?> type) {
        Path path = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the source of " + type.getName(), e);
        }
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
