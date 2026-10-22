package dev.kindred.design;

import dev.kindred.design.demo.DesignDemo;
import dev.kindred.design.model.DesignModel.*;
import dev.kindred.design.planning.*;
import dev.kindred.design.validation.StaticValidator;
import dev.kindred.design.validation.StaticValidator.Code;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Dependency-free executable scenarios, invoked by Gradle check; failures exit nonzero. */
public final class FoundationTests {
    private static final List<String> RESULTS = new ArrayList<>();
    private static final Brief BRIEF = DesignDemo.brief(0, 734922, new Pos(0, 64, 0));
    private static final Observation OBS = DesignDemo.flatObservation(BRIEF);
    private static final Design DESIGN = new WorkshopGenerator().generate(BRIEF, OBS);

    public static void main(String[] args) throws Exception {
        test("flat shell has connected entrance and usable clearance", () -> {
            check(new StaticValidator().validate(DESIGN, OBS).valid(), "Valid flat pilot rejected");
            check(DESIGN.voxels().size() == 99, "Unexpected shell count");
            check(DESIGN.requiredAir().size() == 35, "Interior/door/path air lost");
        });
        test("rotation, negative coordinates, and seeds remain valid", () -> {
            for (Pos origin : List.of(new Pos(0, 64, 0), new Pos(-200, 80, -300),
                    new Pos(127, -20, -129), new Pos(30_000, 100, 30_000))) {
                for (int rotation = 0; rotation < 4; rotation++) {
                    for (long seed = 0; seed < 8; seed++) {
                        Brief brief = DesignDemo.brief(rotation, seed, origin);
                        Observation observed = DesignDemo.flatObservation(brief);
                        Design design = new WorkshopGenerator().generate(brief, observed);
                        check(new StaticValidator().validate(design, observed).valid(), "Transform invalid");
                        check(new SupportCompiler().compile(design, observed).steps().size() == 99,
                                "Transform failed support compilation");
                    }
                }
            }
        });
        test("deterministic generation and order-independent exact digest", () -> {
            Design again = new WorkshopGenerator().generate(BRIEF, OBS);
            check(DESIGN.equals(again), "Generator is not deterministic");
            List<Voxel> reversed = new ArrayList<>(DESIGN.voxels());
            Collections.reverse(reversed);
            Design reordered = copy(reversed, DESIGN.requiredAir(), DESIGN.ports());
            check(DesignDigest.sha256(DESIGN).equals(DesignDigest.sha256(reordered)), "Order changed digest");
        });
        test("digest binds palette, clearance, ports, and revisions", () -> {
            List<Voxel> changed = new ArrayList<>(DESIGN.voxels());
            Voxel v = changed.getFirst();
            changed.set(0, new Voxel(v.position(), Material.OAK_PLANKS, v.phase()));
            different(copy(changed, DESIGN.requiredAir(), DESIGN.ports()));
            Set<Pos> air = new HashSet<>(DESIGN.requiredAir());
            air.add(new Pos(5, 65, 5));
            different(copy(DESIGN.voxels(), air, DESIGN.ports()));
            different(copy(DESIGN.voxels(), DESIGN.requiredAir(), List.of(new Port("other", new Pos(1, 65, 1)))));
            different(new Design(DESIGN.designId(), 2, DESIGN.observationId(), 2, DESIGN.generatorVersion(),
                    BRIEF, DESIGN.voxels(), DESIGN.requiredAir(), DESIGN.ports()));
        });
        test("unknown target fails closed", () -> {
            Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
            cells.remove(new Pos(0, 64, 0));
            rejects(DESIGN, observation(cells, Set.of(), OBS.revision()), Code.UNKNOWN_TARGET);
        });
        test("occupied and hazardous targets cannot be overwritten", () -> {
            for (Cell value : List.of(Cell.SOLID, Cell.HAZARD)) {
                Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
                cells.put(new Pos(0, 64, 0), value);
                rejects(DESIGN, observation(cells, Set.of(), 1), Code.OCCUPIED_TARGET);
            }
        });
        test("protected target rejects and compiler refuses invalid design", () -> {
            Observation protectedSite = observation(OBS.cells(), Set.of(new Pos(0, 64, 0)), 1);
            rejects(DESIGN, protectedSite, Code.PROTECTED_TARGET);
            expect(IllegalArgumentException.class, () -> new SupportCompiler().compile(DESIGN, protectedSite));
        });
        test("removed ground and unknown ground both reject", () -> {
            for (boolean unknown : List.of(false, true)) {
                Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
                if (unknown) cells.remove(new Pos(0, 63, 0));
                else cells.put(new Pos(0, 63, 0), Cell.AIR);
                rejects(DESIGN, observation(cells, Set.of(), 1), Code.NO_GROUND_SUPPORT);
            }
        });
        test("blocked and unknown interior clearance reject", () -> {
            Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
            cells.put(new Pos(1, 65, 1), Cell.SOLID);
            rejects(DESIGN, observation(cells, Set.of(), 1), Code.BLOCKED_CLEARANCE);
            cells.remove(new Pos(1, 65, 1));
            rejects(DESIGN, observation(cells, Set.of(), 1), Code.UNKNOWN_CLEARANCE);
        });
        test("blocked entrance disconnects interior port", () -> {
            Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
            cells.put(new Pos(2, 65, 0), Cell.SOLID);
            rejects(DESIGN, observation(cells, Set.of(), 1), Code.DISCONNECTED_PORT);
        });
        test("planned block cannot invade clearance", () -> {
            List<Voxel> voxels = new ArrayList<>(DESIGN.voxels());
            voxels.add(new Voxel(new Pos(2, 65, 0), Material.COBBLESTONE, Phase.WALLS));
            rejects(copy(voxels, DESIGN.requiredAir(), DESIGN.ports()), OBS, Code.CLEARANCE_CONFLICT);
        });
        test("duplicate target and out-of-scope placement reject", () -> {
            List<Voxel> voxels = new ArrayList<>(DESIGN.voxels());
            voxels.add(voxels.getFirst());
            rejects(copy(voxels, DESIGN.requiredAir(), DESIGN.ports()), OBS, Code.DUPLICATE_TARGET);
            voxels.set(voxels.size() - 1, new Voxel(new Pos(1000, 64, 0), Material.OAK_PLANKS, Phase.ROOF));
            rejects(copy(voxels, DESIGN.requiredAir(), DESIGN.ports()), OBS, Code.OUTSIDE_SITE);
        });
        test("stale observation rejects before compilation", () -> {
            Observation stale = observation(OBS.cells(), Set.of(), 2);
            rejects(DESIGN, stale, Code.STALE_OBSERVATION);
            expect(IllegalArgumentException.class, () -> new SupportCompiler().compile(DESIGN, stale));
        });
        test("world and dimension mismatch reject", () -> {
            Observation wrong = new Observation(OBS.observationId(), new UUID(4, 5), "minecraft:the_nether",
                    1, OBS.capturedAt(), OBS.provenance(), OBS.bounds(), OBS.cells(), Set.of());
            rejects(DESIGN, wrong, Code.WRONG_WORLD);
            expect(IllegalArgumentException.class, () -> new WorkshopGenerator().generate(BRIEF, wrong));
        });
        test("reservations reduce free stock and reveal exact deficits", () -> {
            var budget = new MaterialPlanner().evaluate(DESIGN,
                    Map.of(Material.COBBLESTONE, 70L, Material.OAK_PLANKS, 20L),
                    Map.of(Material.COBBLESTONE, 5L));
            check(budget.required().get(Material.COBBLESTONE) == 74, "Wrong stone count");
            check(budget.required().get(Material.OAK_PLANKS) == 25, "Wrong roof count");
            check(budget.deficits().equals(Map.of(Material.COBBLESTONE, 9L, Material.OAK_PLANKS, 5L)),
                    "Wrong reservation-aware deficit");
            var supplied = new MaterialPlanner().evaluate(DESIGN, budget.required(), Map.of());
            check(supplied.hasAllDirectItems(), "Exact supply incorrectly rejected");
        });
        test("negative and inconsistent stock reject; maximal counts do not overflow", () -> {
            var planner = new MaterialPlanner();
            expect(IllegalArgumentException.class, () -> planner.evaluate(DESIGN,
                    Map.of(Material.COBBLESTONE, -1L), Map.of()));
            expect(IllegalArgumentException.class, () -> planner.evaluate(DESIGN,
                    Map.of(), Map.of(Material.COBBLESTONE, 1L)));
            var budget = planner.evaluate(DESIGN, Map.of(Material.COBBLESTONE, Long.MAX_VALUE),
                    Map.of(Material.COBBLESTONE, Long.MAX_VALUE));
            check(budget.deficits().get(Material.COBBLESTONE) == 74, "Large count overflow");
        });
        test("support graph is ordered, adjacent, complete, and never executable", () -> {
            var plan = new SupportCompiler().compile(DESIGN, OBS);
            Set<String> completed = new HashSet<>();
            Map<Pos, String> planned = new HashMap<>();
            int lastPhase = -1;
            for (var step : plan.steps()) {
                check(completed.containsAll(step.dependencies()), "Dependency precedes its support");
                check(step.proposedBlock().phase().ordinal() >= lastPhase, "Phases regressed");
                lastPhase = step.proposedBlock().phase().ordinal();
                Pos p = step.proposedBlock().position();
                Pos support = step.support();
                check(p.below().equals(support) || p.horizontalNeighbors().contains(support), "Nonadjacent support");
                if (planned.containsKey(support)) check(step.dependencies().contains(planned.get(support)), "Missing support dependency");
                else check(OBS.cell(support).orElse(null) == Cell.SOLID, "Support is unknown or air");
                check(completed.add(step.stepId()), "Duplicate step");
                planned.put(p, step.stepId());
            }
            check(plan.steps().size() == DESIGN.voxels().size(), "Incomplete plan");
            check(!plan.executionQualified(), "Pure graph granted native qualification");
            check(plan.missingQualification().contains("worker-stance-and-reach"), "Missing reach warning");
        });
        test("unsupported floating component cannot compile", () -> {
            List<Voxel> voxels = new ArrayList<>(DESIGN.voxels());
            voxels.add(new Voxel(new Pos(6, 68, 6), Material.OAK_PLANKS, Phase.ROOF));
            Design floating = copy(voxels, DESIGN.requiredAir(), DESIGN.ports());
            check(new StaticValidator().validate(floating, OBS).valid(), "Fixture should pass static checks");
            expect(IllegalArgumentException.class, () -> new SupportCompiler().compile(floating, OBS));
        });
        test("input collections and returned artifacts are immutable", () -> {
            Map<Pos, Cell> cells = new HashMap<>(OBS.cells());
            Observation copy = observation(cells, Set.of(), 1);
            cells.clear();
            check(!copy.cells().isEmpty(), "Observation aliases caller data");
            expect(UnsupportedOperationException.class, () -> copy.cells().clear());
            expect(UnsupportedOperationException.class, () -> DESIGN.voxels().clear());
            expect(UnsupportedOperationException.class, () -> DESIGN.requiredAir().clear());
            expect(UnsupportedOperationException.class, () -> new SupportCompiler().compile(DESIGN, OBS).steps().clear());
        });
        test("bounded arithmetic rejects extremes and permits boundary-sized boxes", () -> {
            expect(IllegalArgumentException.class, () -> new Box(new Pos(Integer.MIN_VALUE, 0, 0), new Pos(Integer.MAX_VALUE, 0, 0)));
            expect(IllegalArgumentException.class, () -> new Box(new Pos(2, 0, 0), new Pos(1, 0, 0)));
            expect(ArithmeticException.class, () -> new Pos(Integer.MAX_VALUE, 0, 0).add(1, 0, 0));
            expect(ArithmeticException.class, () -> DesignDemo.brief(0, 0, new Pos(Integer.MAX_VALUE, 64, 0)));
            check(new Box(new Pos(0, 0, 0), new Pos(65_535, 0, 0)).cells().size() == 65_536, "Boundary box rejected");
            check(new Box(new Pos(Integer.MAX_VALUE, 0, 0), new Pos(Integer.MAX_VALUE, 0, 0)).cells().size() == 1,
                    "Extreme singleton iteration overflowed");
        });
        test("invalid identifiers and pilot limits reject", () -> {
            expect(IllegalArgumentException.class, () -> new Port("../unsafe\nname", new Pos(0, 0, 0)));
            expect(IllegalArgumentException.class, () -> DesignDemo.brief(4, 0, new Pos(0, 64, 0)));
            expect(IllegalArgumentException.class, () -> new Brief(BRIEF.projectId(), BRIEF.worldId(), BRIEF.dimension(),
                    BRIEF.site(), BRIEF.floorOrigin(), BRIEF.accessAnchor(), 0, 0, 0));
        });
        String report = String.join("\n", RESULTS) + "\nPASS: " + RESULTS.size()
                + " pure scenarios (128 rotation/site/seed cases); no native or live evidence.\n";
        if (args.length == 1) {
            Path output = Path.of(args[0]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, report, StandardCharsets.UTF_8);
        }
        System.out.print(report);
    }

    private static Design copy(List<Voxel> voxels, Set<Pos> air, List<Port> ports) {
        return new Design(DESIGN.designId(), DESIGN.revision(), DESIGN.observationId(),
                DESIGN.observationRevision(), DESIGN.generatorVersion(), BRIEF, voxels, air, ports);
    }
    private static Observation observation(Map<Pos, Cell> cells, Set<Pos> protectedCells, long revision) {
        return new Observation(OBS.observationId(), OBS.worldId(), OBS.dimension(), revision,
                OBS.capturedAt(), OBS.provenance(), OBS.bounds(), cells, protectedCells);
    }
    private static void rejects(Design design, Observation observation, Code code) {
        check(new StaticValidator().validate(design, observation).violations().stream()
                .anyMatch(v -> v.code() == code), "Expected rejection " + code);
    }
    private static void different(Design design) {
        check(!DesignDigest.sha256(DESIGN).equals(DesignDigest.sha256(design)), "Digest did not bind changed data");
    }
    private static void test(String name, Runnable body) {
        body.run();
        RESULTS.add("PASS " + name);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable body) {
        try { body.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName());
    }
}
