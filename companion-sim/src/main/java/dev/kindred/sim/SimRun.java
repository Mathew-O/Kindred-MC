package dev.kindred.sim;

import dev.kindred.core.Personality;
import dev.kindred.core.PlayerDecisionLoop;
import dev.kindred.core.PlayerIdentity;
import dev.kindred.core.SurvivalPolicy;
import dev.kindred.core.TaskPreferences;
import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.atlas.Priors;
import dev.kindred.core.eval.EvalRecord;
import dev.kindred.core.eval.EvalScenario;
import dev.kindred.core.route.Cell;
import dev.kindred.core.route.Navigator;
import dev.kindred.core.route.RoutePlanner;
import dev.kindred.core.survival.Lessons;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import dev.kindred.core.survival.WorldFacts;
import dev.kindred.sim.SimWorld.Block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * In plain terms: one simulated game of one AI player on a made-up world: it decides what to do as the real player
 * does, with the same planner and the same choice between staying alive and getting on, and the simulator says how
 * long each thing took and what came of it. Mobs come out in the dark, hunger drains, falls and blows hurt.
 *
 * Technical: F-28. The decisions are the core's own: {@link PlayerDecisionLoop} and {@link dev.kindred.core.TaskDirector}
 * choose among candidates built as the adapter builds them (the goal's step at {@code PROJECT}, staying alive at
 * {@code URGENT}); {@link SurvivalPolicy} weighs threats and eating; {@link PortalRunPlanner} plans the route from a
 * {@link PortalRunSnapshot} and {@link WorldFacts} counted from what the player holds and has seen; {@link Lessons}
 * turns its deaths into habits; the {@link Atlas} keeps what it saw and where it was hurt. What each step does is the
 * simulator's: a skill's outcome and its duration by vanilla's rules where they matter (breaking by tool and block,
 * walking speed, smelting, hunger, fall damage), roughly elsewhere (mobs walk straight at the player). A walk is
 * planned by the core's own route planners over the simulator's world, as the adapter plans over the live one (F-35d),
 * and paid for a leg at a time. It tests decisions and plans, not execution: the adapter's actions are not run. Its log
 * lines are the server's, in the form RunMetrics reads, and its result is an {@link EvalRecord}.
 */
public final class SimRun {
    /** How a run begins. */
    public enum Start {
        /** Empty-handed at dawn on open ground. */
        FRESH,
        /** At dusk on open ground, with stone tools, a sword, a little bread and dirt. */
        DUSK,
        /** In a cave with an iron pickaxe, a stone sword, torches and bread. */
        CAVE
    }

    public record Config(String pack, long seed, long ticks, Start start, String name) { }

    // ------------------------------------------------------------------ the player

    private final String name;
    private int x, y, z;
    private float health = 20, saturation = 5, exhaustion;
    private int food = 20;
    private final Map<String, Integer> items = new HashMap<>();
    /** Uses left on its best pickaxe, sword and axe. */
    private int pickaxeUses, swordUses, axeUses;
    private int pickaxeTier, swordTier, axeTier, chestTier;
    private boolean sheltered;
    /** Where it stood before it dug in for the night. */
    private int shelterY;
    /** Sealed in below ground where it stood, rather than dug in. */
    private boolean sealedInPlace;
    private int[] frameAt;
    /** Its crafting tables and furnaces where it put them down: they stay in the world, at hand within sixteen blocks. */
    private final List<int[]> tables = new ArrayList<>();
    private final List<Furnace> furnaces = new ArrayList<>();
    /** Where it stood when its workstations were last looked to: one it walks away from is taken along. */
    private int keptX, keptY, keptZ;
    private boolean portalFramed, portalLit;
    private String lastHurtBy = "";
    private long lastHurt = Long.MIN_VALUE;
    /** The mob it faces behind a raised shield in a fight, whose blast or arrows the shield stops; null when none. */
    private Mob guarding;
    /** Until when a run from a creeper is not tried again, as the campaign's after one found no way out. */
    private long runFailedUntil = Long.MIN_VALUE;
    /** Running from a hissing creeper now: the run is not cut short for it. */
    private boolean fleeing;
    /** Its things where it died, and until when they lie there. */
    private Map<String, Integer> drops = Map.of();
    private int dropX, dropY, dropZ;
    private long dropsUntil;

    // ------------------------------------------------------------------ the world, mobs and what it knows

    private final SimWorld world;
    private final SplittableRandom random;
    private final List<Mob> mobs = new ArrayList<>();
    /** Animals on open ground, placed with the world: where they stand. */
    private final List<int[]> animals = new ArrayList<>();
    private final Map<WorldFacts.Source, int[]> seen = new EnumMap<>(WorldFacts.Source.class);
    /** Cave floor it saw on its last look: open cells below the ground with a floor to stand on. */
    private final List<int[]> caves = new ArrayList<>();
    /** The cells of four blocks it has explored a cave in, as the campaign keeps them. */
    private final Set<Long> visitedCaves = new java.util.HashSet<>();
    private final Atlas atlas;
    private final PlayerDecisionLoop mind;
    private final Personality personality = Personality.defaults();
    private final List<Lessons.Death> deathFacts = new ArrayList<>();
    private final Set<PlanPreferences.Habit> habits = EnumSet.noneOf(PlanPreferences.Habit.class);
    private final Map<PlanPreferences.Habit, Long> learned = new HashMap<>(), retired = new HashMap<>();
    private final EvalRecord record;
    private final SimLog log;
    private final long start, end;
    private String loggedGoal = "";
    private int stuckSpells;
    /** The campaign's watchdog: time stuck while its last task was active work, and the best of each counter the route needs. */
    private long stuckClock, watchedAt;
    private boolean lastActive;
    private final int[] best = new int[14];
    private boolean bestKnown;
    private final Set<Long> seenCells = new java.util.HashSet<>();
    /** Steps refused as they were planned, by the step: a planner that asks for one again and again with nothing new. */
    private final Map<String, Integer> refusedRuns = new HashMap<>();
    private final List<String> anomalies = new ArrayList<>();
    private int lookedX = Integer.MIN_VALUE / 2, lookedY, lookedZ;
    private long stalledSince = -1;
    private boolean stallNoted;
    private long lookedAt = Long.MIN_VALUE / 2;

    /** A furnace put down: one slot in and one out, as vanilla's, and how many items the fuel in it still smelts. */
    private static final class Furnace {
        final int x, y, z;
        String in = "", out = "";
        int inCount, outCount;
        double burn;
        long nextAt;

        Furnace(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean empty() { return inCount == 0 && outCount == 0; }

        /** Smelting while there is something in it and fuel to burn it, as the adapter reads a furnace. */
        boolean smelting() { return inCount > 0 && burn >= 1 - 1e-9; }
    }

    private static final class Mob {
        final String kind;
        double x, y, z;
        float health;
        int cooldown, fuse = -1;
        boolean exploded;
        /** Until when it is left alone, after a fight that could not reach it. */
        long ignoredUntil = Long.MIN_VALUE;
        /** After the player: it has seen it within its follow range, and keeps after it while it stays in that range. */
        boolean after;
        /** Wandering, while after no one: the way it is going and until when. */
        double heading;
        long headingUntil;

        Mob(String kind, double x, double y, double z, float health) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
        }
    }

    public SimRun(Config config, Priors priors) {
        this(config, priors, SimWorld.generate(config.seed(), priors), null, Map.of());
    }

    /**
     * A staged scene (F-35d): the run on {@code world} as it is given, the player standing at {@code at} with {@code kit}
     * and nothing else, at the world's own time; the config's start is not used. With {@code at} null, the config's start.
     */
    SimRun(Config config, Priors priors, SimWorld world, int[] at, Map<String, Integer> kit) {
        this.name = config.name();
        this.world = world;
        this.random = new SplittableRandom(config.seed() * 31 + 7);
        this.atlas = new Atlas(priors);
        this.mind = new PlayerDecisionLoop(new PlayerIdentity(UUID.nameUUIDFromBytes(name.getBytes()), UUID.nameUUIDFromBytes(("sim" + config.seed()).getBytes()), name));
        this.record = new EvalRecord(EvalScenario.parse("starters=" + name + "; goal=nether; ticks=" + config.ticks()));
        this.log = new SimLog(name);
        // Animals on open grass, as the world's chunks spawn them: forty about the map.
        for (int i = 0; i < 400 && animals.size() < 40; i++) {
            int ax = random.nextInt(SimWorld.SIZE), az = random.nextInt(SimWorld.SIZE), ay = world.top(ax, az);
            if (world.at(ax, ay, az) == Block.GRASS) animals.add(new int[] {ax, ay + 1, az});
        }
        if (at == null) {
            place(config.start());
        } else {
            x = at[0];
            y = at[1];
            z = at[2];
            kit.forEach(this::give);
            refreshTools();
            keptX = x;
            keptY = y;
            keptZ = z;
        }
        this.start = world.tick;
        this.end = world.tick + config.ticks();
        this.watchedAt = world.tick;
    }

    /** Where the run begins, and with what. */
    private void place(Start how) {
        int cx = SimWorld.SIZE / 2, cz = SimWorld.SIZE / 2;
        world.tick = how == Start.DUSK ? 12_000 : 0;
        if (how == Start.CAVE) {
            // The cave cell nearest the middle, low enough to be under ground and with a floor.
            int best = Integer.MAX_VALUE;
            for (int px = 8; px < SimWorld.SIZE - 8; px++) for (int pz = 8; pz < SimWorld.SIZE - 8; pz++) for (int py = -40; py < 40; py++) {
                if (!world.at(px, py, pz).open() || !world.at(px, py + 1, pz).open() || !world.at(px, py - 1, pz).solid() || world.skyOpen(px, py, pz)) continue;
                int d = Math.abs(px - cx) + Math.abs(pz - cz) + Math.abs(py);
                if (d < best) { best = d; x = px; y = py; z = pz; }
            }
            if (best == Integer.MAX_VALUE) how = Start.FRESH;
            else {
                give("iron_pickaxe", 1);
                give("stone_sword", 1);
                give("torch", 32);
                give("bread", 8);
                give("cobblestone", 16);
                give("stick", 4);
                give("crafting_table", 1);
            }
        }
        if (how != Start.CAVE) {
            x = cx;
            z = cz;
            y = ground(x, z);
            for (int r = 1; world.at(x, y - 1, z) == Block.WATER && r < 40; r++) {
                x = cx + r;
                y = ground(x, z);
            }
        }
        if (how == Start.DUSK) {
            give("stone_pickaxe", 1);
            give("stone_sword", 1);
            give("bread", 3);
            give("dirt", 16);
            give("stick", 2);
        }
        refreshTools();
        keptX = x;
        keptY = y;
        keptZ = z;
    }

    // ------------------------------------------------------------------ the run

    /** Plays the run to its end; returns the result as the server's file would hold it. */
    public Result run() {
        while (world.tick < end) {
            // Dead, however it came about (in a wait as well as at work): what the game does with a death, first.
            if (health <= 0) die();
            settle();
            if (health <= 0) die();
            look();
            Choice choice = decide();
            if (choice == null) {
                // Nothing to set about: dug in for the night that is the campaign's wait for daylight, and no time stuck;
                // otherwise a plan with nothing in it, which is.
                watch(!sheltered);
                pass(20, false);
                continue;
            }
            long before = world.tick;
            var outcome = act(choice);
            // A task that did not go is let be a while, as the campaign sets its own aside, not given out again at once.
            if (outcome.state().equals("BLOCKED")) setAside.put(choice.kind() + ":" + choice.label(), world.tick + 600);
            carryAlong();
            // A task that goes nowhere still takes the server a tick or more to give out and end.
            if (world.tick == before) pass(5, false);
            log.line(world.tick, name + " " + outcome.skill() + " " + outcome.state()
                    + (outcome.reason().isEmpty() ? "" : ": " + outcome.reason()) + " [" + choice.label() + "]");
            // The runtime's own tasks (eating, its retreat from a hissing creeper) are not the campaign's: they leave its clock be.
            if (!choice.kind().equals("eat") && !choice.kind().equals("retreat")) watch(active(choice));
            if (health <= 0) die();
        }
        look();
        // Iron left in a furnace it is no longer near: smelted or not, the planner no longer counts it.
        for (Furnace furnace : furnaces) {
            int iron = (furnace.in.equals("raw_iron") ? furnace.inCount : 0) + (furnace.out.equals("iron_ingot") ? furnace.outCount : 0);
            if (iron > 0 && !within(furnace.x, furnace.y, furnace.z, x, y, z)) {
                anomalies.add("iron left in a furnace it walked away from: " + iron + " at " + furnace.x + " " + furnace.y + " " + furnace.z
                        + ", " + (int) Math.sqrt(sq(furnace.x - x) + sq(furnace.y - y) + sq(furnace.z - z)) + " blocks off at the end");
            }
        }
        return new Result(record.toJson("sim", world.seed, world.tick - start, true), log.text(), List.copyOf(anomalies),
                record.deaths(name), stuckSpells);
    }

    public record Result(String json, String log, List<String> anomalies, int deaths, int stuck) { }

    /** Where the player stands now: its feet's cell. */
    int[] at() { return new int[] {x, y, z}; }

