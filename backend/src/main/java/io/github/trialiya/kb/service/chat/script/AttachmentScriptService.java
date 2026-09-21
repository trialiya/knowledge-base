package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Scripts that arrived as attachments — the second shelf {@code runSavedScript} reads from, next to
 * the project's manifest ({@code SavedScriptCatalog}).
 *
 * <p><b>Always read-only</b>, whatever {@code ScriptEditPolicy} says about the project. The
 * sandbox, the budgets and the {@code kb} API are the same as for any other run, so what an
 * attachment adds is not capability but <em>provenance</em>: a document's attachment was uploaded
 * by whoever could edit that document, and a model can be talked into running it by the document's
 * own text. Read-only leaves that scenario costing time and nothing else — the sandbox has no
 * network, and everything such a script can read the model could already read by itself.
 *
 * <p>Which is also why an attachment is not checked against the current chat: {@code
 * getAttachmentContent} already hands the model the text of any attachment by id, so refusing to
 * <em>run</em> what it may already <em>read</em> would guard nothing. What is checked is that the
 * file looks like a script at all — running a PDF as JavaScript is a syntax error with a confusing
 * message, and the model's next move should be "this is not a script", not "fix line 1".
 */
@Slf4j
@Service
public class AttachmentScriptService {

    /** How a script attachment is named in the {@code name} argument: {@code attachment:12}. */
    public static final String PREFIX = "attachment:";

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(".js", ".mjs", ".cjs");

    private static final Set<String> SCRIPT_TYPES =
            Set.of(
                    "text/javascript",
                    "application/javascript",
                    "application/x-javascript",
                    "text/x-javascript");

    private final AttachmentService attachments;
    private final ScriptProperties properties;

    public AttachmentScriptService(AttachmentService attachments, ScriptProperties properties) {
        this.attachments = attachments;
        this.properties = properties;
    }

    /** Whether attachments may be run at all — {@code kb.script.attachment-run}, under scripts. */
    public boolean available() {
        return properties.enabled() && properties.attachmentRun();
    }

    /** Whether this {@code name} argument addresses an attachment rather than a saved script. */
    public static boolean addresses(String name) {
        return name.startsWith(PREFIX);
    }

    /**
     * The attachment's text, ready to run, and what the result will say about it.
     *
     * @param name the tool's {@code name} argument, {@code attachment:<id>}
     * @throws IllegalArgumentException the reference is malformed, attachments are switched off,
     *     there is no such attachment, or it is not a script — each message is the tool's answer to
     *     the model, so it says which of those it was
     */
    public ScriptSource source(String name, Map<String, Object> args) {
        if (!available()) {
            throw new IllegalArgumentException(
                    "Running attachments is disabled here (kb.script.attachment-run=false). Read it"
                            + " with getAttachmentContent instead, or write the script yourself"
                            + " with runScript.");
        }
        long id = id(name);
        Attachment attachment;
        try {
            attachment = attachments.getById(id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("There is no attachment " + id + ".", e);
        }
        requireScriptFile(attachment);
        String text = attachments.getContent(id);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "Attachment " + id + " (" + attachment.fileName() + ") is empty.");
        }
        long size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > SavedScriptCatalog.MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Attachment "
                            + id
                            + " is too large to run ("
                            + size
                            + " bytes, the limit is "
                            + SavedScriptCatalog.MAX_BYTES
                            + ").");
        }
        log.info("Running attachment {} as a script: {}", id, attachment.fileName());
        ScriptRunSource report =
                new ScriptRunSource(
                        ScriptRunSource.Kind.ATTACHMENT,
                        PREFIX + id,
                        attachment.fileName(),
                        SavedScriptCatalog.sha(text),
                        args);
        return new ScriptSource(text, attachment.fileName(), report);
    }

    private static long id(String name) {
        String raw = name.substring(PREFIX.length()).strip();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "\""
                            + name
                            + "\" is not an attachment reference — it is spelled attachment:<id>,"
                            + " with the id getChatAttachments or getDocumentAttachments returned.",
                    e);
        }
    }

    /**
     * A file nobody meant as a script is refused by name and type rather than by the syntax error
     * it would produce: "this is a PDF" is a fact the model can act on, "unexpected token %" is
     * not.
     */
    private static void requireScriptFile(Attachment attachment) {
        String fileName = attachment.fileName().toLowerCase(Locale.ROOT);
        boolean named = SCRIPT_EXTENSIONS.stream().anyMatch(fileName::endsWith);
        boolean typed =
                SCRIPT_TYPES.contains(attachment.contentType().toLowerCase(Locale.ROOT).strip());
        if (!named && !typed) {
            throw new IllegalArgumentException(
                    "Attachment "
                            + attachment.id()
                            + " is not a script: \""
                            + attachment.fileName()
                            + "\" ("
                            + attachment.contentType()
                            + "). Only JavaScript attachments run — .js, .mjs or .cjs.");
        }
    }
}
