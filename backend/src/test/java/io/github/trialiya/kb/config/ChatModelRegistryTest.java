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
    void ownEndpointConnectionsKeepTheSharedCallTimeout() {
        // Соединение со своим эндпоинтом собираем мы сами, и дедлайн вызова оно берёт из того же
        // spring.ai.openai.timeout, что и автоконфигурируемое. Откат к дефолту фреймворка (60 с)
        // виден только на самом длинном запросе приложения — раунде /compact, который везёт весь
        // контекст одним блокирующим вызовом, — и приходит туда как «Error reading response».
        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setTimeout(Duration.ofMinutes(10));

        assertThat(ChatModelRegistry.callTimeout(common, new OpenAiChatProperties()))
                .isEqualTo(Duration.ofMinutes(10))
                .isNotEqualTo(AbstractOpenAiProperties.DEFAULT_TIMEOUT);

        // Заданный явно чат-уровень остаётся сильнее общего — это и есть причина, по которой
        // общий дедлайн нельзя просто подставить вместо слияния.
        OpenAiChatProperties chat = new OpenAiChatProperties();
        chat.setTimeout(Duration.ofMinutes(2));
        assertThat(ChatModelRegistry.callTimeout(common, chat)).isEqualTo(Duration.ofMinutes(2));
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
