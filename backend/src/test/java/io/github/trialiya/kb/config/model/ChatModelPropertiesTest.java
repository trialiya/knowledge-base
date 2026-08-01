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
                new ModelOption("default-model", "Default", true),
                List.of(new ModelOption("gpt-4o-mini", "Mini", false)));
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
                new ChatModelProperties(new ModelOption("solo", "Solo", true), null);
        assertThat(only.models()).isEmpty();
        assertThat(only.isAllowed("solo")).isTrue();
        assertThat(only.isAllowed("anything-else")).isFalse();
    }

    @Test
    void isWeakFollowsTheMatchingModelsOwnFlag() {
        ChatModelProperties props = props();
        assertThat(props.isWeak("default-model")).isTrue();
        assertThat(props.isWeak("gpt-4o-mini")).isFalse();
    }

    @Test
    void isWeakWithNoOverrideFollowsTheDefaultModel() {
        assertThat(props().isWeak(null)).isTrue();
    }

    @Test
    void isWeakDefaultsToTrueForAnUnknownId() {
        // Should not happen past isAllowed(), but the conservative fallback is "assume weak" —
        // missing the tutorial hurts a weak model more than an extra paragraph hurts a strong one.
        assertThat(props().isWeak("evil-model")).isTrue();
    }
}
