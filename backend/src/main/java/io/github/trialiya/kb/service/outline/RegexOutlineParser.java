package io.github.trialiya.kb.service.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Dependency-free, best-effort outline parser. Used as a fallback when tree-sitter is unavailable
 * or does not support a language. Line-oriented regex matching: cheap, never throws, and good
 * enough to give the AI a structural map. It will miss exotic constructs — that is acceptable for a
 * fallback.
 */
public final class RegexOutlineParser implements CodeOutlineParser {

    private static final Set<String> SUPPORTED =
            Set.of("java", "javascript", "typescript", "python", "sql");

    // ── Java ──────────────────────────────────────────────────────────────────
    private static final Pattern JAVA_TYPE =
            Pattern.compile(
                    "^\\s*(?:public|private|protected|abstract|final|sealed|static|\\s)*"
                            + "(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD =
            Pattern.compile(
                    "^\\s*(?:public|private|protected|static|final|abstract|synchronized|native"
                            + "|default|\\s)*"
                            + "[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [\\w,.\\s]+)?\\{");

    // ── JS / TS ─────────────────────────────────────────────────────────────────
    private static final Pattern JS_CLASS =
            Pattern.compile("^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern JS_FUNC =
            Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(\\w+)\\s*\\(");
    private static final Pattern JS_ARROW =
            Pattern.compile(
                    "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*"
                            + "(?:async\\s+)?\\([^)]*\\)\\s*(?::[^=]+)?=>");

    // ── Python ──────────────────────────────────────────────────────────────────
    private static final Pattern PY_DEF =
            Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern PY_CLASS = Pattern.compile("^(\\s*)class\\s+(\\w+)");

    // ── SQL ──────────────────────────────────────────────────────────────────────
    private static final Pattern SQL_OBJECT =
            Pattern.compile(
                    "(?i)^\\s*create\\s+(?:or\\s+replace\\s+)?(?:temp(?:orary)?\\s+)?"
                            + "(table|view|index|function|procedure|trigger|sequence|schema)\\s+"
                            + "(?:if\\s+not\\s+exists\\s+)?[\"`\\[]?([\\w.]+)");

    @Override
    public String name() {
        return "regex";
    }

    @Override
    public boolean supports(@Nullable String language) {
        return language != null && SUPPORTED.contains(language);
    }

    @Override
    public List<GitSymbol> parse(String language, String source) {
        if (source == null || source.isEmpty() || !supports(language)) {
            return List.of();
        }
        String[] lines = source.split("\n", -1);
        return switch (language) {
            case "java" -> parseJava(lines);
            case "javascript", "typescript" -> parseJs(lines);
            case "python" -> parsePython(lines);
            case "sql" -> parseSql(lines);
            default -> List.of();
        };
    }

    private static List<GitSymbol> parseJava(String[] lines) {
        List<GitSymbol> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher t = JAVA_TYPE.matcher(line);
            if (t.find()) {
                out.add(new GitSymbol(t.group(1), t.group(2), trimSig(line), i + 1, i + 1));
                continue;
            }
            Matcher m = JAVA_METHOD.matcher(line);
            if (m.find() && !isControlKeyword(m.group(1))) {
                out.add(new GitSymbol("method", m.group(1), trimSig(line), i + 1, i + 1));
            }
        }
        return out;
    }

    private static List<GitSymbol> parseJs(String[] lines) {
        List<GitSymbol> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher c = JS_CLASS.matcher(line);
            if (c.find()) {
                out.add(new GitSymbol("class", c.group(1), trimSig(line), i + 1, i + 1));
                continue;
            }
            Matcher f = JS_FUNC.matcher(line);
            if (f.find()) {
                out.add(new GitSymbol("function", f.group(1), trimSig(line), i + 1, i + 1));
                continue;
            }
            Matcher a = JS_ARROW.matcher(line);
            if (a.find()) {
                out.add(new GitSymbol("function", a.group(1), trimSig(line), i + 1, i + 1));
            }
        }
        return out;
    }

    private static List<GitSymbol> parsePython(String[] lines) {
        List<GitSymbol> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher c = PY_CLASS.matcher(line);
            if (c.find()) {
                out.add(new GitSymbol("class", c.group(2), trimSig(line), i + 1, i + 1));
                continue;
            }
            Matcher d = PY_DEF.matcher(line);
            if (d.find()) {
                // top-level def → function, nested (indented) → method
                String kind = d.group(1).isEmpty() ? "function" : "method";
                out.add(new GitSymbol(kind, d.group(2), trimSig(line), i + 1, i + 1));
            }
        }
        return out;
    }

    private static List<GitSymbol> parseSql(String[] lines) {
        List<GitSymbol> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = SQL_OBJECT.matcher(lines[i]);
            if (m.find()) {
                out.add(
                        new GitSymbol(
                                m.group(1).toLowerCase(),
                                m.group(2),
                                trimSig(lines[i]),
                                i + 1,
                                i + 1));
            }
        }
        return out;
    }

    /** Filters out Java control-flow keywords that superficially look like method calls. */
    private static boolean isControlKeyword(String name) {
        return switch (name) {
            case "if", "for", "while", "switch", "catch", "synchronized", "return", "new" -> true;
            default -> false;
        };
    }

    private static String trimSig(String line) {
        String s = line.strip();
        // Drop a trailing opening brace for a cleaner signature.
        if (s.endsWith("{")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
