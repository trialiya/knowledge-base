package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
                new ModelOption("default-model", "Default", true, true, null, null, null, false, null),
                List.of(new ModelOption("gpt-4o-mini", "Mini", false, true, null, null, null, false, null)));
    }

    /** Пустое значение в строке — {@code null}: модель в запросе не названа вовсе. */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"default-model,true", "gpt-4o-mini,true", "evil-model,false", ",false"})
    void onlyAConfiguredModelIsAllowed(@Nullable String model, boolean allowed) {
        assertThat(props().isAllowed(model)).isEqualTo(allowed);
    }

    /**
     * Рассуждения в истории — свойство модели: выключенные у одной, они не должны уехать её
     * эндпоинту, который поля не знает. Неизвестная модель получает умолчание — да.
     */
    @Test
    void replayReasoningIsResolvedPerModelAndOnForAnUnknownOne() {
        final ChatModelProperties models = new ChatModelProperties(
                new ModelOption("default-model", "Default", true, true, null, null, null, false, null),
                List.of(new ModelOption("deepseek", "DeepSeek", false, true, null, null, null, true, null)));

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
                new ModelOption("default-model", "Default", true, true, null, null, null, false, null),
                List.of(new ModelOption("picky-gateway", "Picky", false, false, null, null, null, false, null)));

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
                new ModelOption("default-model", "Default", true, true, 200_000, null, null, false, null),
                List.of(
                        new ModelOption("small", "Small", false, true, 8_000, null, null, false, null),
                        new ModelOption("unnamed", "Unnamed", false, true, null, null, null, false, null)));

        assertThat(props.contextTokens("small")).isEqualTo(8_000);
        assertThat(props.contextTokens("default-model")).isEqualTo(200_000);
        // null — «модель не переопределяли», то есть дефолтная; параллель isWeak.
        assertThat(props.contextTokens(null)).isEqualTo(200_000);
        assertThat(props.contextTokens("unnamed")).isNull();
        assertThat(props.contextTokens("evil-model")).isNull();
    }

    @Test
    void nullModelsListDefaultsToEmptyAndAllowsOnlyDefault() {
        ChatModelProperties only = new ChatModelProperties(
                new ModelOption("solo", "Solo", true, true, null, null, null, false, null), null);
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

    /** Пустые строки в конфигурации — то же, что их отсутствие: модель ходит общим подключением. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "  ")
    void aModelWithoutAnEndpointOfItsOwnSharesTheDefaultConnection(@Nullable String blank) {
        ModelOption shared = new ModelOption("shared", "Shared", true, true, null, blank, blank, false, null);
        assertThat(shared.baseUrl()).isNull();
        assertThat(shared.apiKey()).isNull();
        assertThat(shared.hasOwnEndpoint()).isFalse();
    }

    @Test
    void ownHostWithItsOwnTokenGetsAnEndpointOfItsOwn() {
        ModelOption own = new ModelOption(
                "remote", "Remote", false, true, null, "https://llm.example/v1", "sk-remote", false, null);
        assertThat(own.hasOwnEndpoint()).isTrue();
        assertThat(own.baseUrl()).isEqualTo("https://llm.example/v1");
    }

    @Test
    void ownTokenWithoutAHostIsAllowedAndStillNeedsItsOwnConnection() {
        // Same host, separate account or quota — nothing to guess, so nothing to reject.
        ModelOption ownKey =
                new ModelOption("billed-apart", "Billed apart", false, true, null, null, "sk-two", false, null);
        assertThat(ownKey.hasOwnEndpoint()).isTrue();
    }

    @Test
    void ownHostWithoutATokenIsRejected() {
        // The one combination nobody means: a foreign host reached with the default host's token.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelOption(
                        "remote", "Remote", false, true, null, "https://llm.example/v1", null, false, null))
                .withMessageContaining("api-key");
    }

    @Test
    void anEndpointOnTheDefaultModelIsRejected() {
        // spring.ai.openai.* is the default model's endpoint; a second one here would bind and
        // report ownEndpoint without ever being built, so the configuration must not accept it.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatModelProperties(
                        new ModelOption(
                                "solo", "Solo", true, true, null, "https://llm.example/v1", "sk-solo", false, null),
                        List.of()))
                .withMessageContaining("kb.chat.models");
    }

    @Test
    void theTokenIsNotPrinted() {
        // @JsonIgnore covers the API; toString is the other way a secret reaches a log line.
        ModelOption own = new ModelOption(
                "remote", "Remote", false, true, null, "https://llm.example/v1", "sk-remote", false, null);
        assertThat(own.toString()).doesNotContain("sk-remote").contains("remote", "***");
    }

    @Test
    void isWeakDefaultsToTrueForAnUnknownId() {
        // Should not happen past isAllowed(), but the conservative fallback is "assume weak" —
        // missing the tutorial hurts a weak model more than an extra paragraph hurts a strong one.
        assertThat(props().isWeak("evil-model")).isTrue();
    }

    /**
     * Уровни рассуждений приходят из YAML — блоком, где {@code default} назван ключевым словом Java,
     * а {@code extra-body} несёт вложенные поля провайдера с подчёркиваниями. И то и другое обязано
     * доехать до записи как написано — а значение, пришедшее строкой (так его отдают переменные
     * окружения и {@code .properties}), с типом JSON: {@code "false"} провайдеру — это истина.
     */
    @Test
    void reasoningLevelsBindFromTheConfiguration() {
        final ChatModelProperties bound = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
                        Map.entry("kb.chat.default-model.id", "gpt-5"),
                        Map.entry("kb.chat.default-model.label", "GPT-5"),
                        Map.entry("kb.chat.default-model.reasoning.default", "medium"),
                        Map.entry("kb.chat.default-model.reasoning.levels[0].id", "low"),
                        Map.entry("kb.chat.default-model.reasoning.levels[0].reasoning-effort", "low"),
                        Map.entry("kb.chat.default-model.reasoning.levels[1].id", "medium"),
                        Map.entry("kb.chat.default-model.reasoning.levels[1].reasoning-effort", "medium"),
                        Map.entry("kb.chat.models[0].id", "qwen"),
                        Map.entry("kb.chat.models[0].label", "Qwen"),
                        Map.entry("kb.chat.models[0].reasoning.levels[0].id", "off"),
                        Map.entry(
                                "kb.chat.models[0].reasoning.levels[0].extra-body.chat_template_kwargs.enable_thinking",
                                "false"))))
                .bind("kb.chat", ChatModelProperties.class)
                .get();

        assertThat(bound.defaultModel().reasoning().defaultLevel()).isEqualTo("medium");
        assertThat(bound.reasoningLevel(null, null))
                .get()
                .extracting(ReasoningOptions.Level::reasoningEffort)
                .isEqualTo("medium");
        assertThat(bound.reasoningLevel("qwen", "off").orElseThrow().extraBody())
                .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
    }

    /**
     * Выбор хранится у чата, а модель в нём переключают: уровень, которого у модели нет, уступает её
     * умолчанию, а без умолчания отправлять нечего. Модель без блока уровней не получает ничего.
     */
    @Test
    void aReasoningLevelFallsBackToTheModelsDefault() {
        final ChatModelProperties props = new ChatModelProperties(
                new ModelOption(
                        "gpt-5",
                        "GPT-5",
                        false,
                        true,
                        null,
                        null,
                        null,
                        true,
                        new ReasoningOptions("medium", List.of(effort("low"), effort("medium"), effort("high")))),
                List.of(
                        new ModelOption(
                                "deepseek",
                                "DeepSeek",
                                false,
                                true,
                                null,
                                null,
                                null,
                                true,
                                new ReasoningOptions(
                                        null,
                                        List.of(new ReasoningOptions.Level(
                                                "off", null, null, Map.of("thinking", Map.of("type", "disabled")))))),
                        new ModelOption("plain", "Plain", false, true, null, null, null, true, null)));

        assertThat(props.reasoningLevel("gpt-5", "high").map(ReasoningOptions.Level::id))
                .hasValue("high");
        assertThat(props.reasoningLevel(null, "off").map(ReasoningOptions.Level::id))
                .hasValue("medium");
        assertThat(props.reasoningLevel("deepseek", "off").map(ReasoningOptions.Level::id))
                .hasValue("off");
        assertThat(props.reasoningLevel("deepseek", "high")).isEmpty();
        assertThat(props.reasoningLevel("plain", "high")).isEmpty();
        assertThat(props.reasoningLevel("evil-model", "high")).isEmpty();

        assertThat(props.isKnownReasoningLevel("off")).isTrue();
        assertThat(props.isKnownReasoningLevel("high")).isTrue();
        assertThat(props.isKnownReasoningLevel("max")).isFalse();
        assertThat(props.isKnownReasoningLevel(null)).isFalse();
    }

    /** Число, пришедшее строкой, уходит числом; строка, которая числом не является, — строкой. */
    @Test
    void extraBodyNumbersArriveTypedAndOtherStringsStayAsIs() {
        final ReasoningOptions.Level level = new ReasoningOptions.Level(
                "think", null, null, Map.of("thinking", Map.of("type", "enabled", "budget_tokens", "1024")));

        assertThat(level.extraBody()).isEqualTo(Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 1024L)));
    }

    /**
     * Список из YAML доходит до записи картой с ключами-индексами — и уходит провайдеру массивом,
     * в порядке индексов, а не порядке ключей ({@code 10} после {@code 9}, а не после {@code 1}).
     */
    @Test
    void extraBodyListsArriveAsArrays() {
        final Map<String, Object> order = new java.util.LinkedHashMap<>();
        for (final int i : new int[] {0, 1, 10, 2, 3, 4, 5, 6, 7, 8, 9}) {
            order.put(String.valueOf(i), "p" + i);
        }
        final ReasoningOptions.Level level =
                new ReasoningOptions.Level("routed", null, null, Map.of("provider", Map.of("order", order)));

        assertThat(level.extraBody())
                .isEqualTo(Map.of(
                        "provider",
                        Map.of("order", List.of("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10"))));
    }

    @Test
    void aReasoningLevelsListBindsFromYamlAsAnArray() {
        final ChatModelProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                        "kb.chat.default-model.id", "router",
                        "kb.chat.default-model.label", "Router",
                        "kb.chat.default-model.reasoning.levels[0].id", "high",
                        "kb.chat.default-model.reasoning.levels[0].extra-body.provider.order[0]", "anthropic",
                        "kb.chat.default-model.reasoning.levels[0].extra-body.provider.order[1]", "openai")))
                .bind("kb.chat", ChatModelProperties.class)
                .get();

        assertThat(bound.reasoningLevel(null, "high").orElseThrow().extraBody())
                .isEqualTo(Map.of("provider", Map.of("order", List.of("anthropic", "openai"))));
    }

    @Test
    void aReasoningDefaultOutsideTheLevelsIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReasoningOptions("max", List.of(effort("low"))))
                .withMessageContaining("max");
    }

    @Test
    void aReasoningLevelListedTwiceIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReasoningOptions(null, List.of(effort("low"), effort("low"))))
                .withMessageContaining("twice");
    }

    @Test
    void aReasoningLevelWithoutAnIdIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> effort(" "));
    }

    private static ReasoningOptions.Level effort(String id) {
        return new ReasoningOptions.Level(id, null, id, null);
    }
}