    /** How many of {@code item} it holds now. */
    int holding(String item) { return count(item); }

    private record Choice(String kind, String label, PortalRunPlanner.Step step, Mob mob) { }

    private record Outcome(String skill, String state, String reason) {
        static Outcome done(String skill) { return new Outcome(skill, "COMPLETED", ""); }
        static Outcome blocked(String skill, String why) { return new Outcome(skill, "BLOCKED", why); }
        static Outcome cut(String skill, String why) { return new Outcome(skill, "CANCELLED", why); }
    }

    /**
     * The choice for now, as the adapter makes it: what keeps it alive is URGENT (a threat, hunger, its things where it
     * died, a shelter at night in the open), the goal's next step PROJECT; the decision loop and its director choose.
     */
    /** Choices that did not go, until when each is let be. */
    private final Map<String, Long> setAside = new HashMap<>();

    /** How far a workstation is at hand, as the campaign looks for its own: sixteen blocks. */
    private static final int AT_HAND = 16;

    /**
     * Its workstations taken along as it moves on, as the campaign picks its own up when it leaves: one it was beside and
     * has walked away from, the table while it carries none, the furnace once it is empty and with no raw iron in hand.
     * One left where it died stays there: it is taken along only after it has come back to it.
     */
    private void carryAlong() {
        if (count("crafting_table") == 0) {
            for (var it = tables.iterator(); it.hasNext(); ) {
                int[] table = it.next();
                if (within(table[0], table[1], table[2], keptX, keptY, keptZ) && !within(table[0], table[1], table[2], x, y, z)) {
                    it.remove();
                    give("crafting_table", 1);
                    break;
                }
            }
        }
        if (count("furnace") == 0 && count("raw_iron") == 0) {
            for (var it = furnaces.iterator(); it.hasNext(); ) {
                Furnace furnace = it.next();
                if (furnace.empty() && within(furnace.x, furnace.y, furnace.z, keptX, keptY, keptZ) && !within(furnace.x, furnace.y, furnace.z, x, y, z)) {
                    it.remove();
                    give("furnace", 1);
                    break;
                }
            }
        }
        keptX = x;
        keptY = y;
        keptZ = z;
    }

    private static boolean within(int ax, int ay, int az, int bx, int by, int bz) {
        return sq(ax - bx) + sq(ay - by) + sq(az - bz) <= AT_HAND * AT_HAND;
    }

    /** Its nearest crafting table at hand, or null. */
    private int[] tableNear() {
        int[] best = null;
        for (int[] table : tables) {
            if (within(table[0], table[1], table[2], x, y, z) && (best == null || sq(table[0] - x) + sq(table[1] - y) + sq(table[2] - z)
                    < sq(best[0] - x) + sq(best[1] - y) + sq(best[2] - z))) best = table;
        }
        return best;
    }

    /** Its nearest furnace at hand, or null. */
    private Furnace furnaceNear() {
        Furnace best = null;
        for (Furnace furnace : furnaces) {
            if (within(furnace.x, furnace.y, furnace.z, x, y, z) && (best == null || sq(furnace.x - x) + sq(furnace.y - y) + sq(furnace.z - z)
                    < sq(best.x - x) + sq(best.y - y) + sq(best.z - z))) best = furnace;
        }
        return best;
    }

    private boolean asideNow(Choice choice) {
        return setAside.getOrDefault(choice.kind() + ":" + choice.label(), Long.MIN_VALUE) > world.tick;
    }

