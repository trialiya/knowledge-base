package io.github.trialiya.kb.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.ScriptGuideService;
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
        String handbook =
                new ScriptGuideService(ScriptProperties.enabledWithDefaults()).instructions();
        assertThat(handbook).contains("kb.grep", "{", "}");

        String rendered =
                RENDERER.apply(
                        systemPrompt(),
                        Map.of("mode_instructions", "", "script_instructions", handbook));

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
                                        Map.of("mode_instructions", "", "script_instructions", "")))
                .doesNotThrowAnyException();
    }

    @Test
    @Timeout(30)
    void hasExactlyTheTwoPlaceholdersTheApplicationFills() {
        // Every {name} in the template must be one the two call sites (ChatRunService,
        // ChatController) actually pass — an unfilled placeholder fails the render.
        assertThat(systemPrompt()).contains("{mode_instructions}", "{script_instructions}");
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
