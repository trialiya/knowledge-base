package io.github.trialiya.kb.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.ScriptGuideService;
import io.github.trialiya.kb.service.script.ScriptEditPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        when(policy.enabled()).thenReturn(true);
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
                                                "")))
                .doesNotThrowAnyException();
    }

    @Test
    @Timeout(30)
    void hasExactlyThePlaceholdersTheApplicationFills() {
        // Every {name} in the template must be one the call sites (ChatRunService) actually
        // pass — an unfilled placeholder fails the render.
        assertThat(systemPrompt())
                .contains("{mode_instructions}", "{script_instructions}", "{system_extended}");
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
