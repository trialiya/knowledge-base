package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Пороги агентного цикла из {@code application.yaml} действительно доезжают до {@link
 * ToolCallingProperties}.
 *
 * <p>Ключи {@code spring.ai.tools.limits.*} принадлежат Spring AI, а не нам, и промах в имени
 * ничего не ломает: свойство просто игнорируется, а менеджер молча берёт дефолты фреймворка (40 на
 * инструмент, 150 суммарно, обрыв прогона исключением) — то есть настройка исчезает ровно там, где
 * её никто не заметит до первого длинного ответа. Тест держит связку имён и значений.
 */
class ToolCallLimitsConfigTest {

    private final ToolCallingProperties properties = bindApplicationYaml();

    @Test
    void thresholdsAreRaisedAboveTheFrameworkDefaults() {
        assertThat(properties.getLimits().getMaxCallsPerToolDefault()).isEqualTo(80);
        assertThat(properties.getLimits().getMaxTotalToolCalls()).isEqualTo(300);
    }

    @Test
    void breachingALimitAnswersTheModelInsteadOfEndingTheRun() {
        assertThat(properties.getLimits().getOnLimitExceeded())
                .isEqualTo(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE);
    }

    /**
     * Биндится только ветка {@code spring.ai.tools}: остальной файл полон плейсхолдеров без
     * значений по умолчанию ({@code ${AI_BASE_URL}}), а разрешаются они лениво, по мере привязки.
     */
    private static ToolCallingProperties bindApplicationYaml() {
        final MutablePropertySources sources = new MutablePropertySources();
        yaml().forEach(sources::addLast);
        return new Binder(
                        ConfigurationPropertySources.from(sources),
                        new PropertySourcesPlaceholdersResolver(sources))
                .bind("spring.ai.tools", Bindable.ofInstance(new ToolCallingProperties()))
                .get();
    }

    private static List<PropertySource<?>> yaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("application.yaml не читается", e);
        }
    }
}
