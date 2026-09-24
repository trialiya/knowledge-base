package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;

/** Разбор значений конфигурации — то общее, что нужно записям {@code kb.*} этого пакета. */
final class ConfigValues {

    private ConfigValues() {}

    /**
     * Пустая строка приходит от {@code ${ПЕРЕМЕННАЯ:}} в {@code application.yaml} и значит «не
     * задано», а не «задано пустым»: пустая модель, отправленная провайдеру, — это отказ на каждом
     * запросе.
     */
    static @Nullable String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
