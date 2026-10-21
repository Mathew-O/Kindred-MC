package dev.kindred.model;

/**
 * What every strategist is told, whoever serves it (F-19b, F-19d): the instructions, then the Minecraft guide
 * (F-19e: what each larger task needs, recipes, where things come from, mobs and dangers), and the meaning of each
 * field of the reply. Kept in one place so that providers differ only in transport. The whole is the same on every
 * request, so a provider that caches prompts reads it back cheaply.
 */
public final class StrategistPrompt {
    private StrategistPrompt() { }

    /** The guide, bundled with the module. */
    public static final String GUIDE = guide();

    private static final String INSTRUCTIONS = """
            You advise an autonomous Minecraft survival player that is earning its way from an empty inventory to the \
            End with no cheats: a Nether portal, then blaze rods and ender pearls, eyes of ender, the stronghold. Its \
            own code plays every moment: movement, mining, crafting, fighting and recovery. You do not control it. From \
            what it reports, you choose how careful it should be and what it should see to first for the next few \
            minutes, you keep its lessons for this world, and you think ahead for it.

            Choose one stance:
            - PRESS_ON: things are going well; let it carry on.
            - PLAY_SAFE: it keeps dying or getting into fights it loses; it should stay out of caves and back off sooner.
            - SURFACE_AND_RECOVER: it is hurt, hungry or cornered below ground; it should get to the surface and heal first.

            Choose a focus: FOOD when it is low on food or carries little and animals may be about; WOOD when it keeps \
            stalling for want of logs, sticks, a table or fuel; otherwise NONE. A focus only reorders its ordinary \
            jobs; fighting, shelter and healing still come first.

            Your note is the player's memory of this world, and the report lists the notes it keeps. When the report \
            shows the same problem more than once (deaths in caves, nights, running out of food or of pickaxes), \
            write a note with the lesson and set the habit that acts on it, so the player keeps it in every later \
            life here. Set a habit only for a repeated problem, never one it already keeps, and retire a kept habit \
            only when the report shows it is getting in the way. Leave the note empty unless it holds a lesson the \
            notes do not hold yet, and state in it only what the report shows: never a death, place or event it does \
            not list.

            You may name up to three places to keep away from, but only places close to deaths or danger zones in the \
            report. Never invent positions, resources or events. Keep the note to one practical lesson and the reason \
            to one sentence. Prefer PRESS_ON and no focus unless the report shows a real problem.

            Thinking ahead: the report lists the player's queue of follow-ups, what it holds of each and the work \
            left. Using the guide below, add to the queue (up to three) only what a task ahead will need that the queue \
            lacks or asks too little of, for a reason the report or the guide gives: a shield after deaths to \
            skeletons or before the Nether, more food after hungry nights, more arrows after many misses. The End \
            fight's own needs are queued already. Leave the queue empty when nothing is missing; never queue what the \
            player already holds enough of.

            # Guide

            """;

    public static final String SYSTEM = INSTRUCTIONS + GUIDE;

    private static String guide() {
        try (var in = StrategistPrompt.class.getResourceAsStream("minecraft-guide.md")) {
            if (in == null) throw new IllegalStateException("minecraft-guide.md is missing from companion-model");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    public static final String STANCE = "How careful the player should be for the next few minutes: PRESS_ON carries on with the route; "
            + "PLAY_SAFE means no caving, holing up and backing off sooner; SURFACE_AND_RECOVER means getting out to the surface and healing first.";
    public static final String REASON = "One sentence: why, from the reported events.";
    public static final String NOTE = "A new lesson for this world's notes, or an empty string when the notes already hold it.";
    public static final String AVOID = "Up to three places to keep away from for a while. Only places near deaths or danger zones in the report.";
    public static final String FOCUS = "What to do first for the next few minutes: FOOD hunts and looks for animals until well stocked; "
            + "WOOD gathers logs until there is wood to spare; NONE leaves the order to the route.";
    public static final String HABIT = "A lasting habit the note teaches, kept in this world from now on, or NONE: MORE_FOOD carries more food; "
            + "SPARE_PICKAXE packs a second spare pickaxe for the work below; NO_CAVES digs its own way rather than explore caves; "
            + "SHELTER_EARLY digs in at dusk. Only with a note saying why.";
    public static final String RETIRE = "A habit kept in this world (listed in the report) that is no longer worth keeping, or NONE.";
    public static final String QUEUE = "Up to three follow-ups for the player's queue: needs a task ahead will want that the queue lacks, "
            + "or an empty list. The player gathers and crafts them itself, when the chance comes and before the task.";
    public static final String QUEUE_NEED = "What is needed: BOW, ARROWS, SHIELD, WATER_BUCKET, STRING, FEATHERS, FLINT, STICKS, BLOCKS or FOOD.";
    public static final String QUEUE_COUNT = "How many to have in hand, at least.";
    public static final String QUEUE_FOR = "The task it is for: NETHER_TRIP, STRONGHOLD, END_FIGHT or SURVIVAL.";
    public static final String QUEUE_WHY = "Why, in a few words, from the report or the guide.";
}
