package io.github.trialiya.kb.service.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

/**
 * Outline parser backed by tree-sitter ({@code io.github.bonede:tree-sitter-ng}).
 *
 * <p><b>Signature building</b> uses tree-sitter field accessors ({@code getChildByFieldName}) and
 * the {@link #text} helper which decodes bytes as UTF-8. This guarantees correct handling of
 * multibyte content (Cyrillic, CJK, …) — the old byte-by-byte approach cast every byte to {@code
 * (char)} which garbled non-ASCII. Parameter annotations ({@code @ToolParam}, {@code @Nullable}, …)
 * are stripped from the signature; the AI only needs types and names.
 *
 * <p><b>Native isolation.</b> If loading fails (missing native lib), {@link #available} is false
 * and {@link #supports} returns false for every language, so the caller falls back to {@link
 * RegexOutlineParser}. Construction never throws.
 */
@Slf4j
public final class TreeSitterOutlineParser implements CodeOutlineParser {

    private static final Set<String> LANGUAGES =
            Set.of("java", "javascript", "typescript", "python");

    private final Map<String, TSLanguage> languages = new ConcurrentHashMap<>();
    private final boolean available;

    public TreeSitterOutlineParser() {
        boolean ok;
        try {
            new TreeSitterJava();
            ok = true;
        } catch (Throwable t) {
            log.warn(
                    "tree-sitter native layer unavailable, falling back to regex outline: {}",
                    t.toString());
            ok = false;
        }
        this.available = ok;
    }

    @Override
    public String name() {
        return "tree-sitter";
    }

    @Override
    public boolean supports(@Nullable String language) {
        return available && language != null && LANGUAGES.contains(language);
    }

