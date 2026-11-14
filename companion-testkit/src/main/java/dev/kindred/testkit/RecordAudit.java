package dev.kindred.testkit;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.FactionsAudit;
import dev.kindred.core.record.EventData;
import dev.kindred.core.record.WorldEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.UUID;

/**
 * In plain terms: the faction world's locked rules checked against a World Record kept on disk, with no server running.
 * The same check an evaluation run makes at its end ({@link FactionsAudit}), over every event the file holds, so that a
 * world stopped and started many times is checked across all its restarts at once (F-31g's restart soak).
 *
 * Technical: read only. The file is opened with SQLite's read-only flag and never written; give it a copy of the
 * database with its WAL beside it. Prints one JSON object: the audit's counts and its violations.
 *
 * <pre>./gradlew :companion-testkit:runRecordAudit --args="path/to/companions.sqlite [path/to/factions.properties]"</pre>
 */
public final class RecordAudit {
    private RecordAudit() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RecordAudit <companions.sqlite> [factions.properties]");
            System.exit(2);
        }
        Path database = Path.of(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(database)) {
            System.err.println("No such database: " + database);
            System.exit(2);
        }
        FactionSettings settings = FactionSettings.DEFAULTS;
        if (args.length > 1 && Files.isRegularFile(Path.of(args[1]))) {
            var file = new Properties();
            try (var in = Files.newBufferedReader(Path.of(args[1]))) { file.load(in); }
            var entries = new LinkedHashMap<String, String>();
            for (String key : file.stringPropertyNames()) entries.put(key, file.getProperty(key));
            settings = FactionSettings.parse(entries);
        }
        var config = new Properties();
        config.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        var kinds = FactionsAudit.kinds();
        var events = new ArrayList<WorldEvent>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database, config);
             var query = connection.prepareStatement("SELECT * FROM world_events ORDER BY id");
             var rows = query.executeQuery()) {
            while (rows.next()) {
                String kind = rows.getString("kind");
                if (!kinds.contains(kind)) continue;
                String actor = rows.getString("actor");
                events.add(new WorldEvent(rows.getLong("id"), rows.getLong("tick"), rows.getLong("real_time_ms"), rows.getString("world"), kind,
                        actor == null ? null : UUID.fromString(actor), rows.getString("subject"), rows.getString("place"),
                        EventData.decode(rows.getString("data")), WorldEvent.HowKnown.parse(rows.getString("how_known"))));
            }
        }
        var report = FactionsAudit.check(events, settings);
        var out = new StringBuilder("{\"audited\": ").append(events.size()).append(", \"counts\": {");
        String comma = "";
        for (var count : report.counts().entrySet()) {
            out.append(comma).append('"').append(count.getKey()).append("\": ").append(count.getValue());
            comma = ", ";
        }
        out.append("}, \"violations\": [");
        comma = "";
        for (String violation : report.violations()) {
            out.append(comma).append('"').append(violation.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            comma = ", ";
        }
        System.out.println(out.append("]}"));
    }
}
