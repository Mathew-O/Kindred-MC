package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.test.CustomTestProvider;
import net.minecraft.test.TestFunction;
import net.minecraft.util.BlockRotation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.Collection;
import net.minecraft.test.GameTest;

/**
 * In plain terms: the failure corpus. Every death and stuck spell kept from the evaluation runs, played again once each,
 * from the ground it happened in and the state the player had then. A scene passes when the player comes through: alive
 * at the end, or out of the stuck spell. The share that pass is the corpus's pass rate.
 *
 * Technical: F-27a. Each {@code data/kindred_corpus/gametest/corpus/<id>.json} gives one test, of one of two kinds:
 * <ul>
 *   <li>a replay: a replay's state, with a {@code corpus} object saying where it came from and how it was staged; its
 *   structure is {@code kindred_corpus:corpus/<id>}, and {@link ReplayGameTests#play} plays it;</li>
 *   <li>a staged fixture: {@code {"fixture": "<GameTest class>.<method>", "corpus": {...}}}, a GameTest of the contract
 *   suite run as it stands, with its own template, ticks and staging (its javadoc says how it is staged).</li>
 * </ul> Scenes go at most ten to a batch, side by side, a batch's scenes sharing difficulty and weather ({@link #worldKey}),
 * since they share one world, and day and night: every replay sets the world clock to its own record as it starts, so the
 * last of a batch to start sets it for all, and a batch holds only scenes that are night at the same ticks of their runs
 * under any of its scenes' times ({@link #clockFits}). With {@code KINDRED_CORPUS_ONLY} set to scene ids
 * (comma-separated) only those run, together in one batch: the gate's confirming runs of a flagged scene. The suite
 * is its own mod and run ({@code runCorpus}), so the contract tests and the corpus never run together.
 */
public final class CorpusGameTests implements FabricGameTest {
    static final String CORPUS = "data/kindred_corpus/gametest/corpus";
    /** Scenes run side by side in one batch. */
    static final int BATCH = 10;
    /**
     * An empty test after every scene, {@value #SPACER_WIDTH} blocks wide and {@value #SPACER_DEPTH} deep, so that no
     * scene stands within sight of another (#235). 1.21.1's {@code TestStructurePlacer} sets each test 5 blocks past the
     * last in x, 8 to a row, and starts a row 6 blocks past everything placed so far in z, never clearing: scenes stood
     * 5 blocks apart, so that each saw, and went for, its neighbour's logs and ores across the barrier wall. With a
     * spacer between, a scene's wall is 5 + 90 + 5 = 100 blocks from the next in its row, past the 96 a player looks
     * over; its depth puts the second row's scenes 130 + 6 - 33 = 103 past the first's (later rows lie farther still).
     * {@code tools/corpus/layout.py} works the places out.
     */
    static final String SPACER = "kindred_corpus:spacer";
    /**
     * Night in the overworld as vanilla's {@code World.isNight} has it (ambient darkness 4 or more, from
     * {@code calculateAmbientDarkness}), as [from, to) of the day's 24000 ticks: worked out over every tick with 1.21.1's own
     * {@code MathHelper}, rain and thunder at full strength. A player shelters for the night by it (PortalRunCampaign's
     * {@code mayShelterNow}). In thunder it is night all day.
     */
    static final int NIGHT_CLEAR_FROM = 12542, NIGHT_CLEAR_TO = 23460, NIGHT_RAIN_FROM = 12010, NIGHT_RAIN_TO = 23992;
    /**
     * At most this many ticks of a scene's run may be night under a batch-mate's time and day under its own, or the
     * reverse. Batched by time alone, a scene of 11004 played into dusk at 11529, and two of 12851 and 12912 at dawn
     * (#283's counts: 20 of 422 scenes under another day or night than their own).
     */
    static final int CLOCK_TOLERANCE = 0;
    static final int SPACER_WIDTH = 90, SPACER_DEPTH = 130;

    @CustomTestProvider
    public Collection<TestFunction> scenes() {
        var tests = new ArrayList<TestFunction>();
        var folder = FabricLoader.getInstance().getModContainer("kindred_corpus").flatMap(mod -> mod.findPath(CORPUS));
        if (folder.isEmpty() || !Files.isDirectory(folder.get())) return tests;
        // KINDRED_CORPUS_ONLY: scene ids, comma-separated, to run alone (the gate's confirming runs); unset, every scene.
        String onlyList = System.getenv("KINDRED_CORPUS_ONLY");
        var only = onlyList == null || onlyList.isBlank() ? null : new java.util.HashSet<>(java.util.Arrays.asList(onlyList.trim().split("\\s*,\\s*")));
        try (var files = Files.list(folder.get())) {
            var scenes = new java.util.TreeMap<String, JsonObject>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                if (only != null && !only.contains(name)) continue;
                scenes.put(file.getFileName().toString().replace(".json", ""), JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            }
            // Batched by what a batch's scenes share, fixtures last: one world clock, weather and difficulty for ten side by
            // side, each replay setting them to its own record as it starts, so the last of ten to start set them for all
            // ten. Scenes go together by difficulty and weather, then by the time itself, from 0 to 23999.
            var order = new ArrayList<>(scenes.keySet());
            order.sort(java.util.Comparator.comparing((String id) -> worldKey(scenes.get(id))).thenComparing(java.util.Comparator.naturalOrder()));
            // A batch is at most ten scenes, all of one group, and only scenes whose day and night its clock keeps
            // (clockFits): a new group, or a scene that does not fit, starts a new batch.
            int batchNumber = -1, inBatch = BATCH;
            String group = null;
            var members = new ArrayList<JsonObject>();
            for (String id : order) {
                JsonObject scene = scenes.get(id);
                String key = worldKey(scene), its = key.substring(0, Math.max(0, key.lastIndexOf('/')));
                members.add(scene);
                if (inBatch >= BATCH || !its.equals(group) || !clockFits(members)) {
                    batchNumber++;
                    inBatch = 0;
                    group = its;
                    members.clear();
                    members.add(scene);
                }
                inBatch++;
                String batch = "corpus_" + batchNumber;
                if (scene.has("fixture")) {
                    tests.add(fixture(batch, id, scene.get("fixture").getAsString()));
                } else {
                    int ticks = scene.has("ticks") ? scene.get("ticks").getAsInt() : 1200;
                    tests.add(new TestFunction(batch, "corpus." + id, "kindred_corpus:corpus/" + id,
                            BlockRotation.NONE, ticks + 600, 0L, true, false, 1, 1, true, context -> {
                                KindredMod.LOGGER.info("Corpus scene {} placed at {}", id, context.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN).toShortString());
                                ReplayGameTests.play(context, scene, id, 1, ticks);
                            }));
                }
                tests.add(spacer(batch, id));
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException("A scene in " + CORPUS + " could not be read", unreadable);
        }
        return tests;
    }

