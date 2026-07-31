package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.script.ScriptEditPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.Resource;
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
    private final String readOnlyInstructions;

    public ScriptGuideService(ScriptProperties properties, ScriptEditPolicy editPolicy) {
        this.instructions = properties.enabled() ? render(properties, editPolicy.enabled()) : "";
        this.readOnlyInstructions = properties.enabled() ? render(properties, false) : "";
    }

    /**
     * The handbook, or {@code ""} when {@code kb.script.enabled=false}. Never null: the placeholder
     * must always receive a value or the prompt template fails to render.
     */
    public String instructions() {
        return instructions;
    }

    /**
     * The handbook without the write appendix, whatever the edit policy says — for the search
     * sub-agent, which is read-only by construction and must stay that way even in a deployment
     * where the main chat may edit files.
     */
    public String readOnlyInstructions() {
        return readOnlyInstructions;
    }

    private static String render(ScriptProperties properties, boolean editEnabled) {
        ScriptProperties.Limits limits = properties.limits();
        Map<String, String> values =
                Map.ofEntries(
                        Map.entry("max_files_read", String.valueOf(limits.maxFilesRead())),
                        Map.entry("max_bytes_read", limits.maxBytesRead().toMegabytes() + " МБ"),
                        Map.entry("max_file_bytes", limits.maxFileBytes().toKilobytes() + " КБ"),
                        Map.entry("max_grep_matches", String.valueOf(limits.maxGrepMatches())),
                        Map.entry("max_calls", String.valueOf(limits.maxCalls())),
                        Map.entry("max_log_chars", String.valueOf(limits.maxLogChars())),
                        Map.entry("max_result_chars", String.valueOf(limits.maxResultChars())),
                        Map.entry("max_edited_files", String.valueOf(limits.maxEditedFiles())),
                        Map.entry(
                                "max_edited_bytes", limits.maxEditedBytes().toKilobytes() + " КБ"),
                        Map.entry("timeout", properties.timeout().toSeconds() + " с"),
                        Map.entry("max_timeout", properties.maxTimeout().toSeconds() + " с"));

        // The write appendix is added only when kb.edit/kb.create are actually bound, so the
        // handbook can never describe a method the sandbox does not have.
        String text = read(properties.guide());
        if (editEnabled) {
            text = text + "\n\n" + read(properties.editGuide());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return text;
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Не удалось прочитать руководство по скриптам: " + resource, e);
        }
    }
}
