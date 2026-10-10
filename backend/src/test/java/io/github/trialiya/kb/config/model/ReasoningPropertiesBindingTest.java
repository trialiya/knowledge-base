package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Пустая переменная окружения ({@code ${KB_..._THINKING:}}) у каждой записи с полями «размышлений»
 * значит «не задано», а не пустое значение для провайдера: эндпоинт, который не знает {@code
 * thinking}, отвергает весь запрос, а пустой {@code reasoning_effort} — и знающий.
 */
class ReasoningPropertiesBindingTest {

    static Stream<Arguments> records() {
        return Stream.of(
                Arguments.of(
                        "kb.search.subagent",
                        SubAgentConfig.class,
                        Map.of(
                                "enabled", "true",
                                "model-id", "sub-model",
                                "max-tokens", "12000",
                                "max-iterations", "30",
                                "allowed-tools[0]", "grepContent",
                                "temperature", ""),
                        (Function<SubAgentConfig, List<Object>>)
                                c -> Arrays.asList(c.reasoningEffort(), c.thinking(), c.temperature())),
                Arguments.of("kb.chat.summarize", SummarizeProperties.class, Map.of("model", " "), (Function<
                                SummarizeProperties, List<Object>>)
                        p -> Arrays.asList(p.reasoningEffort(), p.thinking(), p.model())),
                Arguments.of("kb.chat.topic", ChatTopicProperties.class, Map.of("model", " "), (Function<
                                ChatTopicProperties, List<Object>>)
                        p -> Arrays.asList(p.reasoningEffort(), p.thinking(), p.model())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    <T> void emptyValuesBindAsUnset(
            String prefix, Class<T> type, Map<String, String> extra, Function<T, List<Object>> optionalFields) {
        final Map<String, String> source = new HashMap<>();
        source.put(prefix + ".reasoning-effort", " ");
        source.put(prefix + ".thinking", "");
        extra.forEach((key, value) -> source.put(prefix + "." + key, value));

        final T bound = new Binder(new MapConfigurationPropertySource(source))
                .bind(prefix, type)
                .get();

        assertThat(optionalFields.apply(bound)).containsOnlyNulls();
    }
}
