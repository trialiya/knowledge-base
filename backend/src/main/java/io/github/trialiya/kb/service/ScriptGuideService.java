package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.ScriptProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Loads the {@code runScript} handbook once at startup and hands it to the system prompt through
 * the {@code {script_instructions}} placeholder — the same mechanism as {@code ChatModeService} and
 * {@code {mode_instructions}}.
 *
 * <p>Two reasons it is a prompt fragment rather than part of the tool description. Weak models
 * cannot use the tool from a one-paragraph description: they need the {@code kb} reference, worked
 * examples they can copy, and a table of what to do about each error — far more text than belongs
 * in a tool listing that is sent on every request. And when the tool is off the fragment is empty,
 * so a model that cannot run scripts is never told about scripts; extra instructions hurt a weak
 * model as much as missing ones.
 *
 * <p>The budget numbers in the handbook are substituted from {@code kb.script.limits} rather than
 * written into the markdown, so lowering a limit cannot silently leave the model working from a
 * stale figure.
 */
@Service
public class ScriptGuideService {

    private final String instructions;

    public ScriptGuideService(ScriptProperties properties) {
        this.instructions = properties.enabled() ? render(properties) : "";
    }

    /**
     * The handbook, or {@code ""} when {@code kb.script.enabled=false}. Never null: the placeholder
     * must always receive a value or the prompt template fails to render.
     */
    public String instructions() {
        return instructions;
    }

    private static String render(ScriptProperties properties) {
        ScriptProperties.Limits limits = properties.limits();
        Map<String, String> values =
                Map.of(
                        "max_files_read", String.valueOf(limits.maxFilesRead()),
                        "max_bytes_read", limits.maxBytesRead().toMegabytes() + " МБ",
                        "max_file_bytes", limits.maxFileBytes().toKilobytes() + " КБ",
                        "max_grep_matches", String.valueOf(limits.maxGrepMatches()),
                        "max_calls", String.valueOf(limits.maxCalls()),
                        "max_log_chars", String.valueOf(limits.maxLogChars()),
                        "max_result_chars", String.valueOf(limits.maxResultChars()),
                        "timeout", properties.timeout().toSeconds() + " с",
                        "max_timeout", properties.maxTimeout().toSeconds() + " с");

        String text = read(properties);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return text;
    }

    private static String read(ScriptProperties properties) {
        try {
            return StreamUtils.copyToString(
                            properties.guide().getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Не удалось прочитать руководство по скриптам: " + properties.guide(), e);
        }
    }
}
