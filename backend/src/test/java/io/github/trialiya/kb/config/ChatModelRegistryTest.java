package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Который {@code kb.chat.models} получает собственное соединение, а который живёт на общем. Сеть не
 * нужна: клиент OkHttp создаётся лениво, тест смотрит только на то, какой {@link OpenAiChatModel}
 * реестр отдаёт по id.
 */
class ChatModelRegistryTest {

    private static final ModelOption DEFAULT_MODEL =
            new ModelOption("default-model", "Default", true, true, null, null, null);

    @Test
    void onlyModelsWithTheirOwnEndpointGetAConnectionOfTheirOwn() {
        OpenAiChatModel defaultConnection = mock(OpenAiChatModel.class);
        ChatModelRegistry registry =
                build(
                        defaultConnection,
                        new ModelOption("shared", "Shared", true, true, null, null, null),
                        new ModelOption(
                                "remote",
                                "Remote",
                                false,
                                true,
                                null,
                                "https://llm.example/v1",
                                "sk-r"),
                        new ModelOption("own-key", "Own key", false, true, null, null, "sk-k"));

        assertThat(registry.ownEndpointModelIds()).containsExactlyInAnyOrder("remote", "own-key");
        // Никакого переопределения на прогон — дефолтное соединение.
        assertThat(registry.forModel(null)).isSameAs(defaultConnection);
        assertThat(registry.forModel("shared")).isSameAs(defaultConnection);
        assertThat(registry.forModel("default-model")).isSameAs(defaultConnection);
        // Неизвестный id сюда дойти не должен (см. isAllowed), но и тогда ответ — дефолт.
        assertThat(registry.forModel("unknown")).isSameAs(defaultConnection);

        assertThat(registry.forModel("remote")).isNotSameAs(defaultConnection);
        assertThat(registry.forModel("own-key"))
                .isNotSameAs(defaultConnection)
                .isNotSameAs(registry.forModel("remote"));
    }

    @Test
    void withoutSuchModelsThereIsOnlyTheDefaultConnection() {
        OpenAiChatModel defaultConnection = mock(OpenAiChatModel.class);
        ChatModelRegistry registry =
                build(
                        defaultConnection,
                        new ModelOption("shared", "Shared", true, true, null, null, null));

        assertThat(registry.ownEndpointModelIds()).isEmpty();
        assertThat(registry.forModel("shared")).isSameAs(defaultConnection);
    }

    @Test
    void anEntryRepeatingTheDefaultIdServesRunsWithoutAnOverrideToo() {
        // Отдельный эндпоинт у модели по умолчанию задаётся строкой kb.chat.models с тем же id
        // (см. ChatModelProperties). Прогон без явной модели идёт на неё же, иначе соединение
        // работало бы только когда пользователь выбрал модель в списке руками.
        OpenAiChatModel defaultConnection = mock(OpenAiChatModel.class);
        ChatModelRegistry registry =
                build(
                        defaultConnection,
                        new ModelOption(
                                "default-model",
                                "Default",
                                true,
                                true,
                                null,
                                "https://llm.example/v1",
                                "sk-d"));

        assertThat(registry.forModel(null)).isNotSameAs(defaultConnection);
        assertThat(registry.forModel(null)).isSameAs(registry.forModel("default-model"));
    }

    @Test
    void everyConnectionCarriesTheCallDeadlineInItsRequestOptions() {
        // Дедлайн вызова живёт в опциях запроса, а не в клиенте: OpenAiChatModel собирает из них
        // RequestOptions, а таймаут запроса сильнее клиентского. Причём опции несут его всегда —
        // билдер OpenAiChatOptions по умолчанию ставит 60 с, и ни одно свойство до этого поля не
        // достаёт, — так что без подстановки здесь ЛЮБОЙ вызов приложения жил бы минуту. Замерено:
        // с клиентом на 10m ответ, приходящий на 75-й секунде, обрывается на 61-й.
        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl("https://default.example");
        common.setApiKey("sk-default");
        common.setTimeout(Duration.ofMinutes(10));
        OpenAiChatProperties chat = new OpenAiChatProperties();

        OpenAiChatModel shared =
                ChatModelRegistry.buildDefaultModel(
                        common, chat, mock(ToolCallingManager.class), absent(), absent(), empty());
        ChatModelRegistry registry =
                ChatModelRegistry.build(
                        shared,
                        new ChatModelProperties(
                                DEFAULT_MODEL,
                                List.of(
                                        new ModelOption(
                                                "remote",
                                                "Remote",
                                                false,
                                                true,
                                                null,
                                                "https://llm.example/v1",
                                                "sk-r"))),
                        common,
                        chat,
                        mock(ToolCallingManager.class),
                        absent(),
                        absent(),
                        empty());

        assertThat(shared.getOptions().getTimeout())
                .isEqualTo(Duration.ofMinutes(10))
                .isNotEqualTo(AbstractOpenAiProperties.DEFAULT_TIMEOUT);
        assertThat(registry.forModel("remote").getOptions().getTimeout())
                .isEqualTo(Duration.ofMinutes(10));
        // Ловушка, ради которой это закреплено тестом: незаданный дедлайн — не «как у клиента».
        assertThat(chat.toOptions().getTimeout())
                .isEqualTo(AbstractOpenAiProperties.DEFAULT_TIMEOUT);
    }

    private static ChatModelRegistry build(
            OpenAiChatModel defaultConnection, ModelOption... models) {
        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl("https://default.example");
        common.setApiKey("sk-default");
        return ChatModelRegistry.build(
                defaultConnection,
                new ChatModelProperties(DEFAULT_MODEL, List.of(models)),
                common,
                new OpenAiChatProperties(),
                mock(ToolCallingManager.class),
                absent(),
                absent(),
                empty());
    }

    /** Провайдер без бина: {@code getIfAvailable(supplier)} отдаёт фолбэк вызывающего. */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(provider.getIfAvailable(any(Supplier.class)))
                .thenAnswer(call -> ((Supplier<T>) call.getArgument(0)).get());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.empty());
        return provider;
    }
}
