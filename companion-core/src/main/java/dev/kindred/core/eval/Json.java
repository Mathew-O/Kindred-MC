package dev.kindred.core.eval;

/** Technical: the little JSON the evaluation record writes, by hand, as core has no library for it. */
final class Json {
    private Json() { }

    /** {@code text} as a JSON string, quotes, backslashes and control characters escaped; null as {@code null}. */
    static String string(String text) {
        if (text == null) return "null";
        var out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
