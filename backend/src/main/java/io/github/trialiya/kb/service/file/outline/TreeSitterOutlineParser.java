package io.github.trialiya.kb.service.file.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

/**
 * Outline parser backed by tree-sitter ({@code io.github.bonede:tree-sitter-ng}).
 *
 * <p><b>What is listed.</b> Declarations, not code: the walk does not enter the body of a symbol it
 * has reported as a function, method or constructor, so a nested helper, a local class, the methods
 * of an anonymous class or a callback's object literal never surface as structure of the file.
 * Python's {@code def} directly in a class body is a {@code method}, like the regex fallback
 * reports it.
 *
 * <p><b>Signature building</b> uses tree-sitter field accessors ({@code getChildByFieldName}) and
 * the {@link #text} helper, which decodes the node's byte span as UTF-8 — tree-sitter offsets are
 * bytes, so multibyte content (Cyrillic, CJK, …) must be decoded, not cast. Parameter annotations
 * ({@code @ToolParam}, {@code @Nullable}, …) are stripped from the signature; the AI only needs
 * types and names.
 *
 * <p><b>Native isolation.</b> If loading fails (missing native lib), {@link #available()} answers
 * false and {@link #supports} returns false for every language, so the caller falls back to {@link
 * RegexOutlineParser}. Neither construction nor a probe ever throws.
 */
@Slf4j
public final class TreeSitterOutlineParser implements CodeOutlineParser {

    private static final Set<String> LANGUAGES = Set.of("java", "javascript", "typescript", "python");

    /** Kinds whose node is a body of code, not a container of declarations: never walked into. */
    private static final Set<String> CODE_KINDS = Set.of("function", "method", "constructor");

    private final Map<String, TSLanguage> languages = new ConcurrentHashMap<>();

    /** Null until the native layer has been probed; see {@link #available()}. */
    @Nullable
    private volatile Boolean available;

    private final ReentrantLock probeLock = new ReentrantLock();

    @Override
    public String name() {
        return "tree-sitter";
    }

    @Override
    public boolean supports(@Nullable String language) {
        // The language check comes first so that the native layer is loaded only for a file this
        // parser could actually answer about — a repository of none of these four never loads it.
        return language != null && LANGUAGES.contains(language) && available();
    }

