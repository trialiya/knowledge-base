package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Учётные данные единственного пользователя приложения. Логин и пароль задаются в конфигах
 * (application.yaml / переменные окружения), хранятся в памяти.
 */
@ConfigurationProperties(prefix = "kb.security")
public record SecurityProperties(String username, String password) {

    public SecurityProperties {
        username = (username == null || username.isBlank()) ? "admin" : username;
        password = (password == null || password.isBlank()) ? "admin" : password;
    }
}
