package dev.kindred.core.match;

import dev.kindred.core.construction.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: the arena a build battle is played in, laid out round its plots as a minigame server lays one
 * out: a lobby pad to gather on before the start; a raised walkway along the front of every plot, with railings,
 * for onlookers to watch from and for the voters to walk along, with a marked spot in front of each plot to stand
 * on while that build is voted on; and a podium of three steps at the far end for the winners.
 *
 * Technical: The generated arena of a build battle (M-04). Pure: the blocks to set, by position, and where things
 * are. The plots run east from the first; the walkway runs along their south side, {@link #WALK_WIDTH} wide and one
 * block above the plots' floor, so a player on it looks down over each build. The lobby pad is at its west end and
 * the podium at its east end. Each plot's viewing spot is the walkway cell in front of the plot's middle. Blocks
 * come back by id; the adapter sets them as the plots are marked out, in Builder only, where blocks are free.
 */
public final class BuildArena {
    /** The walkway's width, and the lobby pad's side. */
    public static final int WALK_WIDTH = 3, LOBBY = 7;

    private final Map<Pos, String> blocks;
    private final Map<UUID, Pos> spots;
    private final Pos lobby;
    private final List<Pos> podium;

    private BuildArena(Map<Pos, String> blocks, Map<UUID, Pos> spots, Pos lobby, List<Pos> podium) {
        this.blocks = Map.copyOf(blocks);
        this.spots = Map.copyOf(spots);
        this.lobby = lobby;
        this.podium = List.copyOf(podium);
    }

    /** The blocks to set, by position: the walkway, its railings and markers, the lobby pad, the cleared air above. */
    public Map<Pos, String> blocks() { return blocks; }

    /** Where each builder's build is watched from for the vote: the cell to stand in, on the walkway. */
    public Map<UUID, Pos> spots() { return spots; }

    /** The middle of the lobby pad: the cell to stand in. */
    public Pos lobby() { return lobby; }

    /** The podium's steps, first, second and third, each the cell above its top block: where a winner's sign goes. */
    public List<Pos> podium() { return podium; }

    /** The podium's blocks for the given number of places (one to three), by position: gold, iron and copper steps. */
    public Map<Pos, String> podiumBlocks(int places) {
        var out = new LinkedHashMap<Pos, String>();
        String[] kind = {"minecraft:gold_block", "minecraft:iron_block", "minecraft:copper_block"};
        for (int i = 0; i < Math.min(3, Math.max(0, places)); i++) {
            Pos top = podium.get(i);
            int height = 3 - i;
            for (int y = 1; y <= height; y++) out.put(new Pos(top.x(), top.y() - y, top.z()), kind[i]);
        }
        return out;
    }

    /** The arena for the given plots, all in a row on the same floor (as {@link BuildBattle#layout} lays them). */
    public static BuildArena around(List<BuildBattle.Plot> plots) {
        if (plots.isEmpty()) throw new IllegalArgumentException("An arena has plots");
        var first = plots.getFirst().origin();
        var last = plots.getLast().origin();
        int floor = first.y();
        int walkZ = first.z() + BuildBattle.PLOT + 1;
        int westX = first.x() - LOBBY - 1, eastX = last.x() + BuildBattle.PLOT + 5;
        var blocks = new LinkedHashMap<Pos, String>();
        // The walkway, one up from the plots' floor, from the lobby pad to the podium, with a railing on its far side.
        for (int x = westX; x <= eastX; x++) {
            for (int dz = 0; dz < WALK_WIDTH; dz++) {
                blocks.put(new Pos(x, floor + 1, walkZ + dz), "minecraft:polished_andesite");
                for (int y = 2; y <= 4; y++) blocks.put(new Pos(x, floor + y, walkZ + dz), "minecraft:air");
            }
            blocks.put(new Pos(x, floor + 1, walkZ + WALK_WIDTH), "minecraft:polished_andesite");
            blocks.put(new Pos(x, floor + 2, walkZ + WALK_WIDTH), "minecraft:oak_fence");
        }
        // The lobby pad at the walkway's west end, reaching north alongside the first plot.
        for (int x = westX; x < westX + LOBBY; x++) {
            for (int z = walkZ + WALK_WIDTH - LOBBY; z < walkZ + WALK_WIDTH; z++) {
                blocks.put(new Pos(x, floor + 1, z), "minecraft:smooth_quartz");
                for (int y = 2; y <= 4; y++) blocks.put(new Pos(x, floor + y, z), "minecraft:air");
            }
        }
        Pos lobby = new Pos(westX + LOBBY / 2, floor + 2, walkZ + WALK_WIDTH - LOBBY / 2 - 1);
        // Each plot's viewing spot: the walkway's middle row in front of the plot's middle, marked in gold underfoot.
        var spots = new LinkedHashMap<UUID, Pos>();
        for (var plot : plots) {
            Pos spot = new Pos(plot.origin().x() + BuildBattle.PLOT / 2, floor + 2, walkZ + 1);
            spots.put(plot.builder(), spot);
            blocks.put(new Pos(spot.x(), floor + 1, spot.z()), "minecraft:gold_block");
        }
        // The podium at the east end: first in the middle, second to the west, third to the east, facing the walkway.
        int podiumX = eastX - 3, podiumZ = walkZ + 1;
        var podium = new ArrayList<Pos>();
        podium.add(new Pos(podiumX, floor + 2 + 3, podiumZ));
        podium.add(new Pos(podiumX - 1, floor + 2 + 2, podiumZ));
        podium.add(new Pos(podiumX + 1, floor + 2 + 1, podiumZ));
        for (Pos step : podium) for (int y = step.y(); y <= step.y() + 2; y++) blocks.put(new Pos(step.x(), y, step.z()), "minecraft:air");
        return new BuildArena(blocks, spots, lobby, podium);
    }
}
