package dev.kindred.core.wire;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: the plain words a message is said in to a person, when nothing better is set for its kind: who it is
 * from, on which channel, and its facts, in one short line ("[faction] Wren: raid planned: target birch's east wall,
 * when dusk."). A kind can have its own line with its facts filled in ("{target} at {when}"); a fact the line names
 * that the message does not carry leaves the line unsaid rather than half said.
 *
 * Technical: F-34's local phrasing, used when the voice has no model or the lane is busy. Templates are registered by
 * whoever owns the kind (Factions' kinds by its mode); nothing here invents a fact. One line, at most 200 characters.
 */
public final class Wording {
    public static final int MAX_CHARS = 200;
    private static final Pattern SLOT = Pattern.compile("\\{([a-z0-9_]+)}");

    private final Map<String, String> templates = new HashMap<>();

    /** Sets the line for a kind, its facts named in braces. */
    public Wording with(String kind, String template) {
        templates.put(Objects.requireNonNull(kind).strip().toLowerCase(java.util.Locale.ROOT), Objects.requireNonNull(template));
        return this;
    }

    /**
     * The line an onlooker may read of a message an AI player was told (F-34c, the watch page, which may be on a stream):
     * the message in words when every fact of it is public; otherwise only who it was from, on which channel and of what
     * kind ("[faction] Wren: raid planned (kept to its faction)", "[private] title (to it alone)"), never a fact narrower than public.
     */
    public String forOnlookers(Message message, String fromName) {
        boolean open = message.facts().values().stream().allMatch(f -> f.visibility() == Visibility.PUBLIC);
        if (open) {
            String words = words(message, fromName);
            if (words != null) return words;
        }
        String prefix = "[" + message.scope().channel().id() + "] " + (message.fromServer() || fromName == null || fromName.isBlank() ? "" : fromName + ": ");
        String kind = message.kind();
        int dot = kind.indexOf('.');
        String line = prefix + (dot >= 0 ? kind.substring(dot + 1) : kind).replace('_', ' ').replace('-', ' ')
                + (message.scope().channel().audience() == Channel.Audience.PRIVATE ? " (to it alone)" : " (kept to its " + message.scope().channel().id() + ")");
        line = line.replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_CHARS ? line : line.substring(0, MAX_CHARS - 1) + "…";
    }

    /** The words for this message from {@code fromName} ("" for the server); null when its kind's line names a fact it lacks. */
    public String words(Message message, String fromName) {
        String prefix = "[" + message.scope().channel().id() + "] " + (message.fromServer() || fromName == null || fromName.isBlank() ? "" : fromName + ": ");
        String template = templates.get(message.kind());
        String body;
        if (template != null) {
            Matcher m = SLOT.matcher(template);
            var out = new StringBuilder();
            while (m.find()) {
                Fact fact = message.facts().get(m.group(1));
                if (fact == null || fact.value().isEmpty()) return null;
                m.appendReplacement(out, Matcher.quoteReplacement(fact.value()));
            }
            m.appendTail(out);
            body = out.toString();
        } else {
            String kind = message.kind();
            int dot = kind.indexOf('.');
            var out = new StringBuilder((dot >= 0 ? kind.substring(dot + 1) : kind).replace('_', ' ').replace('-', ' '));
            String sep = ": ";
            for (var e : message.facts().entrySet()) {
                if (e.getValue().value().isEmpty()) continue;
                out.append(sep).append(e.getKey().replace('_', ' ')).append(' ').append(e.getValue().value());
                sep = ", ";
            }
            body = out.append('.').toString();
        }
        String line = (prefix + body).replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_CHARS ? line : line.substring(0, MAX_CHARS - 1) + "…";
    }
}