    @Nullable
    private TSLanguage languageFor(String language) {
        try {
            return languages.computeIfAbsent(
                    language,
                    l ->
                            switch (l) {
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

    @Override
    public List<GitSymbol> parse(String language, String source) {
        if (!supports(language) || source == null || source.isEmpty()) return List.of();
        TSLanguage lang = languageFor(language);
        if (lang == null) return List.of();
        try {
            TSParser parser = new TSParser();
            parser.setLanguage(lang);
            TSTree tree = parser.parseString(null, source);
            byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<GitSymbol> out = new ArrayList<>();
            walk(tree.getRootNode(), bytes, language, out);
            return out;
        } catch (Throwable t) {
            log.warn(
                    "tree-sitter parse failed for {} ({} bytes): {}",
                    language,
                    source.length(),
                    t.toString());
            return List.of();
        }
    }

    // ── Tree walk ────────────────────────────────────────────────────────────

    private void walk(TSNode node, byte[] src, String language, List<GitSymbol> out) {
        if (node == null || node.isNull()) return;
        String kind = symbolKind(language, node.getType());
        if (kind != null) {
            String name = nameOf(node, src);
            if (name != null) {
                TSNode nameNode = node.getChildByFieldName("name");
                int nameRow =
                        (nameNode != null && !nameNode.isNull())
                                ? nameNode.getStartPoint().getRow()
                                : node.getStartPoint().getRow();
                int startLine = nameRow + 1;
                int endLine = node.getEndPoint().getRow() + 1;
                String signature = buildSignature(node, src, language, kind, name);
                out.add(new GitSymbol(kind, name, signature, startLine, endLine));
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) walk(node.getChild(i), src, language, out);
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
    private static String buildSignature(
            TSNode node, byte[] src, String language, String kind, String name) {

        if (language.equals("java")) {
            if (kind.equals("method") || kind.equals("constructor")) {
                String mods = cleanModifiers(fieldOrTypeText(node, "modifiers", "modifiers", src));
                // Constructors have no return type; methods do (field "type").
                String ret = kind.equals("constructor") ? "" : returnTypeText(node, src);
                TSNode paramsNode = childByFieldOrType(node, "parameters", "formal_parameters");
                String params = cleanParams(paramsNode, src);

                StringBuilder sb = new StringBuilder();
                if (!mods.isEmpty()) sb.append(mods).append(' ');
                if (!ret.isEmpty()) sb.append(ret).append(' ');
                sb.append(name).append('(');
                if (params != null) sb.append(params);
                sb.append(')');
                return cap(sb.toString());
            }
            // class / interface / enum / record
            String mods = cleanModifiers(fieldOrTypeText(node, "modifiers", "modifiers", src));
            StringBuilder sb = new StringBuilder();
            if (!mods.isEmpty()) sb.append(mods).append(' ');
            sb.append(kind).append(' ').append(name);
            return cap(sb.toString());
        }

        // JS/TS/Python/SQL: no annotations on params, so first non-annotation line is enough.
        return cap(firstNonAnnotationLine(node, src));
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
                mods.replaceAll("@\\w+(?:\\.\\w+)*\\s*\\([^)]*\\)", " ")
                        .replaceAll("@\\w+(?:\\.\\w+)*", " ");
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
     * Returns the method's return type. The Java grammar exposes it as the {@code type} field, but
     * we guard against it not being a field by taking the first child node whose type looks like a
     * type and sits before the {@code name} node.
     */
    private static String returnTypeText(TSNode node, byte[] src) {
        TSNode t = node.getChildByFieldName("type");
        if (t != null && !t.isNull()) {
            return text(t, src).replaceAll("\\s+", " ").strip();
        }
        return "";
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
                String raw =
                        text(child, src)
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

    /** Maps a grammar node type to a symbol kind, or null to skip. */
    @Nullable
    private static String symbolKind(String language, String type) {
        return switch (language) {
            case "java" ->
                    switch (type) {
                        case "class_declaration" -> "class";
                        case "interface_declaration" -> "interface";
                        case "enum_declaration" -> "enum";
                        case "record_declaration" -> "record";
                        case "method_declaration" -> "method";
                        case "constructor_declaration" -> "constructor";
                        default -> null;
                    };
            case "javascript", "typescript" ->
                    switch (type) {
                        case "class_declaration", "class" -> "class";
                        case "function_declaration", "generator_function_declaration" -> "function";
                        case "method_definition" -> "method";
                        case "interface_declaration" -> "interface";
                        default -> null;
                    };
            case "python" ->
                    switch (type) {
                        case "class_definition" -> "class";
                        case "function_definition" -> "function";
                        default -> null;
                    };
            case "sql" ->
                    switch (type) {
                        case "create_table", "create_table_statement" -> "table";
                        case "create_view", "create_view_statement" -> "view";
                        case "create_function", "create_function_statement" -> "function";
                        default -> null;
                    };
            default -> null;
        };
    }

    /** Returns the symbol name via the {@code name} field, or the first identifier child. */
    @Nullable
    private static String nameOf(TSNode node, byte[] src) {
        TSNode nameNode = node.getChildByFieldName("name");
        if (nameNode != null && !nameNode.isNull()) return text(nameNode, src);
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = node.getChild(i);
            if (c != null && !c.isNull() && c.getType().contains("identifier")) return text(c, src);
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

    /** First non-empty, non-annotation line of a node's text, with trailing '{' stripped. */
    private static String firstNonAnnotationLine(TSNode node, byte[] src) {
        for (String line : text(node, src).split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("@")) {
                return s.endsWith("{") ? s.substring(0, s.length() - 1).strip() : s;
            }
        }
        return text(node, src).strip();
    }

    /** Collapses whitespace and caps at 200 chars. */
    private static String cap(String s) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Decodes a node's byte span as UTF-8. Never misinterprets multibyte sequences. */
    private static String text(TSNode node, byte[] src) {
        int start = node.getStartByte();
        int end = Math.min(node.getEndByte(), src.length);
        if (start < 0 || start >= end) return "";
        return new String(src, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
    }
}
