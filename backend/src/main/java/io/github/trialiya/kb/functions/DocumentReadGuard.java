package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireContent;
import static io.github.trialiya.kb.tools.ToolArgs.requireInt;
import static io.github.trialiya.kb.tools.ToolArgs.requireNonEmpty;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SectionRename;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.document.DocumentService;
import io.github.trialiya.kb.tools.EarlierToolResults;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The read-before-write rule of the document tools that write content they carry no quote of —
 * {@code updateDocument} and the section tools. ({@code editDocument} needs none: its exact-match
 * contract is the check.) A write passes when the model has seen what it overwrites, in either of
 * two ways:
 *
 * <ul>
 *   <li><b>A read in this response</b> — any successful matching read in the run's {@link
 *       ToolInvocationCollector}, whatever has happened to the document since. The section tools
 *       carry {@code expectedDescriptionVersion} for the concurrent-edit half of that.
 *   <li><b>A read earlier in the chat that is still true</b> — a matching read whose result is in
 *       the live prompt window ({@link EarlierToolResults}) and whose {@code descriptionVersion} is
 *       the document's current one. The version, not a timestamp: it grows with every change of the
 *       content ({@code DocumentService#update}) and with nothing else, so equal versions mean the
 *       text in front of the model is the text in the database — no clock involved. A read that was
 *       compacted away does not count: the model sees a retelling of it, not the text.
 * </ul>
 *
 * <p>A refusal names the read to make and the call's own {@code callRef}: {@code
 * DocumentFunction#retryDocumentWrite} replays the refused call from the collector once the read is
 * done, so the model does not have to send the content a second time.
 *
 * <p>Without a collector in the context (background jobs, tests) the rule is skipped altogether.
 */
final class DocumentReadGuard {

    static final String GET_DOCUMENT = "getDocument";
    static final String GET_DOCUMENT_OUTLINE = "getDocumentOutline";
    static final String GET_DOCUMENT_SECTION = "getDocumentSection";

    private static final Set<String> READ_TOOLS =
            Set.of(GET_DOCUMENT, GET_DOCUMENT_OUTLINE, GET_DOCUMENT_SECTION);

    /** Tools whose refusal {@code retryDocumentWrite} can replay — the ones behind this rule. */
    private static final Set<String> GUARDED_WRITES =
            Set.of(
                    "updateDocument",
                    "updateDocumentSection",
                    "insertDocumentSection",
                    "deleteDocumentSection",
                    "renameDocumentSections");

    /** The replay tool itself: a refused replay is followed back to the write it replays. */
    private static final String RETRY_TOOL = "retryDocumentWrite";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<SectionRename>> SECTION_RENAMES =
            new TypeReference<>() {};

    /**
     * Whether a read — by this tool, of this section ({@code null}: not a section read) — suffices.
     */
    @FunctionalInterface
    private interface Covers {
        boolean test(String tool, @Nullable String sectionPath);
    }

    private final DocumentService documentService;
    private final EarlierToolResults earlier;

    DocumentReadGuard(DocumentService documentService, EarlierToolResults earlier) {
        this.documentService = documentService;
        this.earlier = earlier;
    }

    /** A whole-content rewrite: only the whole document read counts. */
    void requireDocumentRead(ToolContext context, long documentId) {
        require(
                context,
                documentId,
                (tool, path) -> GET_DOCUMENT.equals(tool),
                "Документ id="
                        + documentId
                        + " НЕ обновлён: его текущее содержимое не прочитано — ни в этом ответе, ни"
                        + " в видимой истории чата. Сначала вызови getDocument(documentId="
                        + documentId
                        + "), чтобы увидеть текущее содержимое и не потерять данные.");
    }

    /** A section replace or delete: that section read, or the whole document. */
    void requireSectionRead(ToolContext context, long documentId, String sectionPath) {
        require(
                context,
                documentId,
                (tool, path) ->
                        GET_DOCUMENT.equals(tool)
                                || (GET_DOCUMENT_SECTION.equals(tool) && sectionPath.equals(path)),
                "Секция '"
                        + sectionPath
                        + "' документа id="
                        + documentId
                        + " НЕ изменена: её текущее содержимое не прочитано — ни в этом ответе, ни"
                        + " в видимой истории чата. Сначала вызови getDocumentSection(documentId="
                        + documentId
                        + ", sectionPath=\""
                        + sectionPath
                        + "\") или getDocument(documentId="
                        + documentId
                        + ").");
    }

    /**
     * An insert or a rename: the outline, the whole document, or — when {@code anchorSectionPath}
     * is given — the anchor section.
     */
    void requireStructureRead(
            ToolContext context, long documentId, @Nullable String anchorSectionPath) {
        require(
                context,
                documentId,
                (tool, path) ->
                        GET_DOCUMENT.equals(tool)
                                || GET_DOCUMENT_OUTLINE.equals(tool)
                                || (anchorSectionPath != null
                                        && GET_DOCUMENT_SECTION.equals(tool)
                                        && anchorSectionPath.equals(path)),
                "Документ id="
                        + documentId
                        + " НЕ изменён: его текущая структура не прочитана — ни в этом ответе, ни"
                        + " в видимой истории чата. Сначала вызови getDocumentOutline(documentId="
                        + documentId
                        + ") или getDocument(documentId="
                        + documentId
                        + ").");
    }

    private void require(ToolContext context, long documentId, Covers covers, String refusal) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null
                || readInThisResponse(collector, documentId, covers)
                || readEarlierAndUnchanged(context, documentId, covers)) {
            return;
        }
        throw new IllegalStateException(refusal + retryHint());
    }

    private static boolean readInThisResponse(
            ToolInvocationCollector collector, long documentId, Covers covers) {
        final String id = String.valueOf(documentId);
        return collector.snapshot().stream()
                .filter(inv -> ToolInvocationStatus.OK == inv.status())
                .filter(inv -> id.equals(String.valueOf(inv.arguments().get("documentId"))))
                .anyMatch(inv -> covers.test(inv.name(), sectionPathArgument(inv)));
    }

    private static @Nullable String sectionPathArgument(ToolInvocation invocation) {
        final Object path = invocation.arguments().get("sectionPath");
        return path == null ? null : path.toString();
    }

    /**
     * Reads the document's current version only when the window holds a matching read at all — the
     * common refusal (nothing read anywhere) costs the history query and nothing more.
     */
    private boolean readEarlierAndUnchanged(ToolContext context, long documentId, Covers covers) {
        if (!(context.getContext().get(ChatMemory.CONVERSATION_ID) instanceof String chat)) {
            return false;
        }
        Integer current = null;
        for (ToolData.Response response : earlier.of(chat)) {
            final JsonNode read = documentRead(response, documentId);
            if (read == null
                    || !covers.test(response.name(), textOrNull(read.get("path")))
                    || !read.path("descriptionVersion").canConvertToInt()) {
                continue;
            }
            if (current == null) {
                final DocumentNode node = documentService.getById(documentId);
                if (node == null) {
                    return false;
                }
                current = node.descriptionVersion();
            }
            if (read.path("descriptionVersion").intValue() == current) {
                return true;
            }
        }
        return false;
    }

    /**
     * The result of a read tool about this document, as the model received it. The result is what
     * carries the version the model saw; a refusal or an error is plain text rather than a JSON
     * object and falls out here, which is also how a failed read is told from a successful one.
     */
    private static @Nullable JsonNode documentRead(ToolData.Response response, long documentId) {
        if (!READ_TOOLS.contains(response.name()) || response.responseData() == null) {
            return null;
        }
        final JsonNode node = readTree(response.responseData());
        return node != null
                        && node.path("id").canConvertToLong()
                        && node.path("id").longValue() == documentId
                ? node
                : null;
    }

    /** {@code null} for anything that is not JSON — a refusal, an error, a plain-text result. */
    private static @Nullable JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static @Nullable String textOrNull(@Nullable JsonNode node) {
        return node == null || !node.isTextual() ? null : node.textValue();
    }

    /**
     * Where the refused call can be picked up again. The call index is only ever missing outside a
     * recorded call — and there the rule itself is skipped, so the fallback is for form's sake.
     */
    private static String retryHint() {
        final OptionalInt ref = RecordingToolCallback.currentCallIndex();
        if (ref.isEmpty()) {
            return " Затем повтори вызов.";
        }
        return " Затем, если после чтения этот вызов по-прежнему верен, вызови"
                + " retryDocumentWrite(callRef="
                + ref.getAsInt()
                + ") — он выполнит этот же вызов с теми же аргументами, пересылать текст заново не"
                + " нужно. Если текст надо поменять — вызови инструмент заново.";
    }

    /**
     * A write this rule refused, as {@code retryDocumentWrite} replays it: the tool and the
     * arguments the model sent, read back whole from the collector ({@code argumentsRaw} — the
     * recorded argument map is truncated for the UI). Missing arguments are answered the way the
     * tool itself would answer them, through {@code ToolArgs}.
     */
    record RefusedWrite(String tool, JsonNode args) {

        <T> @Nullable T argument(String name, Class<T> type) {
            final JsonNode value = args.get(name);
            return value == null || value.isNull() ? null : MAPPER.convertValue(value, type);
        }

        String text(String name) {
            return requireText(argument(name, String.class), name);
        }

        String content(String name) {
            return requireContent(argument(name, String.class), name);
        }

        int expectedVersion() {
            return requireInt(
                    argument("expectedDescriptionVersion", Integer.class),
                    "expectedDescriptionVersion");
        }

        List<SectionRename> renames() {
            final JsonNode value = args.get("renames");
            return requireNonEmpty(
                    value == null || value.isNull()
                            ? null
                            : MAPPER.convertValue(value, SECTION_RENAMES),
                    "renames");
        }
    }

    /**
     * The call of this response that {@code callRef} names, provided it is a refused write this
     * rule guards — anything else is not a replay but a new call, and the model is told to make it.
     *
     * <p>A refused replay counts as the write it replays: its own refusal names its own call index
     * (the guard cannot tell a replay from a first attempt), so that index is followed back to the
     * original. Each hop goes to an earlier call, which is what ends the walk.
     */
    static RefusedWrite refusedWrite(ToolContext context, int callRef) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        final List<ToolInvocation> calls =
                collector == null ? List.of() : collector.completedSnapshot();
        int ref = callRef;
        while (true) {
            final ToolInvocation refused = refusedCall(calls, ref, callRef);
            final JsonNode args = argumentsOf(refused, callRef);
            if (!RETRY_TOOL.equals(refused.name())) {
                return new RefusedWrite(refused.name(), args);
            }
            final int next = args.path("callRef").asInt(-1);
            if (next < 0 || next >= ref) {
                throw notARefusedWrite(callRef);
            }
            ref = next;
        }
    }

    private static ToolInvocation refusedCall(List<ToolInvocation> calls, int ref, int asked) {
        return calls.stream()
                .filter(inv -> inv.callIndex() == ref)
                .filter(inv -> ToolInvocationStatus.ERROR == inv.status())
                .filter(inv -> GUARDED_WRITES.contains(inv.name()) || RETRY_TOOL.equals(inv.name()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> notARefusedWrite(asked));
    }

    private static JsonNode argumentsOf(ToolInvocation refused, int asked) {
        final JsonNode args = readTree(refused.argumentsRaw());
        if (args != null && args.isObject()) {
            return args;
        }
        throw new IllegalStateException(
                "The arguments of callRef="
                        + asked
                        + " cannot be read back. Call "
                        + refused.name()
                        + " again with its arguments.");
    }

    private static IllegalArgumentException notARefusedWrite(int callRef) {
        return new IllegalArgumentException(
                "callRef="
                        + callRef
                        + " is not a refused document write of this response. Call the write tool"
                        + " again with its arguments.");
    }
}