    /**
     * Whether the native layer is there, loaded once on the first file that needs it. Loading costs
     * about a fifth of a second, and an outline is asked for long after startup — or never.
     */
    private boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        probeLock.lock();
        try {
            Boolean probed = available;
            if (probed == null) {
                probed = probe();
                available = probed;
            }
            return probed;
        } finally {
            probeLock.unlock();
        }
    }

    private static boolean probe() {
        try {
            new TreeSitterJava();
            return true;
        } catch (Throwable t) {
            log.warn("tree-sitter native layer unavailable, falling back to regex outline: {}", t.toString());
            return false;
        }
    }

    @Nullable
    private TSLanguage languageFor(String language) {
        try {
            return languages.computeIfAbsent(language, l -> switch (l) {
                case "java" -> new TreeSitterJava();
                case "javascript" -> new TreeSitterJavascript();
                case "typescript" -> new TreeSitterTypescript();
                case "python" -> new TreeSitterPython();
                default -> throw new IllegalArgumentException(l);
            });
        } catch (Throwable t) {
            log.warn("Failed to load tree-sitter grammar for {}: {}", language, t.toString());
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code null} when the grammar or the native parse failed — an empty list means the file
     * was read and declares nothing, which is an answer, not a reason to try the regex fallback.
     */
    // TSLanguage is a process-lifetime grammar handle cached by languageFor; closing it would
    // invalidate the cache for every later parse.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    @Nullable
    public List<GitSymbol> parse(String language, String source) {
        if (!supports(language)) return null;
        if (source.isEmpty()) return List.of();
        TSLanguage lang = languageFor(language);
        if (lang == null) return null;
        // All three handles wrap native tree-sitter memory that the JVM does not reclaim on its
        // own.
        try (TSParser parser = new TSParser()) {
            parser.setLanguage(lang);
            try (TSTree tree = parser.parseString(null, source);
                    TSTreeCursor cursor = new TSTreeCursor(tree.getRootNode())) {
                byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
                List<GitSymbol> out = new ArrayList<>();
                walk(cursor, bytes, language, out);
                return out;
            }
        } catch (Throwable t) {
            log.warn("tree-sitter parse failed for {} ({} bytes): {}", language, source.length(), t.toString());
            return null;
        }
    }

    // ── Tree walk ────────────────────────────────────────────────────────────

    /**
     * Pre-order walk with a cursor rather than recursion: a left-deep expression ({@code a + b + …}
     * over thousands of terms, common in generated code) nests as deep as it is long and would
     * overflow the stack.
     */
    private static void walk(TSTreeCursor cursor, byte[] src, String language, List<GitSymbol> out) {
        boolean enter = visit(cursor.currentNode(), src, language, out);
        while (true) {
            if (enter && cursor.gotoFirstChild()) {
                enter = visit(cursor.currentNode(), src, language, out);
                continue;
            }
            while (!cursor.gotoNextSibling()) {
                if (!cursor.gotoParent()) return;
            }
            enter = visit(cursor.currentNode(), src, language, out);
        }
    }

    /** Records the node if it is a symbol; answers whether its children are worth walking. */
    private static boolean visit(TSNode node, byte[] src, String language, List<GitSymbol> out) {
        String type = node.getType();
        // A data literal holds no declarations, and a generated one can hold most of the file.
        if (type.equals("array") || type.equals("string") || type.equals("template_string")) {
            return false;
        }
        String kind = symbolKind(language, node, type);
        if (kind == null) return true;
        TSNode nameNode = nameNode(node);
        if (nameNode == null) return true;
        String name = text(nameNode, src);
        int startLine = nameNode.getStartPoint().getRow() + 1;
        int endLine = node.getEndPoint().getRow() + 1;
        String signature = buildSignature(node, src, language, kind, name);
        out.add(new GitSymbol(kind, name, signature, startLine, endLine));
        return !CODE_KINDS.contains(kind);
    }

    // ── Symbol kinds ─────────────────────────────────────────────────────────

    /** Maps a grammar node to a symbol kind, or null to skip. */
    @Nullable
    private static String symbolKind(String language, TSNode node, String type) {
        return switch (language) {
            case "java" -> javaKind(type);
            case "javascript", "typescript" -> scriptKind(node, type);
            case "python" -> pythonKind(node, type);
            default -> null;
        };
    }

    @Nullable
    private static String javaKind(String type) {
        return switch (type) {
            case "class_declaration" -> "class";
            case "interface_declaration" -> "interface";
            case "annotation_type_declaration" -> "annotation";
            case "enum_declaration" -> "enum";
            case "record_declaration" -> "record";
            case "method_declaration" -> "method";
            case "constructor_declaration", "compact_constructor_declaration" -> "constructor";
            default -> null;
        };
    }

    /**
     * Besides declarations, a JS/TS function is often only named by what holds it: {@code const Foo
     * = () => …} (React components and most modern modules), a class field holding an arrow
     * function, an entry of a module-level object of functions ({@code const api = { load: () => …
     * }}). Only module-level holders count: an object literal passed to a call is an argument, not
     * the file's structure.
     */
    @Nullable
    private static String scriptKind(TSNode node, String type) {
        return switch (type) {
            case "class_declaration", "abstract_class_declaration", "class" -> "class";
            case "function_declaration", "generator_function_declaration" -> "function";
            case "method_definition" ->
                !isType(node.getParent(), "object") || isModuleObject(node.getParent()) ? "method" : null;
            case "abstract_method_signature" -> "method";
            case "interface_declaration" -> "interface";
            case "enum_declaration" -> "enum";
            case "type_alias_declaration" -> "type";
            case "variable_declarator" -> holdsFunction(node) && isModuleLevel(node) ? "function" : null;
            case "field_definition", "public_field_definition" -> holdsFunction(node) ? "method" : null;
            case "pair" -> holdsFunction(node) && isModuleObject(node.getParent()) ? "method" : null;
            default -> null;
        };
    }

    @Nullable
    private static String pythonKind(TSNode node, String type) {
        return switch (type) {
            case "class_definition" -> "class";
            case "function_definition" -> isInClassBody(node) ? "method" : "function";
            default -> null;
        };
    }

    /** A Python {@code def} straight in a class body, decorated or not. */
    private static boolean isInClassBody(TSNode def) {
        TSNode parent = def.getParent();
        if (isType(parent, "decorated_definition")) parent = parent.getParent();
        return isType(parent, "block") && isType(parent.getParent(), "class_definition");
    }

    private static boolean holdsFunction(TSNode node) {
        TSNode value = node.getChildByFieldName("value");
        if (value == null || value.isNull()) return false;
        return switch (value.getType()) {
            case "arrow_function",
                    "function_expression",
                    "function",
                    "generator_function",
                    "generator_function_expression" -> true;
            default -> false;
        };
    }

    /** A declarator in a {@code const}/{@code let}/{@code var} at the top of the module. */
    private static boolean isModuleLevel(TSNode declarator) {
        TSNode declaration = declarator.getParent();
        if (declaration == null || declaration.isNull()) return false;
        TSNode owner = declaration.getParent();
        if (isType(owner, "export_statement")) owner = owner.getParent();
        return isType(owner, "program");
    }

    /**
     * An object literal the module itself holds: the value of a module-level {@code const} or the
     * module's {@code export default}.
     */
    private static boolean isModuleObject(TSNode object) {
        if (!isType(object, "object")) return false;
        TSNode holder = object.getParent();
        if (isType(holder, "variable_declarator")) return isModuleLevel(holder);
        return isType(holder, "export_statement") && isType(holder.getParent(), "program");
    }

    private static boolean isType(@Nullable TSNode node, String type) {
        return node != null && !node.isNull() && node.getType().equals(type);
    }

    // ── Signature building ───────────────────────────────────────────────────

    /**
     * Assembles a clean, single-line signature from tree-sitter nodes. All text extraction goes
     * through {@link #text} which decodes UTF-8 properly — no byte-cast corruption. Parameter
     * annotations are removed by {@link #cleanParams}.
     *
     * <p>Robust to grammar variations: {@code modifiers}, return {@code type} and the parameter
     * list may be exposed either as named fields or as plain typed child nodes depending on the
     * grammar build, so we look them up by field name first and fall back to child node type.
     */
    private static String buildSignature(TSNode node, byte[] src, String language, String kind, String name) {

        if (language.equals("java")) {
            String mods = cleanModifiers(fieldOrTypeText(node, "modifiers", "modifiers", src));
            if (kind.equals("method") || kind.equals("constructor")) {
                return cap(javaCallableSignature(node, src, kind, name, mods));
            }
            // class / interface / annotation / enum / record
            StringBuilder sb = new StringBuilder();
            if (!mods.isEmpty()) sb.append(mods).append(' ');
            sb.append(kind.equals("annotation") ? "@interface" : kind)
                    .append(' ')
                    .append(name);
            return cap(sb.toString());
        }

        // JS/TS/Python: everything up to the body — the head may span lines (a component's
        // destructured props, a TS return type), and its first line alone would end mid-list.
        String head = headBeforeBody(node, src);
        return cap(head != null ? head : firstNonAnnotationLine(node, src));
    }

    /** {@code mods <T> Ret name(params) throws X}; a record's compact constructor has no list. */
    private static String javaCallableSignature(TSNode node, byte[] src, String kind, String name, String mods) {
        String typeParams = fieldOrTypeText(node, "type_parameters", "type_parameters", src);
        // Constructors have no return type; methods do (field "type").
        String ret = kind.equals("constructor") ? "" : fieldText(node, "type", src);
        TSNode paramsNode = childByFieldOrType(node, "parameters", "formal_parameters");
        String throwsClause = fieldOrTypeText(node, "throws", "throws", src);

        StringBuilder sb = new StringBuilder();
        if (!mods.isEmpty()) sb.append(mods).append(' ');
        if (!typeParams.isEmpty()) sb.append(typeParams).append(' ');
        if (!ret.isEmpty()) sb.append(ret).append(' ');
        sb.append(name);
        if (paramsNode != null || !node.getType().equals("compact_constructor_declaration")) {
            String params = cleanParams(paramsNode, src);
            sb.append('(');
            if (params != null) sb.append(params);
            sb.append(')');
        }
        if (!throwsClause.isEmpty()) sb.append(' ').append(throwsClause);
        return sb.toString();
    }

    /**
     * Source from where the symbol is declared up to its body, whitespace-collapsed, without the
     * opening brace or colon — or null when the node has no body to stop at. A function held in a
     * value starts at its declaration ({@code export const Foo = ({ a, b }) =>}), so the reader
     * sees how it is bound, not only its parameter list.
     */
    @Nullable
    private static String headBeforeBody(TSNode node, byte[] src) {
        boolean held = holdsFunction(node);
        TSNode body = (held ? node.getChildByFieldName("value") : node).getChildByFieldName("body");
        if (body == null || body.isNull()) return null;
        TSNode start = node;
        if (held && node.getType().equals("variable_declarator")) {
            start = node.getParent();
            TSNode exported = start.getParent();
            if (isType(exported, "export_statement")) start = exported;
        }
        int from = start.getStartByte();
        int to = Math.min(body.getStartByte(), src.length);
        if (from < 0 || from >= to) return null;
        String head = new String(src, from, to - from, StandardCharsets.UTF_8).strip();
        if (head.endsWith("{") || head.endsWith(":")) head = head.substring(0, head.length() - 1);
        return head.strip();
    }

    /**
     * Strips annotations from a {@code modifiers} text fragment, keeping only Java keyword
     * modifiers (public, private, protected, static, final, abstract, …). This removes any
     * annotation — including multi-byte annotation arguments — that the grammar bundles into the
     * modifiers node, so they never reach the signature.
     */
    private static String cleanModifiers(String mods) {
        if (mods.isEmpty()) return "";
        // Remove annotation expressions first (with or without arguments), so a keyword-looking
        // word inside an annotation string (e.g. description = "use public API") is not kept.
        String noAnnotations =
                mods.replaceAll("@\\w+(?:\\.\\w+)*\\s*\\([^)]*\\)", " ").replaceAll("@\\w+(?:\\.\\w+)*", " ");
        StringBuilder sb = new StringBuilder();
        for (String tokenWord : noAnnotations.split("\\s+")) {
            switch (tokenWord) {
                case "public",
                        "private",
                        "protected",
                        "static",
                        "final",
                        "abstract",
                        "synchronized",
                        "native",
                        "transient",
                        "volatile",
                        "strictfp",
                        "default",
                        "sealed",
                        "non-sealed" -> {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tokenWord);
                }
                default -> {
                    // not a keyword modifier → skip
                }
            }
        }
        return sb.toString();
    }

    /**
     * Builds a parameter list from a parameters node with annotations stripped. Each {@code
     * formal_parameter} child contributes "{type} {name}"; annotation nodes are skipped entirely so
     * multi-byte annotation values never leak into the signature.
     */
    @Nullable
    private static String cleanParams(@Nullable TSNode params, byte[] src) {
        if (params == null || params.isNull()) return null;
        List<String> parts = new ArrayList<>();
        int n = params.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode child = params.getChild(i);
            if (child == null || child.isNull()) continue;
            String t = child.getType();
            if (!t.contains("parameter")) continue; // skip ',' '(' ')'

            String pType = fieldOrTypeText(child, "type", "type_identifier", src);
            String pName = fieldText(child, "name", src);
            if (!pType.isEmpty() || !pName.isEmpty()) {
                parts.add((pType + " " + pName).strip());
            } else {
                // Fallback for varargs/receiver/spread params: strip annotations from raw text.
                String raw = text(child, src)
                        .replaceAll("@\\w+\\s*\\([^)]*\\)\\s*", "")
                        .replaceAll("@\\w+\\s*", "")
                        .replaceAll("\\s+", " ")
                        .strip();
                if (!raw.isEmpty()) parts.add(raw);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // ── Node helpers ─────────────────────────────────────────────────────────

    /**
     * The node naming the symbol: the {@code name} field, or the first identifier child — a JS
     * class field names itself by {@code property}, an object entry by {@code key}.
     */
    @Nullable
    private static TSNode nameNode(TSNode node) {
        TSNode byField = node.getChildByFieldName("name");
        if (byField != null && !byField.isNull()) return byField;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = node.getChild(i);
            if (c != null && !c.isNull() && c.getType().contains("identifier")) return c;
        }
        return null;
    }

    /** Returns the text of the named field, whitespace-collapsed, or empty string. */
    private static String fieldText(TSNode node, String field, byte[] src) {
        TSNode child = node.getChildByFieldName(field);
        if (child == null || child.isNull()) return "";
        return text(child, src).replaceAll("\\s+", " ").strip();
    }

    /**
     * Looks up a child first by field name, then (if absent) by the first direct child whose node
     * type equals {@code typeName}. Grammars differ on whether {@code modifiers}/{@code
     * formal_parameters} are exposed as fields or plain child nodes; this handles both.
     */
    @Nullable
    private static TSNode childByFieldOrType(TSNode node, String field, String typeName) {
        TSNode byField = node.getChildByFieldName(field);
        if (byField != null && !byField.isNull()) return byField;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = node.getChild(i);
            if (c != null && !c.isNull() && c.getType().equals(typeName)) return c;
        }
        return null;
    }

    /** {@link #childByFieldOrType} plus text extraction, whitespace-collapsed. */
    private static String fieldOrTypeText(TSNode node, String field, String typeName, byte[] src) {
        TSNode child = childByFieldOrType(node, field, typeName);
        if (child == null || child.isNull()) return "";
        return text(child, src).replaceAll("\\s+", " ").strip();
    }

    /**
     * First non-empty, non-annotation line of a node's text, without a trailing {@code {} or
     * {@code ;} — the signature of a bodiless declaration ({@code type T = …}, an abstract method).
     */
    private static String firstNonAnnotationLine(TSNode node, byte[] src) {
        for (String line : text(node, src).split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("@")) {
                return s.endsWith("{") || s.endsWith(";")
                        ? s.substring(0, s.length() - 1).strip()
                        : s;
            }
        }
        return text(node, src).strip();
    }

    /** Collapses whitespace and caps at 200 chars, never splitting a surrogate pair. */
    private static String cap(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        if (flat.length() <= 200) return flat;
        int cut = Character.isHighSurrogate(flat.charAt(199)) ? 199 : 200;
        return flat.substring(0, cut) + "…";
    }

    /** Decodes a node's byte span as UTF-8. Never misinterprets multibyte sequences. */
    private static String text(TSNode node, byte[] src) {
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), src.length);
        if (start < 0 || start >= end) return "";
        return new String(src, start, end - start, StandardCharsets.UTF_8);
    }
}
