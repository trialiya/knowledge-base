package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModeProperties.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class ChatModeServiceTest {

    private static Mode mode(String id, Resource prompt) {
        return new Mode(id, id, prompt);
    }

    @Test
    void loadsInstructionsFromResources() {
        ChatModeService service =
                new ChatModeService(
                        new ChatModeProperties(
                                List.of(
                                        mode("developer", res("Инструкции разработчика")),
                                        mode("tester", res("Инструкции тестировщика")))));

        assertThat(service.instructionsFor("developer")).isEqualTo("Инструкции разработчика");
        assertThat(service.instructionsFor("tester")).isEqualTo("Инструкции тестировщика");
    }

    @Test
    void returnsEmptyForNullOrUnknownMode() {
        ChatModeService service =
                new ChatModeService(
                        new ChatModeProperties(List.of(mode("developer", res("текст")))));

        assertThat(service.instructionsFor(null)).isEmpty();
        assertThat(service.instructionsFor("unknown")).isEmpty();
    }

    @Test
    void bundledModePromptsAreReadableAndNonEmpty() {
        ChatModeService service =
                new ChatModeService(
                        new ChatModeProperties(
                                List.of(
                                        mode(
                                                "analytic",
                                                new ClassPathResource("prompt/mode-analytic.md")),
                                        mode(
                                                "developer",
                                                new ClassPathResource("prompt/mode-developer.md")),
                                        mode(
                                                "tester",
                                                new ClassPathResource("prompt/mode-tester.md")))));

        assertThat(service.instructionsFor("analytic")).contains("Аналитик");
        assertThat(service.instructionsFor("developer")).contains("Разработчик");
        assertThat(service.instructionsFor("tester")).contains("Тестировщик");
    }

    private static Resource res(String text) {
        return new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
