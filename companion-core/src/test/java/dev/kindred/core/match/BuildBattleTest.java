package dev.kindred.core.match;

import dev.kindred.core.construction.Placement;
import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BuildBattleTest {
    private final UUID ada = UUID.randomUUID(), bram = UUID.randomUUID(), cy = UUID.randomUUID(), dee = UUID.randomUUID();

    @Test
    void plotsLieInARowEachABuildersOwn() {
        var plots = BuildBattle.layout(List.of(ada, bram), new Pos(100, 64, 0));
        assertEquals(new Pos(100, 64, 0), plots.get(0).origin());
        assertEquals(new Pos(100 + BuildBattle.PLOT + BuildBattle.GAP, 64, 0), plots.get(1).origin());
        assertTrue(plots.get(0).contains(new Pos(114, 65, 14)));
        assertFalse(plots.get(0).contains(new Pos(115, 65, 0)), "past its east edge");
        assertFalse(plots.get(0).contains(new Pos(100, 64, 0)), "its floor is not built on");
    }

    @Test
    void eachVotesOnTheOthersOneToFiveAndTheBestMeanWins() {
        var battle = new BuildBattle(BuildBattle.Theme.COTTAGE, BuildBattle.layout(List.of(ada, bram, cy), new Pos(0, 0, 0)));
        assertTrue(battle.vote(ada, ada, 5).isPresent(), "not on its own");
        assertTrue(battle.vote(dee, ada, 5).isPresent(), "only a builder votes");
        assertTrue(battle.vote(ada, bram, 6).isPresent(), "one to five");
        assertTrue(battle.winners().isEmpty(), "nobody voted yet");
        battle.vote(ada, bram, 4);
        battle.vote(ada, cy, 2);
        battle.vote(bram, ada, 3);
        battle.vote(bram, cy, 5);
        assertFalse(battle.votedAll(cy));
        battle.vote(cy, ada, 5);
        battle.vote(cy, bram, 4);
        assertTrue(battle.votedAll(cy));
        assertEquals(4.0, battle.scores().get(ada), 1e-9);
        assertEquals(4.0, battle.scores().get(bram), 1e-9);
        assertEquals(3.5, battle.scores().get(cy), 1e-9);
        assertEquals(Set.of(ada, bram), battle.winners(), "tied, together");
        battle.vote(cy, bram, 5);
        assertEquals(Set.of(bram), battle.winners(), "a later vote replaces an earlier");
    }

    @Test
    void everyThemesDesignFitsAPlotAndIsMadeOfBlocksAPlayerPlaces() {
        for (var theme : BuildBattle.Theme.values()) for (int seed = 0; seed < 12; seed++) {
            var design = BuildBattle.design(theme, new Random(seed));
            int width = 0, depth = 0;
            for (var b : design.blocks()) {
                width = Math.max(width, b.at().x() + 1);
                depth = Math.max(depth, b.at().z() + 1);
                assertNotEquals(Placement.Rule.UNSUPPORTED, Placement.rule(b.spec()), theme + ": " + b.spec());
            }
            assertTrue(width <= BuildBattle.PLOT - 2 && depth <= BuildBattle.PLOT - 2, theme + " " + width + "x" + depth + " with room round it");
        }
        assertNotEquals(BuildBattle.design(BuildBattle.Theme.COTTAGE, new Random(1)).name(), BuildBattle.design(BuildBattle.Theme.COTTAGE, new Random(4)).name(),
                "not the same cottage every time");
    }

    @Test
    void theJudgeScoresSizeKindsAndHeightAndABarePlotIsAOne() {
        assertEquals(1, BuildBattle.judge(Map.of(), BuildBattle.Theme.PYRAMID));
        var small = new HashMap<Pos, String>();
        for (int x = 0; x < 4; x++) small.put(new Pos(x, 1, 0), "minecraft:dirt");
        assertEquals(1, BuildBattle.judge(small, BuildBattle.Theme.COTTAGE));
        var tower = new HashMap<Pos, String>();
        String[] kinds = {"minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:glass", "minecraft:oak_planks", "minecraft:glowstone"};
        for (int y = 1; y <= 12; y++) for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            if (x == 0 || z == 0 || x == 4 || z == 4) tower.put(new Pos(x, y, z), kinds[(x + y + z) % kinds.length]);
        }
        assertEquals(5, BuildBattle.judge(tower, BuildBattle.Theme.WATCHTOWER), "big, varied and tall");
    }
}
