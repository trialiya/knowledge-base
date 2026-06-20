package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
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
