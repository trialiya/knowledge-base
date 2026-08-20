package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.unit.DataSize;

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
 * <p>The handbook comes in two halves, and only the first is unconditional. The reference half —
 * the {@code kb} API, the budgets, the error kinds — is what no model can guess, so it ships
 * whenever the tool does. The tutorial half — when to prefer a script, how to structure one, worked
 * examples — is the part a strong model already knows; a run whose model is flagged {@code weak:
 * false} ({@code ChatModelProperties.ModelOption}) gets the reference half only, which keeps its
 * prompt short. The same split applies to the write appendix, so a strong model losing the tutorial
 * cannot lose the edit rules along with it.
 *
 * <p>The four combinations (tutorial on/off × writes on/off) are rendered once at startup rather
 * than per request: nothing in a rendering depends on the request, only on which of the two
 * booleans applies to it, so there is no reason to redo the string work per chat turn.
 *
 * <p>The budget numbers in the handbook are substituted from {@code kb.script.limits} rather than
 * written into the markdown, so lowering a limit cannot silently leave the model working from a
 * stale figure.
 */
@Service
public class ScriptGuideService {

    private final ScriptEditPolicy editPolicy;
    private final String instructionsForWeakModel;
    private final String instructionsForStrongModel;
    private final String readOnlyInstructionsForWeakModel;
    private final String readOnlyInstructionsForStrongModel;

    public ScriptGuideService(ScriptProperties properties, ScriptEditPolicy editPolicy) {
        this.editPolicy = editPolicy;
        this.instructionsForWeakModel = properties.enabled() ? render(properties, true, true) : "";
        this.instructionsForStrongModel =
                properties.enabled() ? render(properties, false, true) : "";
        this.readOnlyInstructionsForWeakModel =
                properties.enabled() ? render(properties, true, false) : "";
        this.readOnlyInstructionsForStrongModel =
                properties.enabled() ? render(properties, false, false) : "";
    }

    /**
     * The handbook for a run against the given model, or {@code ""} when {@code
     * kb.script.enabled=false}. Never null: the placeholder must always receive a value or the
     * prompt template fails to render.
     *
     * <p>The write appendix follows the run's own project, not the deployment: {@code edit-enabled}
     * is per project, and the handbook has to describe the {@code kb} object {@code ScriptRunner}
     * will actually bind for this run.
     *
     * @param weak {@code ChatModelProperties.ModelOption#weak} of the model the run actually uses —
     *     picks the tutorial-included or reference-only rendering
     * @param projectId the project the run works against; {@code null} — the default one
     */
    public String instructions(boolean weak, @Nullable String projectId) {
        if (!editPolicy.enabled(projectId)) {
            return readOnlyInstructions(weak);
        }
        return weak ? instructionsForWeakModel : instructionsForStrongModel;
    }

    /** The handbook for the default project — for a caller that has no project in hand. */
    public String instructions(boolean weak) {
        return instructions(weak, null);
    }

    /**
     * The handbook without the write appendix, whatever the edit policy says — for the search
     * sub-agent, which is read-only by construction and must stay that way even in a deployment
     * where the main chat may edit files.
     *
     * @param weak {@code ChatModelProperties.ModelOption#weak} of the sub-agent's own model ({@code
     *     kb.search.subagent.model-id}), which can differ from the main chat's
     */
    public String readOnlyInstructions(boolean weak) {
        return weak ? readOnlyInstructionsForWeakModel : readOnlyInstructionsForStrongModel;
    }

    private static String render(
            ScriptProperties properties, boolean extended, boolean editEnabled) {
        ScriptProperties.Limits limits = properties.limits();
        Map<String, String> values =
                Map.ofEntries(
                        Map.entry("max_files_read", String.valueOf(limits.maxFilesRead())),
                        Map.entry("max_bytes_read", humanBytes(limits.maxBytesRead())),
                        Map.entry("max_calls", String.valueOf(limits.maxCalls())),
                        Map.entry("max_log_chars", String.valueOf(limits.maxLogChars())),
                        Map.entry("max_result_chars", String.valueOf(limits.maxResultChars())),
                        Map.entry("max_edited_files", String.valueOf(limits.maxEditedFiles())),
                        Map.entry("max_edited_bytes", humanBytes(limits.maxEditedBytes())),
                        Map.entry("timeout", properties.timeout().toSeconds() + " с"),
                        Map.entry("max_timeout", properties.maxTimeout().toSeconds() + " с"));

        // Two independent gates. The write appendices are added only when kb.edit/kb.create are
        // actually bound, so the handbook can never describe a method the sandbox does not have;
        // the extended halves are added only for a run whose model is flagged weak.
        StringBuilder handbook = new StringBuilder(read(properties.guide()));
        if (extended) {
            append(handbook, properties.extendedGuide());
        }
        if (editEnabled) {
            append(handbook, properties.editGuide());
            if (extended) {
                append(handbook, properties.extendedEditGuide());
            }
        }
        String text = handbook.toString();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return text;
    }

    private static void append(StringBuilder handbook, Resource section) {
        handbook.append("\n\n").append(read(section));
    }

    /**
     * The largest unit that still spells the size exactly. Fixing the unit per limit would make the
     * handbook lie as soon as a deployment retunes one: {@code max-bytes-read: 512KB} rendered in
     * megabytes reads as "0 МБ", which tells the model its budget is nothing.
     */
    private static String humanBytes(DataSize size) {
        long bytes = size.toBytes();
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return size.toMegabytes() + " МБ";
        }
        if (bytes >= 1024 && bytes % 1024 == 0) {
            return size.toKilobytes() + " КБ";
        }
        return bytes + " Б";
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