    /**
     * What a scene sets for the whole world, as a sort key: difficulty, thunder, rain, time of day. Day or night is not in
     * it: {@link #clockFits} decides that, so a batch never runs from 23999 round to 0 or holds a dawn scene with a dusk one.
     */
    static String worldKey(JsonObject scene) {
        if (!scene.has("timeOfDay")) return "~";
        long time = Math.floorMod(scene.get("timeOfDay").getAsLong(), 24000L);
        return String.format("%s/%b/%b/%05d", scene.has("difficulty") ? scene.get("difficulty").getAsString() : "",
                scene.has("thundering") && scene.get("thundering").getAsBoolean(), scene.has("raining") && scene.get("raining").getAsBoolean(), time);
    }

    /**
     * Whether these scenes, side by side, each keep their own day and night whichever of them sets the clock: for every
     * scene and every other's time, the ticks of its run that are night under that time and day under its own, or the
     * reverse, are at most {@link #CLOCK_TOLERANCE}. Which of a batch sets the clock is whichever reaches its staging last,
     * so every pair is checked, not only the latest time.
     */
    static boolean clockFits(java.util.List<JsonObject> scenes) {
        for (JsonObject scene : scenes) {
            if (!scene.has("timeOfDay")) continue;
            for (JsonObject other : scenes) {
                if (other != scene && other.has("timeOfDay")
                        && clockDifference(scene, other.get("timeOfDay").getAsLong()) > CLOCK_TOLERANCE) return false;
            }
        }
        return true;
    }

    /** The ticks of the scene's run that are night under {@code clock} and day under its own time, or the reverse. */
    static int clockDifference(JsonObject scene, long clock) {
        long own = scene.get("timeOfDay").getAsLong();
        int ticks = scene.has("ticks") ? scene.get("ticks").getAsInt() : 1200;
        boolean rain = scene.has("raining") && scene.get("raining").getAsBoolean(), thunder = rain && scene.has("thundering") && scene.get("thundering").getAsBoolean();
        int differ = 0;
        for (int t = 0; t < ticks; t++) {
            if (night(own + t, rain, thunder) != night(clock + t, rain, thunder)) differ++;
        }
        return differ;
    }

    /** Vanilla's {@code isNight} in the overworld at this time, rain and thunder at full strength ({@link #NIGHT_CLEAR_FROM}). */
    static boolean night(long time, boolean rain, boolean thunder) {
        if (thunder) return true;
        long t = Math.floorMod(time, 24000L);
        return rain ? t >= NIGHT_RAIN_FROM && t < NIGHT_RAIN_TO : t >= NIGHT_CLEAR_FROM && t < NIGHT_CLEAR_TO;
    }

    /** The empty test after scene {@code id}: not required, done at once; only the room it takes counts. */
    private static TestFunction spacer(String batch, String id) {
        return new TestFunction(batch, "corpus.spacer." + id, SPACER, BlockRotation.NONE, 20, 0L, false, false, 1, 1, false,
                net.minecraft.test.TestContext::complete);
    }

    /** A contract-suite GameTest run as a scene: its own template, ticks and staging. */
    private static TestFunction fixture(String batch, String id, String name) throws ReflectiveOperationException {
        int dot = name.lastIndexOf('.');
        Class<?> type = Class.forName("dev.kindred.fabric." + name.substring(0, dot));
        Method method = type.getMethod(name.substring(dot + 1), net.minecraft.test.TestContext.class);
        GameTest test = method.getAnnotation(GameTest.class);
        if (test == null) throw new IllegalArgumentException(name + " is not a GameTest");
        String template = test.templateName().isEmpty() ? FabricGameTest.EMPTY_STRUCTURE : test.templateName();
        if (!template.contains(":")) template = "kindred_gametest:" + template;
        Object instance = type.getDeclaredConstructor().newInstance();
        return new TestFunction(batch, "corpus." + id, template, BlockRotation.NONE, test.tickLimit(), test.duration(), test.required(),
                false, 1, 1, test.skyAccess(), context -> {
                    try {
                        method.invoke(instance, context);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        if (thrown.getCause() instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(thrown.getCause());
                    } catch (IllegalAccessException denied) {
                        throw new IllegalStateException(denied);
                    }
                });
    }
}
