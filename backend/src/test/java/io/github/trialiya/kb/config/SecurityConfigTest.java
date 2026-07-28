package io.github.trialiya.kb.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.controller.SpaForwardController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.h2console.autoconfigure.H2ConsoleProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Пины для двух вещей, которые уже один раз молча сломались.
 *
 * <p>Первая — доступность H2-консоли. Spring Boot 4 вынес {@code H2ConsoleAutoConfiguration} в
 * отдельный модуль {@code spring-boot-h2console}; пока его не было в зависимостях, {@code
 * spring.h2.console.enabled} не делал ничего и {@code /h2-console} отдавал 404, хотя документация
 * обещала консоль. Тест ловит повторение: с включённой консолью путь должен требовать
 * аутентификацию, а не проваливаться мимо цепочки.
 *
 * <p>Вторая — область действия послабления {@code X-Frame-Options}. Консоль рисует себя во фреймах
 * и под дефолтным {@code DENY} открывается пустой, поэтому на её пути стоит {@code SAMEORIGIN}. Оно
 * должно оставаться только там: если послабление расползётся на остальное приложение, весь UI
 * станет встраиваемым в чужой фрейм.
 *
 * <p>Слайс поднимает {@link SpaForwardController} просто чтобы в контексте был хоть один обработчик
 * — проверяются фильтры безопасности, а не маршрутизация. {@code @EnableWebSecurity} нужен явно:
 * {@code @WebMvcTest} в Boot 4 не втягивает security-автоконфигурацию, без которой в контексте нет
 * прототипа {@code HttpSecurity}. Сам сервлет консоли в слайс не попадает, поэтому
 * аутентифицированный запрос к {@code /h2-console} доходит до 404 — важно, что он проходит через
 * свою цепочку и приносит её заголовки.
 */
class SecurityConfigTest {

    @Nested
    @WebMvcTest(SpaForwardController.class)
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    @EnableConfigurationProperties({SecurityProperties.class, H2ConsoleProperties.class})
    @TestPropertySource(properties = "spring.h2.console.enabled=true")
    class ConsoleEnabled {

        @Autowired private MockMvc mockMvc;

        @Test
        void h2ConsoleAllowsSameOriginFraming() throws Exception {
            mockMvc.perform(get("/h2-console/"))
                    .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        }

        @Test
        void restOfTheAppKeepsFramingDenied() throws Exception {
            mockMvc.perform(get("/chat").with(httpBasic("admin", "admin")))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Nested
    @WebMvcTest(SpaForwardController.class)
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    @EnableConfigurationProperties(SecurityProperties.class)
    class ConsoleDisabled {

        @Autowired private MockMvc mockMvc;

        /**
         * Без {@code spring.h2.console.enabled=true} условный бин цепочки не создаётся — приложение
         * должно подниматься и работать обычным образом (профиль Postgres живёт именно так).
         */
        @Test
        void appStillRequiresAuthenticationEverywhere() throws Exception {
            mockMvc.perform(get("/chat")).andExpect(status().isUnauthorized());
        }

        @Test
        void authenticatedRequestsKeepFramingDenied() throws Exception {
            mockMvc.perform(get("/chat").with(httpBasic("admin", "admin")))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }
}
