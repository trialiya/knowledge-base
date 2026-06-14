package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Юнит-тест правила допуска модели — это контракт, на который опираются {@code ChatController}
 * (валидация {@code ?model=...} и {@code PUT /model}) при работе с моделями. Контейнер не нужен.
 */
class ChatModelPropertiesTest {

    private static ChatModelProperties props() {
        return new ChatModelProperties(
                new ModelOption("default-model", "Default"),
                List.of(new ModelOption("gpt-4o-mini", "Mini")));
    }

    @Test
    void defaultModelIsAllowed() {
        assertThat(props().isAllowed("default-model")).isTrue();
    }

    @Test
    void configuredAlternativeIsAllowed() {
        assertThat(props().isAllowed("gpt-4o-mini")).isTrue();
    }

    @Test
    void unknownModelIsRejected() {
        assertThat(props().isAllowed("evil-model")).isFalse();
    }

    @Test
    void nullModelIsRejected() {
        assertThat(props().isAllowed(null)).isFalse();
    }

    @Test
    void nullModelsListDefaultsToEmptyAndAllowsOnlyDefault() {
        ChatModelProperties only =
                new ChatModelProperties(new ModelOption("solo", "Solo"), null);
        assertThat(only.models()).isEmpty();
        assertThat(only.isAllowed("solo")).isTrue();
        assertThat(only.isAllowed("anything-else")).isFalse();
    }
}
