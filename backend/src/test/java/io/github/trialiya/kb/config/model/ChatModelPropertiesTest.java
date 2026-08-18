package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
                new ModelOption("default-model", "Default", true, null, null),
                List.of(new ModelOption("gpt-4o-mini", "Mini", false, null, null)));
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
                new ChatModelProperties(new ModelOption("solo", "Solo", true, null, null), null);
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
    void aModelWithoutAnEndpointOfItsOwnSharesTheDefaultConnection() {
        ModelOption shared = new ModelOption("shared", "Shared", true, null, null);
        assertThat(shared.hasOwnEndpoint()).isFalse();
    }

    @Test
    void blankBaseUrlAndApiKeyAreTheSameAsAbsent() {
        ModelOption shared = new ModelOption("shared", "Shared", true, "  ", "  ");
        assertThat(shared.baseUrl()).isNull();
        assertThat(shared.apiKey()).isNull();
        assertThat(shared.hasOwnEndpoint()).isFalse();
    }

    @Test
    void ownHostWithItsOwnTokenGetsAnEndpointOfItsOwn() {
        ModelOption own =
                new ModelOption("remote", "Remote", false, "https://llm.example/v1", "sk-remote");
        assertThat(own.hasOwnEndpoint()).isTrue();
        assertThat(own.baseUrl()).isEqualTo("https://llm.example/v1");
    }

    @Test
    void ownTokenWithoutAHostIsAllowedAndStillNeedsItsOwnConnection() {
        // Same host, separate account or quota — nothing to guess, so nothing to reject.
        ModelOption ownKey = new ModelOption("billed-apart", "Billed apart", false, null, "sk-two");
        assertThat(ownKey.hasOwnEndpoint()).isTrue();
    }

    @Test
    void ownHostWithoutATokenIsRejected() {
        // The one combination nobody means: a foreign host reached with the default host's token.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ModelOption(
                                        "remote", "Remote", false, "https://llm.example/v1", null))
                .withMessageContaining("api-key");
    }

    @Test
    void anEndpointOnTheDefaultModelIsRejected() {
        // spring.ai.openai.* is the default model's endpoint; a second one here would bind and
        // report ownEndpoint without ever being built, so the configuration must not accept it.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ChatModelProperties(
                                        new ModelOption(
                                                "solo",
                                                "Solo",
                                                true,
                                                "https://llm.example/v1",
                                                "sk-solo"),
                                        List.of()))
                .withMessageContaining("kb.chat.models");
    }

    @Test
    void theTokenIsNotPrinted() {
        // @JsonIgnore covers the API; toString is the other way a secret reaches a log line.
        ModelOption own =
                new ModelOption("remote", "Remote", false, "https://llm.example/v1", "sk-remote");
        assertThat(own.toString()).doesNotContain("sk-remote").contains("remote", "***");
    }

    @Test
    void isWeakDefaultsToTrueForAnUnknownId() {
        // Should not happen past isAllowed(), but the conservative fallback is "assume weak" —
        // missing the tutorial hurts a weak model more than an extra paragraph hurts a strong one.
        assertThat(props().isWeak("evil-model")).isTrue();
    }
}
