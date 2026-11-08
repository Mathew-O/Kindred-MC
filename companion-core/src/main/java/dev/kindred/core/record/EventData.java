package dev.kindred.core.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In plain terms: how an event's details are written as one line of text and read back exactly.
 *
 * Technical: An ordered {@code key=value&key=value} encoding with {@code %}, {@code &}, {@code =}, carriage return and
 * newline percent-escaped, so any text round-trips. The schema 12 backfill writes the same escapes in SQL.
 */
public final class EventData {
    private EventData() {}

    public static String encode(Map<String, String> data) {
        var out = new StringBuilder();
        for (var entry : data.entrySet()) {
            if (!out.isEmpty()) out.append('&');
            out.append(escape(entry.getKey())).append('=').append(escape(entry.getValue()));
        }
        return out.toString();
    }

    public static Map<String, String> decode(String text) {
        var data = new LinkedHashMap<String, String>();
        if (text == null || text.isEmpty()) return Collections.unmodifiableMap(data);
        for (String pair : text.split("&", -1)) {
            int split = pair.indexOf('=');
            if (split < 1) throw new IllegalArgumentException("Malformed event data");
            data.put(unescape(pair.substring(0, split)), unescape(pair.substring(split + 1)));
        }
        return Collections.unmodifiableMap(data);
    }

    static String escape(String value) {
        return value.replace("%", "%25").replace("&", "%26").replace("=", "%3D").replace("\r", "%0D").replace("\n", "%0A");
    }

    static String unescape(String value) {
        if (value.indexOf('%') < 0) return value;
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') { out.append(c); continue; }
            if (i + 2 >= value.length()) throw new IllegalArgumentException("Malformed escape");
            out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
            i += 2;
        }
        return out.toString();
    }
}
