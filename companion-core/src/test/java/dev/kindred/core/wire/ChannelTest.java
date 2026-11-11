package dev.kindred.core.wire;

import dev.kindred.core.knowledge.WebFeed;
import dev.kindred.core.match.GameModes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that the eight built-in channels keep the names saved in the World Record, and that another
 * mod's integration can name a channel of its own (a town's) that reaches the town's people and nobody else, keeps its
 * own limit, is open only in the modes it names, and is saved under its own name, all without changing core.
 */
class ChannelTest {
    private static final Listener.Place SPAWN = new Listener.Place("minecraft:overworld", 0, 64, 0);

    @Test
    void theBuiltInChannelsKeepTheirSavedIds() {
        assertEquals(List.of("private", "team", "faction", "settlement", "side", "match", "public", "operator"),
                Channel.values().stream().filter(Channel::builtIn).map(Channel::id).toList());
        for (Channel c : Channel.values()) {
            assertSame(c, Channel.parse(c.id()));
            assertSame(c, Channel.parse(c.name()), "the enum's old names still parse");
        }
        assertSame(Channel.FACTION, Channel.parse(" Faction "));
        assertNull(Channel.parse("nowhere"));
        assertEquals(Channel.Audience.SIDE, Channel.SIDE.audience());
        assertEquals("TEAM", Channel.TEAM.name());
        assertThrows(IllegalArgumentException.class, () -> Channel.register("team", 6, 60_000, Visibility.TEAM, Channel.Audience.TEAM, Set.of()),
                "a built-in id is never taken");
    }

    @Test
    void anIntegrationsTownChannelReachesTheTownAlone() {
        Channel town = Channel.register("towny.town", 2, 90_000, Visibility.SETTLEMENT, Channel.Audience.SETTLEMENT,
                Set.of(GameModes.WorldMode.FACTIONS, GameModes.WorldMode.COOP_SURVIVAL));
        assertSame(town, Channel.register("Towny.Town", 2, 90_000, Visibility.SETTLEMENT, Channel.Audience.SETTLEMENT,
                Set.of(GameModes.WorldMode.COOP_SURVIVAL, GameModes.WorldMode.FACTIONS)), "the same settings give the one channel");
        assertThrows(IllegalArgumentException.class, () -> Channel.register("towny.town", 9, 90_000, Visibility.SETTLEMENT, Channel.Audience.SETTLEMENT, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> Channel.register("bad id!", 2, 1, Visibility.PUBLIC, Channel.Audience.PUBLIC, Set.of()));
        assertSame(town, Channel.parse("towny.town"));
        assertFalse(town.builtIn());

        var wire = new Wire(Wire.Config.defaults());
        var mayor = new Listener(UUID.randomUUID(), "Mayor", false, Set.of(), "", "riverton", "", false, SPAWN, null);
        var baker = new Listener(UUID.randomUUID(), "Baker", false, Set.of(), "", "riverton", "", false, SPAWN, null);
        var stranger = new Listener(UUID.randomUUID(), "Stranger", false, Set.of(), "", "hillford", "", false, SPAWN, null);
        var roster = List.of(mayor, baker, stranger);
        var scope = new Scope(town, "riverton", null, 0);
        var m = Message.of("town.tax_due", mayor.id(), scope, Map.of("due", Fact.of("dusk", Visibility.SETTLEMENT)), List.of());
        var d = wire.send(m, GameModes.WorldMode.FACTIONS, roster, 0);
        assertEquals(List.of(baker.id()), d.receivers(), "the town's people, never a stranger");
        // A fact narrower than the town's stays off it, and a stranger cannot post on it.
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(Message.of("town.stock", mayor.id(), scope,
                Map.of("iron", Fact.of("40", Visibility.FACTION)), List.of()), GameModes.WorldMode.FACTIONS, roster, 1).refused().orElseThrow());
        assertEquals(Delivery.Refusal.NOT_MEMBER, wire.send(Message.of("town.ask", stranger.id(), scope, Map.of(), List.of()),
                GameModes.WorldMode.FACTIONS, roster, 2).refused().orElseThrow());
        // Its own limit: two a minute.
        int sent = 0;
        for (int i = 0; i < 5; i++) if (wire.send(Message.of("town.note", mayor.id(), scope, Map.of("n", Fact.of("" + i, Visibility.SETTLEMENT)), List.of()),
                GameModes.WorldMode.FACTIONS, roster, 10_000L + i).sent()) sent++;
        assertEquals(1, sent, "two a minute, the tax notice the first");
        // Open only where it said.
        assertTrue(ModeChannels.allows(GameModes.WorldMode.COOP_SURVIVAL, town));
        assertFalse(ModeChannels.allows(GameModes.WorldMode.SPEEDRUN, town));
        assertTrue(ModeChannels.allows(null, town));
        assertEquals(Delivery.Refusal.MODE, wire.send(m, GameModes.WorldMode.SPEEDRUN, roster, 120_000).refused().orElseThrow());
        // Saved under its own id.
        var data = WebFeed.record(m, baker, false, List.of(baker.id()));
        assertTrue(data.toString().contains("towny.town"), "the Record keeps the channel's id: " + data);
    }
}