    private Choice decide() {
        var candidates = new ArrayList<TaskPreferences.Candidate>();
        var choices = new HashMap<UUID, Choice>();
        // As the runtime's emergency rule: a creeper whose fuse is lit is backed away from (0b, URGENT 98).
        for (Mob mob : mobs) {
            if (mob.kind.equals("creeper") && mob.fuse >= 0 && distance(mob) <= 6) {
                urgent(candidates, choices, 98, new Choice("retreat", "Get away from the creeper", null, mob));
                break;
            }
        }
        // The campaign's creeper work outranks it (URGENT 99), as its defence picks it: armed and with the health to
        // spare (ten, six behind a shield), hit and run; a shield faces a hissing one close by; otherwise run, and with
        // nowhere to run, wall it off.
        Mob creeper = creeperAfter();
        if (creeper != null) {
            boolean shield = count("shield") > 0, armed = swordTier > 0 || pickaxeTier > 0 || axeTier > 0;
            Choice work;
            if (shield && creeper.fuse >= 0 && distance(creeper) < 6 || armed && health >= (shield ? 6 : 10)) work = new Choice("fight", "Fight creeper", null, creeper);
            else if (world.tick >= runFailedUntil) work = new Choice("run", "Run from creeper", null, creeper);
            else work = new Choice("cover", "Take cover from creeper", null, creeper);
            urgent(candidates, choices, 99, work);
        }
        Mob threat = nearestOther(8);
        if (creeper == null && threat != null && sees(threat)) {
            var answer = SurvivalPolicy.evaluateThreat("minecraft:" + threat.kind, distance(threat), health, 20, personality);
            if (answer == SurvivalPolicy.Action.DEFEND) urgent(candidates, choices, 99, new Choice("fight", "Fight " + threat.kind, null, threat));
            else if (answer == SurvivalPolicy.Action.RETREAT) urgent(candidates, choices, 98, new Choice("retreat", "Get away from " + threat.kind, null, threat));
        }
        int edible = count("bread") + count("cooked_meat") + count("raw_meat");
        if (edible > 0 && SurvivalPolicy.worthEating(food, 5, health < 20)) urgent(candidates, choices, 95, new Choice("eat", "Eat", null, null));
        boolean night = night();
        if (night && !underground() && !sheltered) urgent(candidates, choices, 94, new Choice("shelter", "Dig in for the night", null, null));
        if (!drops.isEmpty() && world.tick < dropsUntil && !night) urgent(candidates, choices, 90, new Choice("drops", "Back for its things", null, null));
        if (sheltered && !night) urgent(candidates, choices, 93, new Choice("unseal", "Open its shelter", null, null));
        if (!sheltered || !night) {
            var snapshot = snapshot();
            var plan = new ArrayList<>(PortalRunPlanner.plan(snapshot, facts(), new PlanPreferences(habits, PlanPreferences.Focus.NONE)));
            // The campaign's own upkeep the plan does not order: water while a source is close, coal in view.
            int[] water = seen.get(WorldFacts.Source.WATER), coal = seen.get(WorldFacts.Source.COAL_ORE);
            if (snapshot.emptyBuckets() > snapshot.lavaBuckets() && snapshot.waterBuckets() == 0 && water != null
                    && sq(water[0] - x) + sq(water[2] - z) < 24 * 24 && Math.abs(water[1] - y) <= 6) {
                plan.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.FILL_WATER, "", 1, PortalRunPlanner.Stage.IRON, "Fill a bucket with water while it is close"));
            }
            if (snapshot.coal() < 4 && snapshot.pickaxeTier() >= 1 && coal != null && sq(coal[0] - x) + sq(coal[1] - y) + sq(coal[2] - z) < 100) {
                plan.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.MINE_COAL, "", 1, PortalRunPlanner.Stage.IRON, "Take the coal in view"));
            }
            // The campaign's night: below ground the route's iron meanwhile, instead of the climb out for logs; and logs
            // themselves wait for daylight, the way in sealed first below ground.
            if (night && !plan.isEmpty() && plan.getLast().action() == PortalRunPlanner.Action.GATHER_LOGS) {
                if (underground()) PortalRunPlanner.withoutWood(snapshot, facts()).ifPresent(meanwhile -> plan.set(plan.size() - 1, meanwhile));
                if (plan.getLast().action() == PortalRunPlanner.Action.GATHER_LOGS) {
                    boolean seal = underground() && !sheltered && count("dirt") + count("cobblestone") > 0;
                    var id = UUID.nameUUIDFromBytes(("goal:night").getBytes());
                    candidates.add(new TaskPreferences.Candidate(id, TaskPreferences.Priority.PROJECT, 70,
                            new TaskPreferences.Affinities(0, 0, 0, 0.6), new TaskPreferences.Eligibility(true, true, true, true)));
                    choices.put(id, seal ? new Choice("seal", "Seal the shelter", null, null) : new Choice("wait", "Wait for daylight", null, null));
                    plan.clear();
                }
            }
            // As the campaign does: the first step it can act on now; none, and it waits for a feasible step.
            PortalRunPlanner.Step chosen = null;
            for (var each : plan) {
                if (each.action() == PortalRunPlanner.Action.DONE) break;
                if (night && each.action() == PortalRunPlanner.Action.HUNT && snapshot.edibleFood() > 0) continue;
                if (night && each.action() == PortalRunPlanner.Action.SEEK_FOOD) continue;
                if (realizable(each) && !asideNow(new Choice("goal", each.why(), each, null))) {
                    chosen = each;
                    break;
                }
            }
            // Nothing in the plan it can act on, in daylight and out of shelter: the planner has left it nothing to do.
            if (chosen == null && !night) {
                if (stalledSince < 0) stalledSince = world.tick;
                if (world.tick - stalledSince >= 1_200 && !stallNoted) {
                    stallNoted = true;
                    anomalies.add("a minute with no step it could act on at " + world.tick + " ticks: plan " + plan.stream().map(st -> st.action() + (st.item().isEmpty() ? "" : " " + st.item())).toList()
                            + "; holding " + new java.util.TreeMap<>(items) + ", pickaxe tier " + pickaxeTier + ", table " + (tableNear() != null) + ", furnace " + (furnaceNear() != null)
                            + ", seen " + seen.keySet());
                }
            } else {
                stalledSince = -1;
            }
            if (chosen != null) {
                var step = chosen;
                String label = stageLabel(step.stage()) + ": " + step.why();
                String goalLine = name + " goal at " + x + " " + y + " " + z + ": " + label;
                if (!goalLine.equals(loggedGoal)) {
                    loggedGoal = goalLine;
                    log.line(world.tick, goalLine);
                }
                var id = UUID.nameUUIDFromBytes(("goal:" + step.action() + ":" + step.item()).getBytes());
                candidates.add(new TaskPreferences.Candidate(id, TaskPreferences.Priority.PROJECT, 70,
                        new TaskPreferences.Affinities(0, 0, 0, 0.6), new TaskPreferences.Eligibility(true, true, true, true)));
                choices.put(id, new Choice("goal", step.why(), step, null));
            }
        }
        var decision = mind.decide(new PlayerDecisionLoop.Frame(true, false, false, false, false, true, null, candidates), personality);
        if (decision.action() != PlayerDecisionLoop.Action.PROPOSE_TASK) return null;
        return choices.get(decision.proposal().orElseThrow().candidate().id());
    }

    private void urgent(List<TaskPreferences.Candidate> candidates, Map<UUID, Choice> choices, double score, Choice choice) {
        if (asideNow(choice)) return;
        var id = UUID.nameUUIDFromBytes(("urgent:" + choice.kind()).getBytes());
        candidates.add(new TaskPreferences.Candidate(id, TaskPreferences.Priority.URGENT, score, TaskPreferences.Affinities.none(),
                new TaskPreferences.Eligibility(true, true, true, true)));
        choices.put(id, choice);
    }

    private static String stageLabel(PortalRunPlanner.Stage stage) {
        String s = stage.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * What it holds and has at hand, as the adapter counts it for the planner: what is in a furnace counts only while
     * that furnace is within sixteen blocks, as the campaign's snapshot reads the nearest one there and no other.
     */
    private PortalRunSnapshot snapshot() {
        Furnace furnace = furnaceNear();
        int rawIron = count("raw_iron"), iron = count("iron_ingot"), coal = count("coal"), output = 0;
        boolean smelting = false, cooking = false;
        if (furnace != null) {
            smelting = furnace.smelting();
            output = furnace.outCount;
            if (furnace.in.equals("raw_iron")) rawIron += furnace.inCount;
            if (furnace.out.equals("iron_ingot")) iron += furnace.outCount;
            if (furnace.out.equals("charcoal")) coal += furnace.outCount;
            cooking = smelting && furnace.in.equals("raw_meat");
        }
        return new PortalRunSnapshot(count("log"), count("planks"), count("stick"), count("cobblestone"), coal, rawIron,
                iron, count("diamond"), count("flint"), count("gravel"), count("obsidian"),
                count("bucket") + count("lava_bucket"), count("lava_bucket"), count("water_bucket"), count("flint_and_steel"),
                count("crafting_table"), count("furnace"), count("sapling"), count("bread") + count("cooked_meat") + count("raw_meat"),
                pickaxeTier, count("stone_pickaxe") + count("iron_pickaxe") + count("diamond_pickaxe"), swordTier, chestTier, count("shield"),
                tableNear() != null, furnace != null, smelting, output, food, portalFramed, portalLit, count("raw_meat"), cooking);
    }

    /** What it has seen, by how far the nearest of each kind is, and what this world lets it do. */
    private WorldFacts facts() {
        var nearest = new EnumMap<WorldFacts.Source, Integer>(WorldFacts.Source.class);
        for (var each : seen.entrySet()) {
            int[] at = each.getValue();
            nearest.put(each.getKey(), (int) Math.round(Math.sqrt(sq(at[0] - x) + sq(at[1] - y) + sq(at[2] - z))));
        }
        return new WorldFacts(nearest, Set.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.CAST_OBSIDIAN, WorldFacts.Method.DIG_FOR_STONE));
    }

    /**
     * What it sees from where it stands, as a player would: the faces of blocks open to the air within sixteen blocks,
     * and trees and water on open ground within forty-eight. Kept as the nearest of each kind; the atlas told of ores.
     */
    private void look() {
        // What it saw a moment ago from about here still holds; a look again once it has moved or a while has passed.
        if (Math.abs(x - lookedX) + Math.abs(y - lookedY) + Math.abs(z - lookedZ) < 3 && world.tick - lookedAt < 400) {
            seen.values().removeIf(at -> source(world.at(at[0], at[1], at[2])) == null);
            stages();
            return;
        }
        lookedX = x;
        lookedY = y;
        lookedZ = z;
        lookedAt = world.tick;
        seen.clear();
        caves.clear();
        for (int dx = -16; dx <= 16; dx++) for (int dy = -12; dy <= 12; dy++) for (int dz = -16; dz <= 16; dz++) {
            int px = x + dx, py = y + dy, pz = z + dz;
            Block b = world.at(px, py, pz);
            if (b.open() && world.at(px, py + 1, pz).open() && world.at(px, py - 1, pz).solid() && py < ground(px, pz) - 2) caves.add(new int[] {px, py, pz});
            var source = source(b);
            if (source == null || !world.exposed(px, py, pz)) continue;
            remember(source, px, py, pz);
        }
        for (int dx = -48; dx <= 48; dx += 2) for (int dz = -48; dz <= 48; dz += 2) {
            int px = x + dx, pz = z + dz, top = world.top(px, pz);
            Block b = world.at(px, top, pz);
            if (b == Block.LEAVES || b == Block.LOG) {
                int ty = top;
                while (ty > top - 8 && world.at(px, ty, pz) != Block.LOG) ty--;
                if (world.at(px, ty, pz) == Block.LOG) remember(WorldFacts.Source.TREE, px, ty, pz);
            } else if (b == Block.WATER) {
                remember(WorldFacts.Source.WATER, px, top, pz);
            }
        }
        if (world.at(x, y - 1, z) == Block.STONE) remember(WorldFacts.Source.STONE, x, y - 1, z);
        stages();
    }

    private static WorldFacts.Source source(Block b) {
        return switch (b) {
            case LOG -> WorldFacts.Source.TREE;
            case STONE -> WorldFacts.Source.STONE;
            case COAL_ORE -> WorldFacts.Source.COAL_ORE;
            case IRON_ORE -> WorldFacts.Source.IRON_ORE;
            case DIAMOND_ORE -> WorldFacts.Source.DIAMOND_ORE;
            case LAVA -> WorldFacts.Source.LAVA;
            case WATER -> WorldFacts.Source.WATER;
            case GRAVEL -> WorldFacts.Source.GRAVEL;
            default -> null;
        };
    }

    private void remember(WorldFacts.Source source, int px, int py, int pz) {
        int[] was = seen.get(source);
        double d = sq(px - x) + sq(py - y) + sq(pz - z);
        if (was == null || d < sq(was[0] - x) + sq(was[1] - y) + sq(was[2] - z)) seen.put(source, new int[] {px, py, pz});
        var resource = switch (source) {
            case IRON_ORE -> Atlas.Resource.IRON;
            case DIAMOND_ORE -> Atlas.Resource.DIAMOND;
            case COAL_ORE -> Atlas.Resource.COAL;
            case LAVA -> Atlas.Resource.LAVA;
            default -> null;
        };
        if (resource != null) atlas.sighted(resource, new Atlas.Place("overworld", px, py, pz), world.tick, name);
    }

    /** The stages it has reached, as the server's evaluation reads them from what it holds. */
    private void stages() {
        long at = world.tick - start;
        if (pickaxeTier >= 1 || count("wooden_pickaxe") > 0) record.reached(name, "wooden_tool", at);
        if (pickaxeTier >= 2) record.reached(name, "stone_tools", at);
        if (count("iron_ingot") > 0 || pickaxeTier >= 3 || count("bucket") > 0) record.reached(name, "iron_ingot", at);
        if (pickaxeTier >= 3) record.reached(name, "iron_pickaxe", at);
        if (count("diamond") > 0 || pickaxeTier >= 4) record.reached(name, "diamond", at);
        if (count("obsidian") > 0 || portalFramed) record.reached(name, "obsidian", at);
        if (portalLit) record.reached(name, "portal_lit", at);
    }

    /**
     * Whether a task is active work, as the campaign's watchdog counts it: safety, shelter and waiting are not time
     * stuck (its {@code passive} labels: a fight, cover, a run, backing off, digging in, sealing a shelter).
     */
    private static boolean active(Choice choice) {
        return switch (choice.kind()) {
            case "fight", "run", "cover", "retreat", "shelter", "seal", "wait" -> false;
            default -> !choice.label().startsWith("Fight") && !choice.label().startsWith("Dig in");
        };
    }

    /**
     * The campaign's watchdog, as it counts: the time since it last looked counts as stuck when its last task was active
     * work; a new best in any counter the route needs, or ground it has not stood on (a cell of eight blocks), starts the
     * clock again; three game minutes of it is a stuck spell, and it tries elsewhere.
     */
    private void watch(boolean nowActive) {
        if (lastActive) stuckClock += world.tick - watchedAt;
        watchedAt = world.tick;
        lastActive = nowActive;
        if (gainedSomething()) stuckClock = 0;
        else if (seenCells.size() < 100_000 && seenCells.add(((long) (x >> 3) << 40) ^ ((long) ((y >> 3) & 0xFFFFF) << 20) ^ (z >> 3))) stuckClock = 0;
        if (stuckClock <= 3_600) return;
        stuckSpells++;
        record.stuck(name, new EvalRecord.Stuck(world.tick - start, x, y, z, stuckSpells));
        log.line(world.tick, name + " Goal made no progress for 3600 ticks; trying elsewhere");
        stuckClock = 0;
        // As the watchdog does: somewhere else, a little way off.
        int nx = Math.clamp(x + random.nextInt(-24, 25), 2, SimWorld.SIZE - 3), nz = Math.clamp(z + random.nextInt(-24, 25), 2, SimWorld.SIZE - 3);
        walkTo(nx, ground(nx, nz), nz, "native_goto", WALK, Way.PARTIAL, 3);
    }

    /** A new best in a counter the route needs, as the campaign's {@code gainedSomething} reads them from the snapshot. */
    private boolean gainedSomething() {
        var s = snapshot();
        int[] values = {PortalRunPlanner.stage(s, facts()).ordinal(), s.pickaxeTier(), s.swordTier(), s.chestArmorTier(), Math.min(s.woodUnits(), 40),
                Math.min(s.cobble(), PortalRunPlanner.COBBLE_TARGET), Math.min(s.coal(), 8), Math.min(s.rawIron() + s.ironIngots(), 24),
                s.ironIngots(), s.diamonds(), s.obsidian(), s.flint(), s.flintAndSteel(), s.emptyBuckets() + s.waterBuckets()};
        boolean better = false;
        for (int i = 0; i < values.length; i++) {
            if (bestKnown && values[i] > best[i]) better = true;
            best[i] = Math.max(best[i], values[i]);
        }
        bestKnown = true;
        return better;
    }

    // ------------------------------------------------------------------ the skills

    private Outcome act(Choice choice) {
        return switch (choice.kind()) {
            case "fight" -> fight(choice.mob());
            case "run" -> runFrom(choice.mob(), "native_goto");
            case "cover" -> cover(choice.mob());
            case "retreat" -> retreat(choice.mob());
            case "eat" -> eat();
            case "shelter" -> shelter();
            case "unseal" -> unseal();
            case "seal" -> sealIn();
            case "wait" -> waitForDaylight();
            case "drops" -> recover();
            default -> step(choice.step());
        };
    }

    private Outcome step(PortalRunPlanner.Step step) {
        var outcome = switch (step.action()) {
            case GATHER_LOGS, CUT_TREES -> mineNearest(WorldFacts.Source.TREE, "native_gather");
            case CRAFT -> craft(step.item());
            case PLACE_TABLE -> placeStation("crafting_table");
            case PLACE_FURNACE -> placeStation("furnace");
            case GATHER_STONE -> gatherStone();
            case MINE_COAL -> mineNearest(WorldFacts.Source.COAL_ORE, "native_break");
            case MINE_IRON -> mineNearest(WorldFacts.Source.IRON_ORE, "native_break");
            case MINE_DIAMOND -> mineNearest(WorldFacts.Source.DIAMOND_ORE, "native_break");
            case GATHER_FLINT -> mineNearest(WorldFacts.Source.GRAVEL, "native_break");
            case SMELT -> smelt(step.item());
            case AWAIT_SMELT -> awaitSmelt();
            case COLLECT_SMELTED -> collect();
            case FILL_WATER -> fill(WorldFacts.Source.WATER, "water_bucket");
            case FILL_LAVA -> fill(WorldFacts.Source.LAVA, "lava_bucket");
            case MAKE_OBSIDIAN -> makeObsidian();
            case CAST_PORTAL -> castPortal();
            case BUILD_PORTAL -> buildPortal();
            case IGNITE_PORTAL -> ignite();
            case HUNT, SEEK_FOOD -> hunt();
            case COOK -> cook();
            default -> Outcome.blocked("native_wait", "the simulator has no skill for " + step.action());
        };
        // A step refused as it was planned, asked for again and again with nothing new seen: the planner's, not the body's.
        String key = step.action() + ":" + step.item();
        if (outcome.state().equals("BLOCKED")) {
            int runs = refusedRuns.merge(key, 1, Integer::sum);
            if (runs == 20) anomalies.add("asked " + runs + " times running for " + key + " at " + world.tick + " ticks: " + outcome.reason()
                    + "; holding " + new java.util.TreeMap<>(items) + ", pickaxe tier " + pickaxeTier + ", seen " + seen.keySet());
        } else {
            refusedRuns.remove(key);
        }
        return outcome;
    }

    /** Walks to the nearest seen block of {@code source}, digging its way when it is under ground, and breaks it. */
    private Outcome mineNearest(WorldFacts.Source source, String skill) {
        int[] at = seen.get(source);
        if (at == null) {
            // Nothing seen: a look round, as the campaign's search does, on open ground or along a tunnel.
            return explore(source, skill);
        }
        var block = world.at(at[0], at[1], at[2]);
        int tier = switch (block) {
            case IRON_ORE -> 2;
            case DIAMOND_ORE -> 3;
            case COAL_ORE, STONE, GRAVEL -> block == Block.GRAVEL ? 0 : 1;
            default -> 0;
        };
        if (pickaxeTier < tier) return Outcome.blocked(skill, "its pickaxe cannot mine " + block.name().toLowerCase(java.util.Locale.ROOT));
        var walked = reach(at[0], at[1], at[2], skill);
        if (walked != null) return walked;
        var broke = breakBlock(at[0], at[1], at[2], skill);
        if (broke != null) return broke;
        return Outcome.done(skill);
    }

    /**
     * A look for {@code source} a way off: below ground, a cave it has seen first, as the campaign explores one (caves
     * show far more ore than a tunnel); otherwise twenty blocks out on open ground, or a tunnel's worth below.
     */
    private Outcome explore(WorldFacts.Source source, String skill) {
        boolean below = source != WorldFacts.Source.TREE && source != WorldFacts.Source.WATER;
        if (below && pickaxeTier == 0) return Outcome.blocked(skill, "nothing seen, and nothing to dig with");
        if (below) {
            var cave = caveToward(target(source));
            if (cave != null) {
                visitedCaves.add(((long) (cave[0] >> 2) << 40) ^ ((long) ((cave[1] >> 2) & 0xFFFFF) << 20) ^ (cave[2] >> 2));
                var walked = walkTo(cave[0], cave[1], cave[2], "native_goto");
                return walked != null ? walked : Outcome.done("native_goto");
            }
        }
        int nx = Math.clamp(x + random.nextInt(-20, 21), 2, SimWorld.SIZE - 3), nz = Math.clamp(z + random.nextInt(-20, 21), 2, SimWorld.SIZE - 3);
        // Below ground, the campaign's dig down and tunnel toward the height the ore is likeliest at; over it, its
        // explore, a walk as far toward the place as the way goes.
        var walked = below ? tunnelTo(nx, Math.max(SimWorld.MIN_Y + 6, target(source)), nz, "native_goto")
                : walkTo(nx, ground(nx, nz), nz, "native_goto", WALK, Way.PARTIAL, 3);
        return walked != null ? walked : Outcome.done("native_goto");
    }

    /** Night as the campaign reckons it: vanilla's, and from dusk once it has learned to shelter early. */
    private boolean night() {
        long t = world.timeOfDay();
        return world.night() || habits.contains(PlanPreferences.Habit.SHELTER_EARLY) && t >= 11_500 && t < 13_000;
    }

    /**
     * Below the ground with no open sky, as the campaign reckons it: a shaft or a cave, more than two blocks under the
     * ground's top (leaves are no cover), not a forest floor.
     */
    private boolean underground() {
        return !world.skyOpen(x, y + 1, z) && y < ground(x, z) - 2;
    }

    /**
     * Down to whatever holds it up, as a body falls: through open cells to the first block that is not open, water
     * breaking the fall; past three blocks, vanilla's fall damage, a point a block.
     */
    private void settle() {
        int fell = 0;
        while (y > SimWorld.MIN_Y + 1 && world.at(x, y - 1, z).open()) {
            y--;
            fell++;
        }
        if (fell > 3 && world.at(x, y - 1, z) != Block.WATER && world.at(x, y, z) != Block.WATER) hurt(fell - 3, "fell from a high place");
    }

    /**
     * A walk's end on the floor under it: the way there is the navigator's, which takes no drop of more than three
     * blocks, so a straight line through a cave's air ends on the cave's floor, the way down paid for in time.
     */
    private void land() {
        int down = 0;
        while (y > SimWorld.MIN_Y + 1 && world.at(x, y - 1, z).open()) {
            y--;
            down++;
        }
        if (down > 0) pass(2L * down, false);
    }

    /** Where a walk on open ground ends in a column: on the ground, under any tree's crown. */
    private int ground(int cx, int cz) {
        int t = world.top(cx, cz);
        while (t > SimWorld.MIN_Y && (world.at(cx, t, cz) == Block.LEAVES || world.at(cx, t, cz) == Block.LOG)) t--;
        return t + 1;
    }

    /**
     * The campaign's choice of cave to explore toward {@code targetY}: only with a shield, or with no mob within
     * sixteen blocks to be heard; not hurt below fourteen, nor keeping out of caves. A cave cell it saw, about eight
     * blocks on and near the height wanted, not one it has been in, and in its sight; null for none.
     */
    private int[] caveToward(int targetY) {
        if (caves.isEmpty() || health < 14 || habits.contains(PlanPreferences.Habit.NO_CAVES)) return null;
        if (count("shield") == 0 && nearestMob(16) != null) return null;
        int gapNow = Math.abs(y - targetY);
        var ranked = new ArrayList<int[]>();
        for (int[] cell : caves) {
            if (visitedCaves.contains(((long) (cell[0] >> 2) << 40) ^ ((long) ((cell[1] >> 2) & 0xFFFFF) << 20) ^ (cell[2] >> 2))) continue;
            if (sq(cell[0] - x) + sq(cell[1] - y) + sq(cell[2] - z) < 4 || Math.abs(cell[1] - targetY) >= gapNow + 8) continue;
            ranked.add(cell);
        }
        ranked.sort(java.util.Comparator.comparingDouble(cell -> Math.abs(Math.hypot(cell[0] - x, cell[2] - z) - 8) + 1.5 * Math.abs(cell[1] - targetY)));
        for (int i = 0; i < Math.min(8, ranked.size()); i++) {
            int[] cell = ranked.get(i);
            if (clearLine(x + 0.5, y + 1.62, z + 0.5, cell[0] + 0.5, cell[1] + 0.5, cell[2] + 0.5)) return cell;
        }
        return null;
    }

    private Mob nearestMob(double within) {
        Mob best = null;
        for (Mob mob : mobs) if (distance(mob) <= within && (best == null || distance(mob) < distance(best))) best = mob;
        return best;
    }

    /** Nothing solid on the straight line between two points. */
    private boolean clearLine(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Block b = world.at((int) Math.floor(ax + dx * t), (int) Math.floor(ay + dy * t), (int) Math.floor(az + dz * t));
            if (!b.open() && b != Block.WATER) return false;
        }
        return true;
    }

    /** Where it digs to for an ore it has not seen: the height the atlas's priors weigh most. */
    private int target(WorldFacts.Source source) {
        var resource = switch (source) {
            case DIAMOND_ORE -> Atlas.Resource.DIAMOND;
            case IRON_ORE -> Atlas.Resource.IRON;
            case COAL_ORE -> Atlas.Resource.COAL;
            case LAVA -> Atlas.Resource.LAVA;
            default -> null;
        };
        if (resource == null) return y - 4;
        return atlas.priors().bestHeight(resource, "overworld").orElse(y - 4);
    }

    /**
     * Stone as the campaign gathers it: what is in view within six blocks first, then dug for under its feet, three
     * blocks down; with neither (it stands on leaves, or in water), refused, as a dig with nothing to dig is.
     */
    private Outcome gatherStone() {
        if (pickaxeTier == 0) return Outcome.blocked("native_break", "stone wants a pickaxe");
        int[] stone = seen.get(WorldFacts.Source.STONE);
        if (stone != null && world.at(stone[0], stone[1], stone[2]) == Block.STONE && sq(stone[0] - x) + sq(stone[1] - y) + sq(stone[2] - z) <= 36
                && !(stone[0] == x && stone[2] == z && stone[1] == y - 1)) {
            return mineNearest(WorldFacts.Source.STONE, "native_break");
        }
        if (!diggable(world.at(x, y - 1, z))) return Outcome.blocked("native_break", "no stone in reach and nothing to dig under it");
        for (int i = 0; i < 3 && diggable(world.at(x, y - 1, z)); i++) {
            var broke = breakBlock(x, y - 1, z, "native_break");
            if (broke != null) return broke;
            y--;
        }
        return Outcome.done("native_break");
    }

    /** Ground a dig for stone goes down through: stone, ores, dirt, gravel, sand. */
    private static boolean diggable(Block b) {
        return b == Block.STONE || b == Block.COBBLE || b == Block.DIRT || b == Block.GRASS || b == Block.GRAVEL || b == Block.SAND
                || b == Block.COAL_ORE || b == Block.IRON_ORE || b == Block.DIAMOND_ORE;
    }

    /**
     * The walk to where it can reach the block at {@code tx, ty, tz}, as a player mines or fills from beside a block: an
     * open cell beside it (or below it, for a log) with ground to stand on and the block within four and a half blocks of
     * its eyes, the nearest to it; with none, the cell beside it on its own side, a way dug to it. A walk if there is one,
     * else a made way, as the campaign's route (F-35d). Null when it got there.
     */
    private Outcome reach(int tx, int ty, int tz, String skill) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int sy = ty + 1; sy >= ty - 5; sy--) {
                int sx = tx + side[0], sz = tz + side[1];
                if (!world.at(sx, sy, sz).open() || !world.at(sx, sy + 1, sz).open() || !world.at(sx, sy - 1, sz).solid()) continue;
                if (Math.abs(sy + 1.62 - (ty + 0.5)) > 4.5) continue;
                double d = sq(sx - x) + sq(sy - y) + sq(sz - z);
                if (d < bestD) {
                    bestD = d;
                    best = new int[] {sx, sy, sz};
                }
                break;
            }
        }
        if (best == null) {
            // Nothing open beside it: the cell beside it on its own side, dug to.
            int dx = Integer.signum(x - tx), dz = dx == 0 ? (z >= tz ? 1 : -1) : 0;
            best = new int[] {tx + dx, ty, tz + dz};
        }
        return walkTo(best[0], best[1], best[2], skill, WALK, Way.MAKE, RANGE);
    }

    private Outcome walkTo(int tx, int ty, int tz, String skill) { return walkTo(tx, ty, tz, skill, WALK); }

    private Outcome walkTo(int tx, int ty, int tz, String skill, double pace) { return walkTo(tx, ty, tz, skill, pace, Way.WALK, RANGE); }

    /** Ticks a block, walking and sprinting: vanilla's 4.317 and 5.612 blocks a second. */
    private static final double WALK = 4.63, SPRINT = 3.56;
    /** The adapter's goto: its default range, the navigator's node budget, and the goto's search radius. */
    private static final double RANGE = 1.5;
    private static final int WALK_NODES = Navigator.DEFAULT_MAX_NODES, WALK_RADIUS = 96;
    /** The campaign's made way ({@code buildRoute}): its node budget ({@code ROUTE_NODES}) and its search radius. */
    private static final int ROUTE_NODES = 2_500, ROUTE_RADIUS = 48;
    /** Ticks to put a block down on a made way, as a placing takes the adapter. */
    private static final int PLACE_TICKS = 5;

    /**
     * How a walk may go, as the adapter's callers ask: a walk ({@code native_goto}); a walk that, finding no whole way,
     * goes as far as the navigator's way toward it does ({@code partial}, as the campaign explores and moves elsewhere);
     * or a walk that, finding none, makes a way, digging, bridging, pillaring or building stairs (the campaign's
     * {@code buildRoute}, with edits on and the blocks it holds).
     */
    private enum Way { WALK, PARTIAL, MAKE }

    /** One leg of a way: where the body ends, the cells it digs and places first, and the ticks it all takes. */
    private record Leg(Cell to, List<Cell> dig, List<Cell> place, long ticks) { }

    /**
     * The walk to within {@code range} of {@code tx, ty, tz}, planned by the core's own planners over this world as the
     * adapter plans over the live one (F-35d): {@link Navigator#find} for the walk, with the goto's budget and radius;
     * and with {@link Way#MAKE}, where that finds none, {@link RoutePlanner#find} with the campaign's. Paid for in time
     * as it goes, a leg at a time: four and a third blocks a second, two ticks a block up or down, each block dug by the
     * pickaxe it has, and each placed from its dirt or cobblestone. Mobs and hunger go on meanwhile; a hit cuts it short
     * where it got to. No way, or one that does not get there, is the goto's refusal. Null when it got there.
     */
    private Outcome walkTo(int tx, int ty, int tz, String skill, double pace, Way way, double range) {
        Cell from = new Cell(x, y, z), target = new Cell(tx, ty, tz);
        java.util.function.Predicate<Cell> goal = cell -> Math.sqrt(cell.squaredDistance(target)) <= range + 0.01;
        if (goal.test(from)) return null;
        var navigator = new Navigator(world);
        var path = navigator.find(from, target, goal, WALK_NODES, WALK_RADIUS);
        List<Leg> legs = null;
        if (path.complete() || way == Way.PARTIAL && path.nodes().size() >= 2) legs = walked(from, path.nodes(), pace);
        if (legs == null && way == Way.MAKE) {
            var route = new RoutePlanner(navigator, allowance()).find(from, target, goal, ROUTE_NODES, ROUTE_RADIUS);
            if (route.complete() && !route.steps().isEmpty()) legs = made(from, route.steps(), pace);
        }
        if (legs == null) return Outcome.blocked(skill, "No walkable route toward " + tx + ", " + ty + ", " + tz);
        return follow(legs, target, skill);
    }

    /** What the campaign lets a made way do: dig what its tools harvest with no liquid by it, and place its dirt and cobblestone. */
    private RoutePlanner.Allowance allowance() {
        return new RoutePlanner.Allowance(cell -> harvestable(world.at(cell.x(), cell.y(), cell.z())) && !liquidNear(cell.x(), cell.y(), cell.z()),
                cell -> false, count("dirt") + count("cobblestone"));
    }

    /** A block its tools can harvest, as the adapter's {@code bestTool} finds one: a pickaxe of the tier it takes for rock and ore. */
    private boolean harvestable(Block b) {
        int tier = switch (b) {
            case STONE, COBBLE, COAL_ORE, FURNACE -> 1;
            case IRON_ORE -> 2;
            case DIAMOND_ORE -> 3;
            case OBSIDIAN -> 4;
            default -> 0;
        };
        return pickaxeTier >= tier;
    }

    /**
     * As the campaign's {@code liquidNear}: lava on any side or in it, or water above or beside, which would pour into the
     * opening; or a liquid beside a column of sand or gravel resting on it, which comes down once the block is dug.
     */
    private boolean liquidNear(int cx, int cy, int cz) {
        if (world.at(cx, cy, cz) == Block.LAVA) return true;
        for (int[] d : SIX) {
            Block b = world.at(cx + d[0], cy + d[1], cz + d[2]);
            if (b == Block.LAVA || d[1] != -1 && b == Block.WATER) return true;
        }
        for (int up = 1; up <= 16; up++) {
            Block b = world.at(cx, cy + up, cz);
            if (b != Block.SAND && b != Block.GRAVEL) break;
            for (int[] d : SIX) {
                if (d[1] == -1) continue;
                Block beside = world.at(cx + d[0], cy + up + d[1], cz + d[2]);
                if (beside == Block.WATER || beside == Block.LAVA) return true;
            }
        }
        return false;
    }

    private static final int[][] SIX = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    /** The navigator's way as legs: each cell walked to in turn. */
    private List<Leg> walked(Cell from, List<Cell> nodes, double pace) {
        var legs = new ArrayList<Leg>(nodes.size());
        Cell at = from;
        for (Cell node : nodes) {
            if (node.equals(at)) continue;
            legs.add(new Leg(node, List.of(), List.of(), moveTicks(at, node, pace)));
            at = node;
        }
        return legs;
    }

    /** The planner's route as legs: a walk, a dig of the cells in the way, or a block placed under or ahead, then the step. */
    private List<Leg> made(Cell from, List<RoutePlanner.Step> steps, double pace) {
        var legs = new ArrayList<Leg>(steps.size());
        Cell at = from;
        for (var step : steps) {
            long ticks = moveTicks(at, step.to(), pace);
            if (step.kind() == RoutePlanner.Kind.DIG) {
                for (Cell cell : step.cells()) ticks += breakTicks(world.at(cell.x(), cell.y(), cell.z()));
                legs.add(new Leg(step.to(), step.cells(), List.of(), ticks));
            } else if (step.kind() == RoutePlanner.Kind.WALK) {
                legs.add(new Leg(step.to(), List.of(), List.of(), ticks));
            } else {
                legs.add(new Leg(step.to(), List.of(), step.cells(), ticks + (long) PLACE_TICKS * step.cells().size()));
            }
            at = step.to();
        }
        return legs;
    }

    /** A step's time: the walking pace a block across (a diagonal's 1.41), and two ticks a block up or down. */
    private static long moveTicks(Cell a, Cell b, double pace) {
        double across = Math.sqrt(sq(b.x() - a.x()) + sq(b.z() - a.z()));
        return (long) Math.ceil(across * pace) + 2L * Math.abs(b.y() - a.y());
    }

    /**
     * The legs walked as the time goes, each done once its share of the time has gone by: its cells dug (each block's
     * drop in hand) or its blocks placed (dirt first, then cobblestone), then the body where the leg ends, so that what
     * comes at it meets it where it has got to. A block it no longer has to place stops the way there.
     */
    private Outcome follow(List<Leg> legs, Cell target, String skill) {
        long[] ends = new long[legs.size()];
        long total = 0;
        for (int i = 0; i < legs.size(); i++) ends[i] = total += legs.get(i).ticks();
        int[] next = {0};
        boolean[] short_ = {false};
        long all = total;
        Along along = share -> {
            double elapsed = share * all;
            while (!short_[0] && next[0] < legs.size() && ends[next[0]] <= elapsed + 1e-9) {
                if (!carryOut(legs.get(next[0]))) short_[0] = true;
                else next[0]++;
            }
        };
        long done = all == 0 ? 0 : pass(all, true, along);
        if (done >= all) along.at(1);
        if (short_[0]) {
            land();
            return Outcome.blocked(skill, "Route to " + target.x() + ", " + target.y() + ", " + target.z() + " is blocked");
        }
        land();
        if (next[0] < legs.size()) return Outcome.cut(skill, hissingCreeper() != null ? "A creeper is about to explode" : "A hostile mob is attacking; fight back first");
        return null;
    }

    /** One leg done: its cells dug, its blocks placed, and the body where it ends; false with no block left to place. */
    private boolean carryOut(Leg leg) {
        for (Cell cell : leg.dig()) {
            Block b = world.at(cell.x(), cell.y(), cell.z());
            if (!b.open() && b != Block.WATER && b != Block.LAVA && b != Block.BEDROCK) dug(b, cell.x(), cell.y(), cell.z());
        }
        for (Cell cell : leg.place()) {
            String block = count("dirt") > 0 ? "dirt" : count("cobblestone") > 0 ? "cobblestone" : null;
            if (block == null) return false;
            take(block, 1);
            world.set(cell.x(), cell.y(), cell.z(), block.equals("dirt") ? Block.DIRT : Block.COBBLE);
        }
        x = leg.to().x();
        y = leg.to().y();
        z = leg.to().z();
        return true;
    }

    /**
     * A tunnel to {@code tx, ty, tz}, as the campaign digs down or branch mines toward an ore it has not seen: over open
     * ground by the lie of the land, below it on the straight line between the two ends, with every block of rock on that
     * line dug out two high, a block at a time by the pickaxe it has. Water, lava and bedrock on the line are left be and
     * walked through: the campaign's own tunnel, which turns from liquid, is the adapter's, not the core's. Null when it
     * got there.
     */
    private Outcome tunnelTo(int tx, int ty, int tz, String skill) {
        int sx = x, sy = y, sz = z;
        double pace = WALK;
        double flat = Math.sqrt(sq(tx - sx) + sq(tz - sz));
        boolean overGround = sy >= ground(sx, sz) && ty >= ground(tx, tz);
        int dig = 0;
        long digTicks = 0;
        if (!overGround) {
            for (int[] cell : line(sx, sy, sz, tx, ty, tz)) {
                Block b = world.at(cell[0], cell[1], cell[2]);
                if (b.open() || b == Block.WATER || b == Block.LAVA || b == Block.BEDROCK) continue;
                dig++;
                digTicks += breakTicks(b);
            }
        }
        if (dig > 0 && pickaxeTier == 0 && digTicks > 2_000) return Outcome.blocked(skill, "No walkable route toward " + tx + ", " + ty + ", " + tz);
        double along3d = overGround ? flat : Math.sqrt(sq(tx - sx) + sq(ty - sy) + sq(tz - sz));
        long ticks = (long) Math.ceil(along3d * pace) + digTicks + (overGround ? 2L * Math.abs(ty - sy) : 0);
        long done = pass(ticks, true, share -> {
            x = (int) Math.round(sx + (tx - sx) * share);
            z = (int) Math.round(sz + (tz - sz) * share);
            y = overGround ? ground(x, z) : (int) Math.round(sy + (ty - sy) * share);
        });
        double share = ticks == 0 ? 1 : Math.min(1, (double) done / ticks);
        if (share < 1) {
            // Stopped part way: the tunnel as far as it got, the cell it stands in among it.
            if (dig > 0) carve(sx, sy, sz, x, y, z);
            if (!overGround) for (int i = 0; i < 2; i++) {
                Block b = world.at(x, y + i, z);
                if (!b.open() && b != Block.BEDROCK && b != Block.WATER && b != Block.LAVA) dug(b, x, y + i, z);
            }
            land();
            return Outcome.cut(skill, hissingCreeper() != null ? "A creeper is about to explode" : "A hostile mob is attacking; fight back first");
        }
        // The tunnel itself, two high along the way dug, so that the ore in its walls is seen as a branch mine shows it.
        if (dig > 0) carve(sx, sy, sz, tx, ty, tz);
        x = tx;
        y = overGround && !world.at(tx, ty, tz).open() ? ground(tx, tz) : ty;
        z = tz;
        land();
        return null;
    }

    /** The cells of a straight tunnel from one place to another, two high: its feet and its head along the way. */
    private static List<int[]> line(int sx, int sy, int sz, int tx, int ty, int tz) {
        var cells = new ArrayList<int[]>();
        int steps = Math.max(Math.abs(tx - sx), Math.max(Math.abs(ty - sy), Math.abs(tz - sz)));
        for (int i = 1; i <= steps; i++) {
            int cx = sx + Math.round((tx - sx) * (float) i / steps), cy = sy + Math.round((ty - sy) * (float) i / steps),
                    cz = sz + Math.round((tz - sz) * (float) i / steps);
            cells.add(new int[] {cx, cy, cz});
            cells.add(new int[] {cx, cy + 1, cz});
        }
        return cells;
    }

    /** Digs out a straight tunnel from one place to another, two high, with what each block drops; water, lava and bedrock left be. */
    private void carve(int sx, int sy, int sz, int tx, int ty, int tz) {
        for (int[] cell : line(sx, sy, sz, tx, ty, tz)) {
            Block b = world.at(cell[0], cell[1], cell[2]);
            if (b != Block.WATER && b != Block.LAVA && b != Block.BEDROCK && !b.open()) dug(b, cell[0], cell[1], cell[2]);
        }
    }

    /** A block dug out on the way: gone, its drop in hand as {@link #breakBlock} gives it, and the pickaxe worn. */
    private void dug(Block b, int bx, int by, int bz) {
        world.set(bx, by, bz, Block.AIR);
        drop(b);
    }

    private Outcome breakBlock(int bx, int by, int bz, String skill) {
        Block b = world.at(bx, by, bz);
        if (b.open() || b == Block.BEDROCK) return null;
        long done = pass(breakTicks(b), true);
        if (done < breakTicks(b)) return Outcome.cut(skill, "A hostile mob is attacking; fight back first");
        world.set(bx, by, bz, Block.AIR);
        drop(b);
        return null;
    }

    /** What a block broken gives, by vanilla's drops and the tool it takes; the tool worn a use. */
    private void drop(Block b) {
        switch (b) {
            case LOG -> { give("log", 1); if (axeTier > 0) axeUses--; }
            case STONE, COBBLE -> { if (pickaxeTier > 0) { give("cobblestone", 1); wear(1); } }
            case COAL_ORE -> { give("coal", 1); wear(1); }
            case IRON_ORE -> { give("raw_iron", 1); wear(1); }
            case DIAMOND_ORE -> { give("diamond", 1); wear(1); }
            case GRAVEL -> give(random.nextInt(10) == 0 ? "flint" : "gravel", 1);
            case OBSIDIAN -> { if (pickaxeTier >= 4) { give("obsidian", 1); wear(1); } }
            case LEAVES -> { if (random.nextInt(20) == 0) give("sapling", 1); }
            case DIRT, GRASS, SAND -> give("dirt", 1);
            default -> { }
        }
        // A player keeps a few stacks of the blocks it digs and leaves the rest, as the campaign's own clear-out does.
        if (count("cobblestone") > 192) items.put("cobblestone", 192);
        if (count("dirt") > 64) items.put("dirt", 64);
    }

    /** Vanilla's break time in ticks: hardness, and the right tool's speed, or five times as long by hand. */
    private long breakTicks(Block b) {
        double hardness = switch (b) {
            case STONE -> 1.5;
            case COBBLE, LOG, PLANKS -> 2;
            case COAL_ORE, IRON_ORE, DIAMOND_ORE -> 3;
            case OBSIDIAN -> 50;
            case DIRT, GRASS, SAND -> 0.5;
            case GRAVEL -> 0.6;
            case LEAVES -> 0.2;
            default -> 1;
        };
        boolean pick = b == Block.STONE || b == Block.COBBLE || b == Block.COAL_ORE || b == Block.IRON_ORE || b == Block.DIAMOND_ORE || b == Block.OBSIDIAN;
        boolean axe = b == Block.LOG || b == Block.PLANKS;
        int tier = pick ? pickaxeTier : axe ? axeTier : 0;
        double speed = switch (tier) { case 1 -> 2; case 2 -> 4; case 3 -> 6; case 4 -> 8; default -> 1; };
        boolean right = !pick || pickaxeTier >= 1 && (b != Block.OBSIDIAN || pickaxeTier >= 4);
        double seconds = right ? hardness * 1.5 / speed : hardness * 5;
        return Math.max(1, Math.round(seconds * 20));
    }

    private void wear(int uses) {
        if (pickaxeTier == 0) return;
        pickaxeUses -= uses;
        if (pickaxeUses <= 0) {
            String worn = pickaxeName(pickaxeTier);
            take(worn, 1);
            log.line(world.tick, name + "'s " + worn + " broke");
            refreshTools();
        }
    }

    private static String pickaxeName(int tier) {
        return switch (tier) { case 1 -> "wooden_pickaxe"; case 2 -> "stone_pickaxe"; case 3 -> "iron_pickaxe"; case 4 -> "diamond_pickaxe"; default -> ""; };
    }

    /** Its best tools from what it holds, with their uses as new ones have them. */
    private void refreshTools() {
        int was = pickaxeTier;
        pickaxeTier = count("diamond_pickaxe") > 0 ? 4 : count("iron_pickaxe") > 0 ? 3 : count("stone_pickaxe") > 0 ? 2 : count("wooden_pickaxe") > 0 ? 1 : 0;
        if (pickaxeTier != was || pickaxeUses <= 0) pickaxeUses = switch (pickaxeTier) { case 1 -> 59; case 2 -> 131; case 3 -> 250; case 4 -> 1561; default -> 0; };
        swordTier = count("iron_sword") > 0 ? 3 : count("stone_sword") > 0 ? 2 : count("wooden_sword") > 0 ? 1 : 0;
        axeTier = count("stone_axe") > 0 ? 2 : count("wooden_axe") > 0 ? 1 : 0;
        chestTier = count("iron_chestplate") > 0 ? 3 : 0;
    }

    /** A recipe: what it takes, how many it makes, and whether it wants a crafting table. */
    private record Recipe(Map<String, Integer> needs, int makes, boolean table) { }

    /** Vanilla's recipe for {@code item}, of those the planner asks for; null for one it never asks for. */
    private static Recipe recipe(String item) {
        return switch (item) {
            case "planks" -> new Recipe(Map.of("log", 1), 4, false);
            case "stick" -> new Recipe(Map.of("planks", 2), 4, false);
            case "crafting_table" -> new Recipe(Map.of("planks", 4), 1, false);
            case "wooden_pickaxe" -> new Recipe(Map.of("planks", 3, "stick", 2), 1, true);
            case "stone_pickaxe" -> new Recipe(Map.of("cobblestone", 3, "stick", 2), 1, true);
            case "stone_sword" -> new Recipe(Map.of("cobblestone", 2, "stick", 1), 1, true);
            case "iron_pickaxe" -> new Recipe(Map.of("iron_ingot", 3, "stick", 2), 1, true);
            case "diamond_pickaxe" -> new Recipe(Map.of("diamond", 3, "stick", 2), 1, true);
            case "furnace" -> new Recipe(Map.of("cobblestone", 8), 1, true);
            case "bucket" -> new Recipe(Map.of("iron_ingot", 3), 1, true);
            case "iron_chestplate" -> new Recipe(Map.of("iron_ingot", 8), 1, true);
            case "shield" -> new Recipe(Map.of("planks", 6, "iron_ingot", 1), 1, true);
            case "flint_and_steel" -> new Recipe(Map.of("iron_ingot", 1, "flint", 1), 1, false);
            default -> null;
        };
    }

    /** Whether it holds what {@code item} takes, with a table placed for one that needs it. */
    private boolean craftable(String item) {
        Recipe recipe = recipe(item);
        if (recipe == null || recipe.table() && tableNear() == null && count("crafting_table") == 0) return false;
        for (var need : recipe.needs().entrySet()) if (count(need.getKey()) < need.getValue()) return false;
        return true;
    }

    /** Crafting from vanilla's recipes for what the planner asks; a table within reach for those that need one. */
    private Outcome craft(String item) {
        Recipe recipe = recipe(item);
        if (recipe == null) return Outcome.blocked("native_craft", "no recipe for " + item);
        // A table it carries is put down first, as the campaign places its own before a craft that wants one.
        if (recipe.table() && tableNear() == null && count("crafting_table") > 0) return placeStation("crafting_table");
        if (recipe.table() && tableNear() == null) return Outcome.blocked("native_craft", "no crafting table within reach");
        for (var need : recipe.needs().entrySet()) {
            if (count(need.getKey()) < need.getValue()) {
                return Outcome.blocked("native_craft", "Missing ingredients for " + item + ": " + need.getKey() + " " + count(need.getKey()) + "/" + need.getValue());
            }
        }
        pass(10, false);
        for (var need : recipe.needs().entrySet()) take(need.getKey(), need.getValue());
        give(item, recipe.makes());
        refreshTools();
        return Outcome.done("native_craft");
    }

    private Outcome placeStation(String station) {
        if (count(station) == 0) return Outcome.blocked("native_place", "no " + station + " to place");
        pass(5, false);
        take(station, 1);
        if (station.equals("crafting_table")) tables.add(new int[] {x, y, z});
        else furnaces.add(new Furnace(x, y, z));
        return Outcome.done("native_place");
    }

    /**
     * Raw iron into its furnace with the fuel to smelt it, as the adapter loads one: what the furnace made taken out
     * first; the best fuel it has (coal, then planks, then logs, sticks last); and only as much iron as that fuel smelts,
     * the rest kept in hand. With the iron in already and its fuel burnt out, fuel only. Ten seconds an item.
     */
    private Outcome smelt(String item) {
        Furnace furnace = furnaceNear();
        if (furnace == null && count("furnace") > 0) return placeStation("furnace");
        if (furnace == null) return Outcome.blocked("native_smelt", "no furnace within reach");
        if (item.equals("log")) return charcoal(furnace);
        if (furnace.outCount > 0) take(furnace);
        if (furnace.inCount > 0 && !furnace.in.equals("raw_iron")) return Outcome.blocked("native_smelt", "the furnace is busy with " + furnace.in);
        int n = count("raw_iron");
        if (n == 0 && (furnace.inCount == 0 || furnace.smelting())) return Outcome.blocked("native_smelt", "nothing to smelt");
        return load(furnace, "raw_iron", n, "native_smelt");
    }

    /** {@code n} of {@code input} in, with the fuel for them and for what waits in it already: as much as the fuel smelts. */
    private Outcome load(Furnace furnace, String input, int n, String skill) {
        String fuel = bestFuel(input);
        if (fuel == null) return Outcome.blocked(skill, "no fuel for " + (n + furnace.inCount) + " " + input);
        int burn = burnTicks(fuel);
        int smeltable = (int) Math.floor(furnace.burn + count(fuel) * (double) burn / 200) - furnace.inCount;
        int loaded = Math.max(0, Math.min(n, smeltable));
        double short_ = furnace.inCount + loaded - furnace.burn;
        if (loaded == 0 && short_ <= 0) return Outcome.blocked(skill, "no fuel for " + (n + furnace.inCount) + " " + input);
        int units = short_ <= 0 ? 0 : Math.min(count(fuel), (int) Math.ceil(short_ * 200 / burn - 1e-9));
        boolean idle = !furnace.smelting();
        take(fuel, units);
        furnace.burn += units * (double) burn / 200;
        take(input, loaded);
        furnace.in = input;
        furnace.inCount += loaded;
        if (idle && furnace.smelting()) furnace.nextAt = world.tick + 200;
        pass(5, false);
        return Outcome.done(skill);
    }

    /** One log in with one plank to burn it: charcoal, as the campaign makes it where there is no coal. */
    private Outcome charcoal(Furnace furnace) {
        if (furnace.inCount > 0) return Outcome.blocked("native_smelt", "the furnace is busy with " + furnace.in);
        if (furnace.outCount > 0 && !furnace.out.equals("charcoal")) take(furnace);
        if (count("log") == 0 || count("planks") == 0) return Outcome.blocked("native_smelt", "no log, or no plank to burn it");
        take("log", 1);
        take("planks", 1);
        furnace.in = "log";
        furnace.inCount = 1;
        furnace.burn += 1.5;
        furnace.nextAt = world.tick + 200;
        pass(5, false);
        return Outcome.done("native_smelt");
    }

    /** The adapter's choice of fuel: coal first, then planks, then logs (not when smelting a log), sticks last. */
    private String bestFuel(String input) {
        if (count("coal") > 0) return "coal";
        if (count("planks") > 0) return "planks";
        if (count("log") > 0 && !input.equals("log")) return "log";
        if (count("stick") > 0) return "stick";
        return null;
    }

    private static int burnTicks(String fuel) {
        return switch (fuel) { case "coal" -> 1_600; case "stick" -> 100; default -> 300; };
    }

    private static String product(String input) {
        return switch (input) { case "raw_iron" -> "iron_ingot"; case "log" -> "charcoal"; default -> "cooked_meat"; };
    }

    /** Every furnace it put down goes on smelting, wherever it is: an item each ten seconds while the fuel lasts. */
    private void furnaceTick() {
        for (Furnace furnace : furnaces) {
            while (furnace.smelting() && world.tick >= furnace.nextAt) {
                String made = product(furnace.in);
                if (furnace.outCount > 0 && !furnace.out.equals(made)) break;
                furnace.out = made;
                furnace.outCount++;
                furnace.inCount--;
                furnace.burn -= 1;
                if (furnace.inCount == 0) furnace.in = "";
                furnace.nextAt += 200;
            }
        }
    }

    private Outcome awaitSmelt() {
        Furnace furnace = furnaceNear();
        long left = furnace == null || !furnace.smelting() ? 20 : furnace.nextAt - world.tick;
        pass(Math.max(20, Math.min(200, left)), true);
        return Outcome.done("native_wait");
    }

    private Outcome collect() {
        Furnace furnace = furnaceNear();
        if (furnace == null || furnace.outCount == 0) return Outcome.blocked("native_smelt", "nothing in the furnace's output");
        take(furnace);
        pass(5, false);
        return Outcome.done("native_smelt");
    }

    /** What the furnace made, into its inventory: charcoal is the coal it burns. */
    private void take(Furnace furnace) {
        give(furnace.out.equals("charcoal") ? "coal" : furnace.out, furnace.outCount);
        furnace.outCount = 0;
        furnace.out = "";
    }

    private Outcome fill(WorldFacts.Source source, String filled) {
        if (count("bucket") == 0) return Outcome.blocked("native_fill", "no empty bucket");
        int[] at = seen.get(source);
        if (at == null) return explore(source, "native_fill");
        var walked = reach(at[0], at[1], at[2], "native_fill");
        if (walked != null) return walked;
        take("bucket", 1);
        give(filled, 1);
        if (source == WorldFacts.Source.LAVA) world.set(at[0], at[1], at[2], Block.AIR);
        return Outcome.done("native_fill");
    }

    /** Water on lava where it lies, and the obsidian mined with a diamond pickaxe: a block a pour. */
    private Outcome makeObsidian() {
        if (count("water_bucket") == 0) return Outcome.blocked("native_obsidian", "no water bucket");
        int[] at = seen.get(WorldFacts.Source.LAVA);
        if (at == null) return explore(WorldFacts.Source.LAVA, "native_obsidian");
        var walked = reach(at[0], at[1], at[2], "native_obsidian");
        if (walked != null) return walked;
        world.set(at[0], at[1], at[2], Block.OBSIDIAN);
        var broke = breakBlock(at[0], at[1], at[2], "native_obsidian");
        return broke != null ? broke : Outcome.done("native_obsidian");
    }

    /**
     * The frame cast in place, a cell a pour: the empty bucket filled at the lava it knows (as the campaign's cast fetches
     * its own), carried back to the frame's site, poured and hardened with the water bucket.
     */
    private Outcome castPortal() {
        if (count("water_bucket") == 0) return Outcome.blocked("native_cast", "no water bucket");
        if (frameAt == null) frameAt = new int[] {x, y, z};
        if (count("lava_bucket") == 0) {
            if (count("bucket") == 0) return Outcome.blocked("native_cast", "no bucket for the lava");
            var filled = fill(WorldFacts.Source.LAVA, "lava_bucket");
            if (!filled.state().equals("COMPLETED") || count("lava_bucket") == 0) return filled;
        }
        var back = walkTo(frameAt[0], frameAt[1], frameAt[2], "native_cast", WALK, Way.MAKE, RANGE);
        if (back != null) return back;
        pass(60, true);
        take("lava_bucket", 1);
        give("bucket", 1);
        give("obsidian", 1);
        if (count("obsidian") >= 10) {
            take("obsidian", 10);
            portalFramed = true;
        }
        return Outcome.done("native_cast");
    }

    private Outcome buildPortal() {
        if (count("obsidian") < 10) return Outcome.blocked("native_place", "obsidian " + count("obsidian") + "/10");
        pass(200, true);
        take("obsidian", 10);
        portalFramed = true;
        return Outcome.done("native_place");
    }

    private Outcome ignite() {
        if (!portalFramed) return Outcome.blocked("native_ignite_portal", "no frame");
        if (count("flint_and_steel") == 0) return Outcome.blocked("native_ignite_portal", "no flint and steel");
        pass(10, false);
        portalLit = true;
        log.line(world.tick, name + " built and lit a Nether portal");
        return Outcome.done("native_ignite_portal");
    }

    /** The nearest animal in sight, walked to and killed: one to three raw meat. Without one, a look about on open ground. */
    private Outcome hunt() {
        int[] animal = animalInSight();
        if (animal == null) return explore(WorldFacts.Source.TREE, "native_hunt");
        var walked = walkTo(animal[0], animal[1], animal[2], "native_hunt");
        if (walked != null) return walked;
        pass(30, true);
        animals.remove(animal);
        give("raw_meat", 1 + random.nextInt(3));
        return Outcome.done("native_hunt");
    }

    private int[] animalInSight() {
        int[] best = null;
        for (int[] a : animals) {
            double d = sq(a[0] - x) + sq(a[2] - z);
            if (d < 24 * 24 && !underground() && (best == null || d < sq(best[0] - x) + sq(best[2] - z))) best = a;
        }
        return best;
    }

    /**
     * Whether the campaign could act on {@code step} now, as its {@code realize} answers: a hunt only with an animal in
     * sight, a craft only with what it takes and a table for those that need one, the furnace's steps only with one
     * placed, a fill or the obsidian only with the bucket for it. The rest it can always set about.
     */
    private boolean realizable(PortalRunPlanner.Step step) {
        return switch (step.action()) {
            case HUNT -> animalInSight() != null;
            case CRAFT -> craftable(step.item());
            case SMELT, COOK -> furnaceNear() != null || count("furnace") > 0;
            case AWAIT_SMELT, COLLECT_SMELTED -> furnaceNear() != null;
            case PLACE_TABLE -> count("crafting_table") > 0;
            case PLACE_FURNACE -> count("furnace") > 0;
            case FILL_WATER, FILL_LAVA -> count("bucket") > 0;
            case MAKE_OBSIDIAN -> count("water_bucket") > 0;
            case IGNITE_PORTAL -> portalFramed && count("flint_and_steel") > 0;
            default -> true;
        };
    }

    /** Its raw meat into its furnace with the fuel for it, as the campaign cooks: what the furnace made taken out first. */
    private Outcome cook() {
        Furnace furnace = furnaceNear();
        if (furnace == null && count("furnace") > 0) return placeStation("furnace");
        if (furnace == null) return Outcome.blocked("native_smelt", "no furnace within reach");
        if (furnace.outCount > 0) take(furnace);
        if (furnace.inCount > 0) return Outcome.blocked("native_smelt", "the furnace is busy with " + furnace.in);
        int n = count("raw_meat");
        if (n == 0) return Outcome.blocked("native_smelt", "nothing to cook");
        return load(furnace, "raw_meat", n, "native_smelt");
    }

    private Outcome eat() {
        // As vanilla feeds: cooked meat 8 and 12.8, bread 5 and 6, raw beef or pork 3 and 1.8.
        String what = count("cooked_meat") > 0 ? "cooked_meat" : count("bread") > 0 ? "bread" : "raw_meat";
        if (count(what) == 0) return Outcome.blocked("native_eat", "no food");
        pass(32, false);
        take(what, 1);
        food = Math.min(20, food + switch (what) { case "cooked_meat" -> 8; case "bread" -> 5; default -> 3; });
        saturation = Math.min(food, saturation + switch (what) { case "cooked_meat" -> 12.8f; case "bread" -> 6f; default -> 1.8f; });
        return Outcome.done("native_eat");
    }

    /** Two blocks down and sealed with a block it carries, as the night's dig-in; dug in, no mob reaches it. */
    private Outcome shelter() {
        // Two blocks down where the ground is diggable, and a block from those dug put over its head.
        int depth = diggable(world.at(x, y - 1, z)) ? diggable(world.at(x, y - 2, z)) ? 2 : 1 : 0;
        if (depth == 0) return Outcome.blocked("native_break", "nothing to dig in to here: " + world.at(x, y - 1, z).name().toLowerCase(java.util.Locale.ROOT) + " underfoot");
        long dig = breakTicks(world.at(x, y - 1, z)) + (depth == 2 ? breakTicks(world.at(x, y - 2, z)) : 0);
        if (pass(dig + 10, true) < dig + 10) return Outcome.cut("native_break", "A hostile mob is attacking; fight back first");
        for (int i = 1; i <= depth; i++) dug(world.at(x, y - i, z), x, y - i, z);
        shelterY = y;
        y -= depth;
        String seal = count("dirt") > 0 ? "dirt" : count("cobblestone") > 0 ? "cobblestone" : null;
        if (seal != null && world.at(x, y + 2, z).open()) {
            take(seal, 1);
            world.set(x, y + 2, z, seal.equals("dirt") ? Block.DIRT : Block.COBBLE);
        }
        sheltered = true;
        return Outcome.done("native_place");
    }

    /** Below ground at night with logs next: the way in closed with a block, and the night waited out where it is. */
    private Outcome sealIn() {
        String block = count("dirt") > 0 ? "dirt" : "cobblestone";
        if (count(block) == 0) return Outcome.blocked("native_place", "nothing to seal the way in with");
        pass(10, false);
        take(block, 1);
        sheltered = true;
        sealedInPlace = true;
        return Outcome.done("native_place");
    }

    /** As the campaign waits: four seconds, looking about. */
    private Outcome waitForDaylight() {
        pass(80, true);
        return Outcome.done("native_wait");
    }

    /** Its seal broken and out of the hole it dug, onto the ground beside it, a step cut in the side to climb by. */
    private Outcome unseal() {
        pass(20, false);
        sheltered = false;
        if (sealedInPlace) {
            // The way in it closed below ground, opened again.
            sealedInPlace = false;
            give("cobblestone", 1);
            return Outcome.done("native_break");
        }
        Block seal = world.at(x, y + 2, z);
        if (seal == Block.DIRT || seal == Block.COBBLE) breakBlock(x, y + 2, z, "native_break");
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int ny = standAt(x + side[0], shelterY, z + side[1]);
            if (ny == Integer.MIN_VALUE) continue;
            pass(20, false);
            x += side[0];
            y = ny;
            z += side[1];
            break;
        }
        return Outcome.done("native_break");
    }

    private Outcome recover() {
        var walked = walkTo(dropX, dropY, dropZ, "native_goto");
        if (walked != null) return walked;
        for (var each : drops.entrySet()) give(each.getKey(), each.getValue());
        drops = Map.of();
        refreshTools();
        return Outcome.done("native_goto");
    }

    // ------------------------------------------------------------------ fights, mobs and time

    /** The nearest mob within {@code within} blocks that is not a creeper: those are the campaign's own work. */
    private Mob nearestOther(double within) {
        Mob best = null;
        for (Mob mob : mobs) {
            // One left alone is still fought once it is in reach, as the runtime fights back whatever is hitting it.
            boolean leftAlone = mob.ignoredUntil > world.tick && distance(mob) > 3;
            if (!mob.kind.equals("creeper") && !leftAlone && distance(mob) <= within && (best == null || distance(mob) < distance(best))) best = mob;
        }
        return best;
    }

    /** A creeper after it within eight blocks across and four up or down, in sight, as the campaign looks for one. */
    private Mob creeperAfter() {
        Mob best = null;
        for (Mob mob : mobs) {
            if (!mob.kind.equals("creeper") || Math.abs(mob.x - (x + 0.5)) > 8.3 || Math.abs(mob.z - (z + 0.5)) > 8.3 || Math.abs(mob.y - y) > 4) continue;
            if (mob.ignoredUntil > world.tick && distance(mob) >= 5) continue;
            if (sees(mob) && (best == null || distance(mob) < distance(best))) best = mob;
        }
        return best;
    }

    private double distance(Mob mob) { return Math.sqrt(sq(mob.x - (x + 0.5)) + sq(mob.y - y) + sq(mob.z - (z + 0.5))); }

    /**
     * A fight as the adapter's: a swing as the cooldown allows while in reach, a step closer when not, until it dies or
     * fifteen seconds are up. A creeper is fought hit and run, a step back after each blow; any creeper that starts to
     * hiss close by is run from, or faced behind a raised shield, which stops the blast; a skeleton is closed in on
     * behind the shield. A foe it could not reach is left alone for thirty seconds, as the campaign leaves one.
     */
    private Outcome fight(Mob mob) {
        double damage = switch (swordTier) { case 1 -> 4; case 2 -> 5; case 3 -> 6; default -> pickaxeTier > 0 ? 2 + pickaxeTier : 1; };
        boolean creeper = mob.kind.equals("creeper"), shield = count("shield") > 0;
        try {
            for (int swing = 0; swing < 25 && mobs.contains(mob) && health > 0; swing++) {
                Mob hissing = hissingCreeper();
                if (hissing != null && !shield) {
                    // Run; with nowhere to run, wall it off; and with nothing to wall it off with, knock it back.
                    var ran = runFrom(hissing, "native_fight");
                    if (!ran.state().equals("BLOCKED")) return ran;
                    var walled = cover(hissing);
                    if (walled.state().equals("COMPLETED")) return Outcome.done("native_fight");
                }
                guarding = hissing != null ? hissing : shield && (creeper || mob.kind.equals("skeleton")) ? mob : null;
                pass(12, false);
                if (!mobs.contains(mob)) break;
                if (distance(mob) > 2.8) approach(mob);
                if (distance(mob) <= 2.8 && sees(mob)) {
                    mob.health -= damage;
                    if (mob.health <= 0) {
                        mobs.remove(mob);
                        break;
                    }
                    push(mob, 1);
                    if (creeper && !shield) stepAway(mob, 1);
                }
            }
        } finally {
            guarding = null;
        }
        if (mob.exploded) return Outcome.cut("native_fight", "the creeper blew up");
        if (!mobs.contains(mob)) return Outcome.done("native_fight");
        mob.ignoredUntil = world.tick + 600;
        return Outcome.cut("native_fight", "the fight went on too long");
    }

    /** Whether a raised shield stops what {@code mob} sends at it: the shield faces the mob it guards, and stops what comes from in front. */
    private boolean shielded(Mob mob) {
        if (guarding == null) return false;
        double ax = mob.x - (x + 0.5), az = mob.z - (z + 0.5), gx = guarding.x - (x + 0.5), gz = guarding.z - (z + 0.5);
        return ax * gx + az * gz > 0;
    }

    /** The nearest creeper within seven and a half blocks whose fuse is burning, as the fight watches for one. */
    private Mob hissingCreeper() {
        Mob best = null;
        for (Mob mob : mobs) if (mob.kind.equals("creeper") && mob.fuse >= 0 && distance(mob) < 7.5 && (best == null || distance(mob) < distance(best))) best = mob;
        return best;
    }

    /** Two blocks toward it through open cells, as the chase closes in. */
    private void approach(Mob mob) {
        double d = Math.max(1e-3, distance(mob));
        for (int i = 0; i < 2; i++) {
            int nx = (int) Math.floor(x + 0.5 + (mob.x - (x + 0.5)) / d), nz = (int) Math.floor(z + 0.5 + (mob.z - (z + 0.5)) / d);
            int ny = standAt(nx, y, nz);
            if (ny == Integer.MIN_VALUE) return;
            x = nx;
            y = ny;
            z = nz;
            d = Math.max(1e-3, distance(mob));
            if (d <= 2.8) return;
        }
    }

    /** A step back from it through open cells; none where none is open. */
    private void stepAway(Mob mob, int blocks) {
        double ax = x + 0.5 - mob.x, az = z + 0.5 - mob.z, len = Math.max(1e-3, Math.hypot(ax, az));
        for (int i = 0; i < blocks; i++) {
            int nx = (int) Math.floor(x + 0.5 + ax / len), nz = (int) Math.floor(z + 0.5 + az / len);
            int ny = standAt(nx, y, nz);
            if (ny == Integer.MIN_VALUE) return;
            x = nx;
            y = ny;
            z = nz;
        }
    }

    /** A blow's knockback: the mob a block back from it, where it fits. */
    private void push(Mob mob, double blocks) {
        double ax = mob.x - (x + 0.5), az = mob.z - (z + 0.5), len = Math.max(1e-3, Math.hypot(ax, az));
        double nx = mob.x + ax / len * blocks, nz = mob.z + az / len * blocks;
        if (fits((int) Math.floor(nx), (int) Math.floor(mob.y), (int) Math.floor(nz), !mob.kind.equals("spider"))) {
            mob.x = nx;
            mob.z = nz;
        }
    }

    /**
     * A sprint a dozen blocks straight away from a creeper, as far as open ground goes, as the campaign runs from one:
     * beyond seven its fuse goes out. Under four blocks of way, the run is no good, and not tried again for ten seconds.
     */
    private Outcome runFrom(Mob mob, String skill) {
        double away = Math.atan2(z + 0.5 - mob.z, x + 0.5 - mob.x);
        // Straight away first; where that is closed, the way that turns least from it, as the navigator's path bends.
        int bx = x, by = y, bz = z, got = 0;
        for (double turn : new double[] {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
            double ax = Math.cos(away + turn), az = Math.sin(away + turn);
            int cxBest = x, cyBest = y, czBest = z, reach = 0;
            for (int i = 1; i <= 12; i++) {
                int cx = (int) Math.floor(x + 0.5 + ax * i), cz = (int) Math.floor(z + 0.5 + az * i);
                int cy = standAt(cx, cyBest, cz);
                if (cy == Integer.MIN_VALUE) break;
                cxBest = cx;
                cyBest = cy;
                czBest = cz;
                reach = i;
            }
            if (reach > got) {
                got = reach;
                bx = cxBest;
                by = cyBest;
                bz = czBest;
            }
            if (got >= 8) break;
        }
        if (got < 4) {
            runFailedUntil = world.tick + 200;
            return Outcome.blocked(skill, "No way to run from the creeper");
        }
        fleeing = true;
        try {
            var walked = walkTo(bx, by, bz, skill, SPRINT);
            return walked != null ? walked : Outcome.done(skill);
        } finally {
            fleeing = false;
        }
    }

    /** A block put up between it and the creeper, at its feet and its head, as the campaign walls one off. */
    private Outcome cover(Mob mob) {
        String block = count("cobblestone") > 1 ? "cobblestone" : count("dirt") > 1 ? "dirt" : null;
        if (block == null) return Outcome.blocked("native_place", "nothing to wall the creeper off with");
        double ax = mob.x - (x + 0.5), az = mob.z - (z + 0.5);
        pass(10, false);
        // The side it is on, and the other side too where it stands off the line, until it can no longer see it.
        int[][] sides = Math.abs(ax) >= Math.abs(az) ? new int[][] {{(int) Math.signum(ax), 0}, {0, (int) Math.signum(az)}}
                : new int[][] {{0, (int) Math.signum(az)}, {(int) Math.signum(ax), 0}};
        int placed = 0;
        for (int[] side : sides) {
            if (side[0] == 0 && side[1] == 0) continue;
            for (int h = 0; h < 2 && count(block) > 0; h++) {
                if (world.at(x + side[0], y + h, z + side[1]).open()) {
                    world.set(x + side[0], y + h, z + side[1], Block.COBBLE);
                    take(block, 1);
                    placed++;
                }
            }
            if (!sees(mob)) break;
        }
        // Walled off only if it no longer sees it; a wall it sees over or round is no cover.
        if (placed == 0 || sees(mob)) return Outcome.blocked("native_place", "no wall puts the creeper out of sight");
        return Outcome.done("native_place");
    }

    /**
     * Where it can stand in the column at {@code cx, cz} from {@code fromY}: that cell, a step up or a step down, with
     * room for its head and ground under its feet; {@link Integer#MIN_VALUE} when none.
     */
    private int standAt(int cx, int fromY, int cz) {
        if (!world.inside(cx, fromY, cz)) return Integer.MIN_VALUE;
        for (int dy : new int[] {0, 1, -1}) {
            int cy = fromY + dy;
            if (fits(cx, cy, cz, true) && world.at(cx, cy - 1, cz).solid()) return cy;
        }
        return Integer.MIN_VALUE;
    }

    /** Room for a body at {@code cx, cy, cz}: open at its feet (water too) and, for one two blocks tall, at its head. */
    private boolean fits(int cx, int cy, int cz, boolean tall) {
        Block feet = world.at(cx, cy, cz);
        if (!feet.open() && feet != Block.WATER) return false;
        Block head = world.at(cx, cy + 1, cz);
        return !tall || head.open() || head == Block.WATER;
    }

    /** Whether the mob sees it: nothing solid on the line from its eyes to the player's. */
    private boolean sees(Mob mob) {
        double ex = mob.x, ey = mob.y + (mob.kind.equals("spider") ? 0.65 : 1.5), ez = mob.z;
        double dx = x + 0.5 - ex, dy = y + 1.62 - ey, dz = z + 0.5 - ez;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Block b = world.at((int) Math.floor(ex + dx * t), (int) Math.floor(ey + dy * t), (int) Math.floor(ez + dz * t));
            if (!b.open() && b != Block.WATER) return false;
        }
        return true;
    }

    /**
     * Away from {@code mob} over open ground, never into the rock: from a hissing creeper the runtime's one block a
     * step (0b), from anything else up to eight blocks. Refused with nowhere to go.
     */
    private Outcome retreat(Mob mob) {
        boolean creeper = mob.kind.equals("creeper");
        double ax = x + 0.5 - mob.x, az = z + 0.5 - mob.z, len = Math.hypot(ax, az);
        if (len < 1e-3) { ax = 1; az = 0; len = 1; }
        int bx = x, by = y, bz = z, got = 0;
        for (int i = 1; i <= (creeper ? 1 : 8); i++) {
            int cx = (int) Math.floor(x + 0.5 + ax / len * i), cz = (int) Math.floor(z + 0.5 + az / len * i);
            int cy = standAt(cx, by, cz);
            if (cy == Integer.MIN_VALUE) break;
            bx = cx;
            by = cy;
            bz = cz;
            got = i;
        }
        if (got == 0) return Outcome.blocked("native_retreat", "nowhere to back away to");
        fleeing = creeper;
        try {
            var walked = walkTo(bx, by, bz, "native_retreat", creeper ? SPRINT : WALK);
            return walked != null ? walked : Outcome.done("native_retreat");
        } finally {
            fleeing = false;
        }
    }

    /**
     * Time going by, five ticks at a time: mobs come out in the dark and close in, hunger drains and health comes back
     * on a full stomach. With {@code interruptible}, it stops as soon as a mob has hurt the player; the ticks gone by.
     */
    private long pass(long ticks, boolean interruptible) { return pass(ticks, interruptible, null); }

    /** Where a walk has got to, by the share of its time gone by. */
    private interface Along { void at(double share); }

    private long pass(long ticks, boolean interruptible, Along along) {
        long passed = 0;
        float before = health;
        while (passed < ticks && health > 0) {
            world.tick += 5;
            passed += 5;
            if (along != null) along.at(Math.min(1, (double) passed / ticks));
            furnaceTick();
            exhaustion += 0.012f;
            if (exhaustion >= 4) {
                exhaustion -= 4;
                if (saturation > 0) saturation = Math.max(0, saturation - 1);
                else food = Math.max(0, food - 1);
            }
            if (world.tick % 80 == 0) {
                if (food >= 18 && health < 20) health = Math.min(20, health + 1);
                if (food == 0) hurt(1, "starved to death");
            }
            mobs(passed);
            if (interruptible && health < before - 0.5f && health > 0) break;
            // As the runtime stops whatever is running for a creeper that starts to hiss close by (0b).
            if (interruptible && !fleeing && hissingCreeper() != null) break;
        }
        return Math.min(passed, ticks);
    }

    private void mobs(long passed) {
        // Vanilla's cap is seventy hostiles to the ground a player loads, about sixteen to a map this size.
        if (world.tick % 20 == 0 && mobs.size() < 16 && !sheltered) spawn();
        for (var it = mobs.iterator(); it.hasNext(); ) {
            Mob mob = it.next();
            int mx = (int) Math.floor(mob.x), my = (int) Math.floor(mob.y), mz = (int) Math.floor(mob.z);
            boolean burns = !world.night() && world.skyLit(mx, my, mz) && !mob.kind.equals("creeper") && !mob.kind.equals("spider");
            // Gone as vanilla's go: at once past 128 blocks, and now and then past 32 (a chance in 800 a tick).
            if (burns || distance(mob) > 128 || distance(mob) > 32 && random.nextInt(160) == 0) {
                it.remove();
                continue;
            }
            if (sheltered) continue;
            double d = distance(mob);
            double speed = mob.kind.equals("spider") ? 0.75 : 0.5;
            // As vanilla's: a mob goes after a player it sees within its follow range (a zombie's 35 blocks, the
            // others' 16) and keeps after it while it stays in range; until then it stays where it is.
            double range = mob.kind.equals("zombie") ? 35 : 16;
            if (d > range) mob.after = false;
            else if (!mob.after && sees(mob)) mob.after = true;
            if (mob.after && (d > 1.5 && !mob.kind.equals("skeleton") || mob.kind.equals("skeleton") && d > 10)) walk(mob, d, speed);
            // After no one, it wanders, as vanilla's do: a few blocks one way at an easy pace, a pause, another way.
            if (!mob.after) wander(mob);
            d = distance(mob);
            boolean seen = d <= 16 && sees(mob);
            if (mob.cooldown > 0) mob.cooldown -= 5;
            switch (mob.kind) {
                case "zombie", "spider" -> {
                    boolean peaceful = mob.kind.equals("spider") && !world.night() && world.skyLit(mx, my, mz);
                    if (d <= 1.8 && seen && mob.cooldown <= 0 && !peaceful) {
                        hurt(mob.kind.equals("zombie") ? 3 : 2, "was slain by " + (mob.kind.equals("zombie") ? "Zombie" : "Spider"));
                        mob.cooldown = 20;
                    }
                }
                case "skeleton" -> {
                    if (d <= 15 && seen && mob.cooldown <= 0) {
                        if (random.nextInt(10) < 6 && !shielded(mob)) hurt(2 + random.nextInt(3), "was shot by Skeleton");
                        mob.cooldown = 40;
                    }
                }
                case "creeper" -> {
                    // As vanilla's: the fuse lit within three blocks and in sight, put out beyond seven or out of sight; a
                    // second and a half, then an explosion of power three, its harm falling off to nothing six blocks out.
                    if (d <= 3 && seen && mob.fuse < 0) mob.fuse = 30;
                    if (mob.fuse >= 0 && (d > 7 || !seen)) mob.fuse = -1;
                    if (mob.fuse >= 0) {
                        mob.fuse -= 5;
                        if (mob.fuse <= 0) {
                            double impact = 1 - d / 6;
                            if (impact > 0 && !shielded(mob)) hurt((float) ((impact * impact + impact) / 2 * 7 * 6 + 1), "was blown up by Creeper");
                            mob.exploded = true;
                            it.remove();
                        }
                    }
                }
                default -> { }
            }
            if (health <= 0) return;
        }
    }

    /**
     * A mob's walk toward the player through open cells, as a path would take it: straight on, else along one axis, up
     * a block onto a step; and down where nothing holds it up. Rock it cannot get through stops it.
     */
    private void walk(Mob mob, double d, double speed) {
        double nx = mob.x + (x + 0.5 - mob.x) / d * speed, nz = mob.z + (z + 0.5 - mob.z) / d * speed;
        if (!moveTo(mob, nx, nz) && !moveTo(mob, nx, mob.z)) moveTo(mob, mob.x, nz);
        int fx = (int) Math.floor(mob.x), fy = (int) Math.floor(mob.y), fz = (int) Math.floor(mob.z);
        if (fy - 1 > SimWorld.MIN_Y && (world.at(fx, fy - 1, fz).open() || world.at(fx, fy - 1, fz) == Block.WATER)) mob.y = fy - 1;
    }

    private void wander(Mob mob) {
        if (world.tick >= mob.headingUntil) {
            mob.heading = random.nextInt(3) == 0 ? Double.NaN : random.nextDouble() * Math.PI * 2;
            mob.headingUntil = world.tick + 40 + random.nextInt(80);
        }
        if (Double.isNaN(mob.heading)) return;
        double nx = mob.x + Math.cos(mob.heading) * 0.25, nz = mob.z + Math.sin(mob.heading) * 0.25;
        if (!moveTo(mob, nx, nz)) mob.headingUntil = world.tick;
        int fx = (int) Math.floor(mob.x), fy = (int) Math.floor(mob.y), fz = (int) Math.floor(mob.z);
        if (fy - 1 > SimWorld.MIN_Y && (world.at(fx, fy - 1, fz).open() || world.at(fx, fy - 1, fz) == Block.WATER)) mob.y = fy - 1;
    }

    private boolean moveTo(Mob mob, double nx, double nz) {
        boolean tall = !mob.kind.equals("spider");
        int cx = (int) Math.floor(nx), cy = (int) Math.floor(mob.y), cz = (int) Math.floor(nz);
        if (fits(cx, cy, cz, tall)) {
            mob.x = nx;
            mob.z = nz;
            return true;
        }
        if (fits(cx, cy + 1, cz, tall) && fits((int) Math.floor(mob.x), cy + 1, (int) Math.floor(mob.z), tall)) {
            mob.x = nx;
            mob.z = nz;
            mob.y = cy + 1;
            return true;
        }
        return false;
    }

    /**
     * A mob in a dark cell twenty-four to forty-eight blocks off, as vanilla never spawns one nearer a player than
     * twenty-four, more at night: zombies, skeletons, creepers and spiders.
     */
    private void spawn() {
        for (int attempt = 0; attempt < 2; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2, r = 24 + random.nextDouble() * 24;
            int mx = (int) Math.round(x + Math.cos(angle) * r), mz = (int) Math.round(z + Math.sin(angle) * r);
            if (!world.inside(mx, 0, mz)) continue;
            int my;
            if (world.skyLit(x, y, z)) {
                if (!world.night()) continue;
                my = world.top(mx, mz) + 1;
            } else {
                // Below ground at any height near its own, as vanilla picks a spawn's height anywhere in the column.
                my = y + random.nextInt(-12, 13);
                if (!world.at(mx, my, mz).open() || !world.at(mx, my + 1, mz).open() || !world.at(mx, my - 1, mz).solid()) continue;
            }
            if (world.light(mx, my, mz) > 0 && !(world.night() && world.skyLit(mx, my, mz))) continue;
            if (world.at(mx, my - 1, mz) == Block.WATER || world.at(mx, my - 1, mz) == Block.LEAVES) continue;
            if (random.nextInt(world.night() ? 3 : 8) != 0) continue;
            String kind = switch (random.nextInt(4)) { case 0 -> "zombie"; case 1 -> "skeleton"; case 2 -> "creeper"; default -> "spider"; };
            mobs.add(new Mob(kind, mx + 0.5, my, mz + 0.5, kind.equals("spider") ? 16 : 20));
        }
    }

    private void hurt(float amount, String how) {
        float reduced = amount * (1 - (chestTier >= 3 ? 0.24f : 0));
        health -= reduced;
        exhaustion += 0.1f;
        lastHurtBy = how;
        lastHurt = world.tick;
    }

    /** Its death, as the game records it, and back at the spawn with its things where it fell for five minutes. */
    private void die() {
        boolean night = world.night(), sky = world.skyOpen(x, y, z);
        var how = lastHurtBy.startsWith("fell") ? Lessons.How.FALL : lastHurtBy.contains("by ") ? Lessons.How.MOB : Lessons.How.OTHER;
        String message = name + " " + lastHurtBy;
        log.line(world.tick, name + " died at " + x + ", " + y + ", " + z + ": " + message);
        record.died(name, new EvalRecord.Death(world.tick - start, message, x, y, z, world.light(x, y, z), world.timeOfDay(), "minecraft:overworld",
                food, sky, loggedGoal, "NORMAL", habits.toString(), "simulated"));
        deathFacts.add(new Lessons.Death(world.tick, y, night, sky, true, food, how, false));
        atlas.harmed(new Atlas.Place("overworld", x, y, z), night, world.tick, Atlas.DEATH);
        for (var lesson : Lessons.review(deathFacts, List.of(), habits, learned, retired, world.tick)) {
            if (lesson.retire()) {
                habits.remove(lesson.habit());
                retired.put(lesson.habit(), world.tick);
            } else if (habits.add(lesson.habit())) {
                learned.put(lesson.habit(), world.tick);
                log.line(world.tick, name + " takes up a habit from its own play: " + lesson.habit() + " (" + lesson.note() + ")");
            }
        }
        drops = Map.copyOf(items);
        dropX = x; dropY = y; dropZ = z;
        dropsUntil = world.tick + 6_000;
        items.clear();
        mobs.clear();
        sheltered = false;
        sealedInPlace = false;
        health = 20;
        food = 20;
        saturation = 5;
        x = SimWorld.SIZE / 2;
        z = SimWorld.SIZE / 2;
        y = ground(x, z);
        keptX = x;
        keptY = y;
        keptZ = z;
        refreshTools();
        world.tick += 40;
    }

    // ------------------------------------------------------------------ helpers

    private int count(String item) { return items.getOrDefault(item, 0); }

    private void give(String item, int n) { if (n > 0) items.merge(item, n, Integer::sum); }

    private void take(String item, int n) {
        int left = count(item) - n;
        if (left > 0) items.put(item, left);
        else items.remove(item);
    }

    private static double sq(double v) { return v * v; }

    static Optional<Long> parseSeed(String text) {
        try {
            return Optional.of(Long.parseLong(text.trim()));
        } catch (NumberFormatException none) {
            return Optional.empty();
        }
    }
}
