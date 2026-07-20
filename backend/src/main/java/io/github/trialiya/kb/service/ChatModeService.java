package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModeProperties.Mode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Загружает тексты режимов из classpath-ресурсов один раз при старте и отдаёт фрагмент инструкций
 * по id режима. Фрагмент подставляется в системный промпт через плейсхолдер {@code
 * {mode_instructions}} (см. {@code ChatController}/{@code ChatRunService}). «Без режима» → пустая
 * строка (плейсхолдер всё равно должен получить значение, иначе рендер шаблона упадёт).
 */
@Service
public class ChatModeService {

    private final Map<String, String> instructionsById = new LinkedHashMap<>();

    public ChatModeService(ChatModeProperties modeProperties) {
        for (Mode mode : modeProperties.modes()) {
            instructionsById.put(mode.id(), read(mode));
        }
    }

    /** Фрагмент инструкций для режима (или {@code ""} для {@code null}/неизвестного id). */
    public String instructionsFor(@Nullable String modeId) {
        if (modeId == null) {
            return "";
        }
        return instructionsById.getOrDefault(modeId, "");
    }

    private static String read(Mode mode) {
        try {
            return StreamUtils.copyToString(mode.prompt().getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Не удалось прочитать промпт режима '" + mode.id() + "': " + mode.prompt(), e);
        }
    }
}
