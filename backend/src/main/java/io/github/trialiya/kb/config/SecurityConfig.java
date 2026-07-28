package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.SecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Простая HTTP Basic-аутентификация для единственного пользователя из конфигов (см. {@link
 * SecurityProperties}). Пользователь хранится в памяти.
 *
 * <p>Basic-аутентификация stateless: учётные данные передаются с каждым запросом, поэтому сессия
 * «переживает» рестарты бэкенда без хранилища сессий.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsManager(
            SecurityProperties properties, PasswordEncoder passwordEncoder) {
        UserDetails user =
                User.withUsername(properties.username())
                        .password(passwordEncoder.encode(properties.password()))
                        .roles("USER")
                        .build();
        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Отдельная цепочка для H2-консоли — она включена только профилем {@code h2} (см. {@code
     * application-h2.yaml}), поэтому и бин условный: с Postgres пути {@code /h2-console} нет.
     *
     * <p>Консоль рисует себя во фреймах, а общая цепочка ниже оставляет дефолтный {@code
     * X-Frame-Options: DENY} — под ним страница открывается пустой. Ослабляем заголовок до {@code
     * SAMEORIGIN}, и только на пути консоли: во всём остальном приложении остаётся {@code DENY}.
     *
     * <p>HTTP Basic на этот путь намеренно не распространяется ({@code permitAll}): вход в саму
     * консоль всё равно закрыт JDBC-логином из {@code application-h2.yaml}, второй пароль поверх
     * первого — не защита, а лишний клик. Профиль {@code h2} рассчитан на локальную разработку и
     * демо-окружения, не на публичный интернет.
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(PathRequest.toH2Console())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(
                        headers ->
                                headers.frameOptions(
                                        HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
