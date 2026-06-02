package io.github.trialiya.kb.tools;

public final class Compact {
    private Compact() {}

    public static String truncate(String s, int max) {
        if (s == null) return null;
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…(+" + (s.length() - max) + ")";
    }

    public static String truncateObject(Object object, int max) {
        if (object == null) return null;
        return truncate(object.toString(), max);
    }

    public static Kv tag(String tag) {
        return new Kv(tag);
    }

    public static final class Kv {
        private final StringBuilder sb;

        Kv(String tag) {
            sb = new StringBuilder(tag);
        }

        public Kv add(String k, Object v) {
            if (v == null) return this;
            String s = v.toString();
            if (s.isBlank()) return this;
            sb.append(' ').append(k).append('=').append(s.indexOf(' ') >= 0 ? '"' + s + '"' : s);
            return this;
        }

        public Kv body(String text) { // тело на новой строке
            if (text != null && !text.isBlank()) sb.append('\n').append(text);
            return this;
        }

        public String done() {
            return sb.toString();
        }
    }
}
