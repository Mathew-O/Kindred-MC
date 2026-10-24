package dev.kindred.core.team;

import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Member;
import dev.kindred.core.team.ShareOut.Slot;
import dev.kindred.core.team.ShareOut.Work;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeamTalkTest {
    private static final UUID WREN = new UUID(0, 1), MASON = new UUID(0, 2);

    @Test
    void aFindIsSaidWithWhoGetsWhatOfItFromTheShareOut() {
        var grants = List.of(new ShareOut.Grant(WREN, "Wren", Slot.BOOTS, Material.DIAMOND, 4),
                new ShareOut.Grant(WREN, "Wren", Slot.HELMET, Material.DIAMOND, 5),
                new ShareOut.Grant(MASON, "Mason", Slot.SWORD, Material.DIAMOND, 2));
        assertEquals("I've got 11 diamonds: diamond boots and a diamond helmet for Wren and a diamond sword for me.",
                TeamTalk.find(MASON, Material.DIAMOND, 11, grants));
    }

    @Test
    void whatTheShareOutGivesIsWhatIsSaidAndNothingElse() {
        var team = List.of(new Member(WREN, "Wren", Map.of(), Work.NETHER), new Member(MASON, "Mason", Map.of(), Work.GATHERING));
        var grants = ShareOut.share(team, Material.IRON, 3);
        String line = TeamTalk.find(MASON, Material.IRON, 3, grants);
        assertTrue(line.startsWith("I've got 3 iron ingots: "), line);
        for (var g : grants) assertTrue(line.contains(TeamTalk.piece(g.slot(), g.material())), line);
    }

    @Test
    void nothingToMakeOfItIsSaidSoAndCountsAndPiecesReadAsSpoken() {
        assertEquals("I've got 1 diamond; nobody needs anything made of it yet.", TeamTalk.find(MASON, Material.DIAMOND, 1, List.of()));
        assertEquals("an iron chestplate", TeamTalk.piece(Slot.CHESTPLATE, Material.IRON));
        assertEquals("gold boots", TeamTalk.piece(Slot.BOOTS, Material.GOLD));
        assertEquals("4 gold ingots", "4 " + TeamTalk.of(Material.GOLD, 4));
    }
}
