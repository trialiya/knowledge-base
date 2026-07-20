package io.github.trialiya.kb.config.model;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Готовые «режимы» ассистента (Аналитик, Разработчик, Тестировщик, …) — параллель {@link
 * ChatModelProperties}. Каждый режим подставляет свой фрагмент инструкций в системный промпт через
 * плейсхолдер {@code {mode_instructions}} (см. {@code prompt/sys.md} и {@code ChatModeService}).
 *
 * <p>{@code prompt} — classpath-ресурс с текстом режима; наружу (в {@code GET /api/chats/modes})
 * отдаётся только {@code id}/{@code label} (см. {@link #views()}), сам текст остаётся на бэке.
 */
@ConfigurationProperties(prefix = "kb.chat")
public record ChatModeProperties(List<Mode> modes) {

    public ChatModeProperties {
        modes = modes == null ? List.of() : List.copyOf(modes);
    }

    public record Mode(String id, String label, Resource prompt) {}

    /** Проекция режима без текста промпта — то, что видит фронт. */
    public record ModeView(String id, String label) {}

    public boolean isAllowed(@Nullable String id) {
        return id != null && modes.stream().anyMatch(m -> id.equals(m.id()));
    }

    public List<ModeView> views() {
        return modes.stream().map(m -> new ModeView(m.id(), m.label())).toList();
    }
}
