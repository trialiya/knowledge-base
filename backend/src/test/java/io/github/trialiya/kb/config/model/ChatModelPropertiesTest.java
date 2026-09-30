package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Юнит-тест правила допуска модели — это контракт, на который опираются {@code ChatController}
 * (валидация поля {@code model} в теле запроса и {@code PUT /model}) при работе с моделями.
 * Контейнер не нужен.
 */
class ChatModelPropertiesTest {

    private static ChatModelProperties props() {
        return new ChatModelProperties(
                new ModelOption("default-model", "Default", true, true, null, null, null, false),
                List.of(new ModelOption("gpt-4o-mini", "Mini", false, true, null, null, null, false)));
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

    /**
     * Рассуждения в истории — свойство модели: выключенные у одной, они не должны уехать её
     * эндпоинту, который поля не знает. Неизвестная модель получает умолчание — да.
     */
    @Test
    void replayReasoningIsResolvedPerModelAndOnForAnUnknownOne() {
        final ChatModelProperties models = new ChatModelProperties(
                new ModelOption("default-model", "Default", true, true, null, null, null, false),
                List.of(new ModelOption("deepseek", "DeepSeek", false, true, null, null, null, true)));

        assertThat(models.replayReasoning(null)).isFalse();
        assertThat(models.replayReasoning("deepseek")).isTrue();
        assertThat(models.replayReasoning("unknown")).isTrue();
    }

    /**
     * Умолчание — из самой привязки конфигурации: модель, у которой {@code replay-reasoning} не
     * написан, рассуждения получает. Выключенный же флаг обязан дойти до записи как есть.
     */
    @Test
    void replayReasoningIsOnUnlessTheConfigTurnsItOff() {
        final ChatModelProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                        "kb.chat.default-model.id", "deepseek",
                        "kb.chat.default-model.label", "DeepSeek",
                        "kb.chat.models[0].id", "groq",
                        "kb.chat.models[0].label", "Groq",
                        "kb.chat.models[0].replay-reasoning", "false")))
                .bind("kb.chat", ChatModelProperties.class)
                .get();

        assertThat(bound.replayReasoning("deepseek")).isTrue();
        assertThat(bound.replayReasoning("groq")).isFalse();
    }

    /**
     * Счётчик токенов — свойство эндпоинта, а не деплоя: выключенный на одном шлюзе он обязан
     * остаться включённым у остальных моделей.
     */
    @Test
    void streamUsageIsResolvedPerModel() {
        final ChatModelProperties props = new ChatModelProperties(
                new ModelOption("default-model", "Default", true, true, null, null, null, false),
                List.of(new ModelOption("picky-gateway", "Picky", false, false, null, null, null, false)));

        assertThat(props.streamUsage("picky-gateway")).isFalse();
        assertThat(props.streamUsage("default-model")).isTrue();
        // null — «модель не переопределяли», то есть дефолтная; параллель isWeak.
        assertThat(props.streamUsage(null)).isTrue();
        // Неизвестная модель считается умеющей: лишний запрос счётчика стоит ничего, его
        // отсутствие — прогон без цифры навсегда.
        assertThat(props.streamUsage("evil-model")).isTrue();
    }

    /**
     * Окно — свойство модели, и цена ошибки здесь противоположна {@code stream-usage}: выдуманное
     * за конфигурацию число решает, когда переписать историю чата, поэтому неназванного окна не
     * бывает — бывает только его отсутствие.
     */
    @Test
    void contextTokensAreResolvedPerModelAndNeverGuessed() {
        final ChatModelProperties props = new ChatModelProperties(
                new ModelOption("default-model", "Default", true, true, 200_000, null, null, false),
                List.of(
                        new ModelOption("small", "Small", false, true, 8_000, null, null, false),
                        new ModelOption("unnamed", "Unnamed", false, true, null, null, null, false)));

        assertThat(props.contextTokens("small")).isEqualTo(8_000);
        assertThat(props.contextTokens("default-model")).isEqualTo(200_000);
        // null — «модель не переопределяли», то есть дефолтная; параллель isWeak.
        assertThat(props.contextTokens(null)).isEqualTo(200_000);
        assertThat(props.contextTokens("unnamed")).isNull();
        assertThat(props.contextTokens("evil-model")).isNull();
    }

    @Test
    void nullModelsListDefaultsToEmptyAndAllowsOnlyDefault() {
        ChatModelProperties only =
                new ChatModelProperties(new ModelOption("solo", "Solo", true, true, null, null, null, false), null);
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
        ModelOption shared = new ModelOption("shared", "Shared", true, true, null, null, null, false);
        assertThat(shared.hasOwnEndpoint()).isFalse();
    }

    @Test
    void blankBaseUrlAndApiKeyAreTheSameAsAbsent() {
        ModelOption shared = new ModelOption("shared", "Shared", true, true, null, "  ", "  ", false);
        assertThat(shared.baseUrl()).isNull();
        assertThat(shared.apiKey()).isNull();
        assertThat(shared.hasOwnEndpoint()).isFalse();
    }

    @Test
    void ownHostWithItsOwnTokenGetsAnEndpointOfItsOwn() {
        ModelOption own =
                new ModelOption("remote", "Remote", false, true, null, "https://llm.example/v1", "sk-remote", false);
        assertThat(own.hasOwnEndpoint()).isTrue();
        assertThat(own.baseUrl()).isEqualTo("https://llm.example/v1");
    }

    @Test
    void ownTokenWithoutAHostIsAllowedAndStillNeedsItsOwnConnection() {
        // Same host, separate account or quota — nothing to guess, so nothing to reject.
        ModelOption ownKey = new ModelOption("billed-apart", "Billed apart", false, true, null, null, "sk-two", false);
        assertThat(ownKey.hasOwnEndpoint()).isTrue();
    }

    @Test
    void ownHostWithoutATokenIsRejected() {
        // The one combination nobody means: a foreign host reached with the default host's token.
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new ModelOption("remote", "Remote", false, true, null, "https://llm.example/v1", null, false))
                .withMessageContaining("api-key");
    }

    @Test
    void anEndpointOnTheDefaultModelIsRejected() {
        // spring.ai.openai.* is the default model's endpoint; a second one here would bind and
        // report ownEndpoint without ever being built, so the configuration must not accept it.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatModelProperties(
                        new ModelOption("solo", "Solo", true, true, null, "https://llm.example/v1", "sk-solo", false),
                        List.of()))
                .withMessageContaining("kb.chat.models");
    }

    @Test
    void theTokenIsNotPrinted() {
        // @JsonIgnore covers the API; toString is the other way a secret reaches a log line.
        ModelOption own =
                new ModelOption("remote", "Remote", false, true, null, "https://llm.example/v1", "sk-remote", false);
        assertThat(own.toString()).doesNotContain("sk-remote").contains("remote", "***");
    }

    @Test
    void isWeakDefaultsToTrueForAnUnknownId() {
        // Should not happen past isAllowed(), but the conservative fallback is "assume weak" —
        // missing the tutorial hurts a weak model more than an extra paragraph hurts a strong one.
        assertThat(props().isWeak("evil-model")).isTrue();
    }
}
