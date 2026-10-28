package dev.kindred.core;

import java.util.List;

/**
 * In plain terms: writes the short report a player gives about what it did: how many actions worked, failed
 * or are uncertain, and a few lines of detail, built only from what was actually recorded.
 *
 * Technical: One return report built only from recorded outcomes. It never starts work.
 */
public final class JournalSummary {
    private JournalSummary() { }

    /** The report as text. */
    public static String render(String name, int verifiedActions, int failedActions, int uncertainActions, List<String> lines) {
        StringBuilder text = new StringBuilder(name).append(" since you last checked: ");
        text.append(verifiedActions).append(" verified, ").append(failedActions).append(" failed, ").append(uncertainActions).append(" uncertain.");
        for (String line : lines) {
            if (text.length() > 900) break;
            text.append(' ').append(line);
        }
        if (verifiedActions == 0 && failedActions == 0 && uncertainActions == 0) text.append(" No verified work in this window.");
        return text.toString();
    }
}
