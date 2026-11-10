package dev.kindred.fabric;

import dev.kindred.core.factions.Hud;
import dev.kindred.core.record.ClaimLedger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * In plain terms: sends each player their Factions panel: to a player with Kindred's client mod the panel's state, only
 * when it changed and at most once a second; to a player on an ordinary client a sidebar with their faction, power,
 * claims, coins and standing, and one action-bar line each time they cross into other land.
 *
 * Technical: The HUD's server side (M-06l), run in the faction world's once-a-second step. Each state is built by
 * {@link Hud#build} for its viewer alone, so what reaches a client is what that person may know. The sidebar is a
 * per-player objective sent as packets to that player only; it touches no scoreboard of the server's.
 */
final class FactionsHud {
    private static final String OBJECTIVE = "kindred_factions";
    private final FactionsService factions;
    private final MarketDesk market;
    private final Map<UUID, String> sent = new HashMap<>();
    /** Each player's state as last built, the Factions sections' source for this second's panel; and its text, for fixtures. */
    private final Map<UUID, Hud.State> current = new HashMap<>();
    private final Map<UUID, String> stateSent = new HashMap<>();
    private final KindredHudSections sections;
    private final Map<UUID, List<String>> sidebars = new HashMap<>();
    private final Map<UUID, String> hereSeen = new HashMap<>();
    private final Map<UUID, Long> coinsSeen = new HashMap<>();
    private final Map<UUID, String[]> changes = new HashMap<>();
    private final Map<UUID, Integer> actionBarLines = new HashMap<>();
    private final Map<UUID, ArrayDeque<String[]>> notices = new HashMap<>();
    private Map<UUID, Integer> powerHourAgo = new HashMap<>();
    private Map<UUID, Integer> trends = new HashMap<>();
    private long hourStarted;
    private final Scoreboard scratch = new Scoreboard();

    FactionsHud(FactionsService factions, MarketDesk market, KindredHudSections sections) {
        this.factions = factions;
        this.market = market;
        this.sections = sections;
        // Factions' sections, registered as any integration registers its own (mod-support decision 7).
        sections.register("factions", viewer -> Hud.sections(current.computeIfAbsent(viewer.getUuid(), id -> state(viewer))));
    }

    /** A faction notice for its members' panels, shown for a minute. */
    void notice(UUID faction, String line) {
        var list = notices.computeIfAbsent(faction, k -> new ArrayDeque<>());
        list.addLast(new String[] {line, String.valueOf(factions.now())});
        while (list.size() > 3) list.removeFirst();
    }

    /** How many land lines a player's action bar has been sent, for fixtures. */
    int actionBarLines(UUID player) { return actionBarLines.getOrDefault(player, 0); }

    /** The panel last sent to a player ({@link dev.kindred.core.ui.HudPanel#encode}), for fixtures; empty before any. */
    Optional<String> lastSent(UUID player) { return Optional.ofNullable(sent.get(player)); }

    /** The Factions state the last panel was built from ({@link Hud#encode}), for fixtures; empty before any. */
    Optional<String> lastState(UUID player) { return Optional.ofNullable(stateSent.get(player)); }

    /** {@code viewer}'s state now. */
    Hud.State state(ServerPlayerEntity viewer) {
        long now = factions.now();
        var ledgers = factions.ledgersNow();
        String world = viewer.getServerWorld().getRegistryKey().getValue().toString();
        var pos = viewer.getBlockPos();
        var here = Hud.here(viewer.getUuid(), ledgers.factions(), ledgers.power(), ledgers.claims(), factions.safe(), factions.settings(),
                ClaimLedger.chunk(world, pos.getX() >> 4, pos.getZ() >> 4));
        var online = new HashSet<UUID>();
        for (var p : factions.server().getPlayerManager().getPlayerList()) online.add(p.getUuid());
        long coins = ledgers.coins().of(viewer.getUuid());
        Long before = coinsSeen.put(viewer.getUuid(), coins);
        if (before != null && before != coins) {
            long delta = coins - before;
            changes.put(viewer.getUuid(), new String[] {(delta > 0 ? "+" : "") + dev.kindred.core.factions.Coins.show(delta), String.valueOf(now)});
        }
        var change = changes.get(viewer.getUuid());
        String lastChange = change != null && now - Long.parseLong(change[1]) < 10_000 ? change[0] : "";
        var mine = ledgers.factions().of(viewer.getUuid());
        var lines = new ArrayList<String>();
        if (mine.isPresent()) for (var n : notices.getOrDefault(mine.get().id(), new ArrayDeque<>())) if (now - Long.parseLong(n[1]) < 60_000) lines.add(n[0]);
        String hand = viewer.getMainHandStack().isEmpty() ? "" : "In hand: " + market.priceLine(net.minecraft.registry.Registries.ITEM.getId(viewer.getMainHandStack().getItem()).toString());
        return Hud.build(viewer.getUuid(), ledgers.factions(), ledgers.power(), ledgers.claims(), ledgers.captures(), coins, factions.settings(), online, lastChange,
                here.getKey(), here.getValue(), lines, trends, market.movers(), hand, now);
    }

    /** Once a second: each player's panel or sidebar where it changed, and the action bar where they crossed into other land. */
    void tick(Iterable<ServerPlayerEntity> players) {
        long now = factions.now();
        if (now - hourStarted >= 3_600_000L) {
            var power = new HashMap<UUID, Integer>();
            var ledgers = factions.ledgersNow();
            for (var f : ledgers.factions().factions().values()) {
                power.put(f.id(), dev.kindred.core.factions.PowerRules.factionPower(ledgers.factions(), ledgers.power(), f.id(), factions.settings()));
            }
            var next = new HashMap<UUID, Integer>();
            for (var e : power.entrySet()) next.put(e.getKey(), Integer.signum(e.getValue() - powerHourAgo.getOrDefault(e.getKey(), e.getValue())));
            trends = next;
            powerHourAgo = power;
            hourStarted = now;
        }
        for (var player : players) {
            var state = state(player);
            UUID id = player.getUuid();
            current.put(id, state);
            String text = dev.kindred.core.ui.HudPanel.encode(sections.panel(player));
            String before = hereSeen.put(id, state.here());
            boolean kindredClient = ServerPlayNetworking.canSend(player, KindredPanelPayloads.Hud.ID);
            if (before != null && !before.equals(state.here()) && !kindredClient) {
                player.sendMessage(Text.literal(state.here()), true);
                actionBarLines.merge(id, 1, Integer::sum);
            }
            if (text.equals(sent.get(id))) continue;
            sent.put(id, text);
            stateSent.put(id, Hud.encode(state));
            if (kindredClient) ServerPlayNetworking.send(player, new KindredPanelPayloads.Hud(text));
            else sidebar(player, state);
        }
    }

    /** The vanilla sidebar: faction, power, claims, coins and standing, sent to this player alone. */
    private void sidebar(ServerPlayerEntity player, Hud.State state) {
        var lines = new ArrayList<String>();
        state.own().ifPresentOrElse(o -> {
            lines.add("[" + o.tag() + "] " + o.name());
            lines.add("Faction power " + o.power());
            lines.add("Claims " + o.claims() + " / " + o.capacity() + (o.exposed() > 0 ? " (" + o.exposed() + " exposed)" : ""));
        }, () -> lines.add("No faction"));
        lines.add("Power " + state.myPower() + " / " + state.maxPower());
        lines.add("Coins " + dev.kindred.core.factions.Coins.show(state.coins()));
        int rank = 0;
        for (var r : state.standings()) { rank++; if (r.mine()) { lines.add("Standing #" + rank); break; } }
        var objective = new ScoreboardObjective(scratch, OBJECTIVE, ScoreboardCriterion.DUMMY, Text.literal("Factions"), ScoreboardCriterion.RenderType.INTEGER,
                false, BlankNumberFormat.INSTANCE);
        var handler = player.networkHandler;
        var old = sidebars.get(player.getUuid());
        if (old == null) {
            handler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
            handler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
        }
        for (int i = 0; i < lines.size(); i++) {
            handler.sendPacket(new ScoreboardScoreUpdateS2CPacket("l" + i, OBJECTIVE, lines.size() - i, Optional.of(Text.literal(lines.get(i))), Optional.of(BlankNumberFormat.INSTANCE)));
        }
        if (old != null) for (int i = lines.size(); i < old.size(); i++) handler.sendPacket(new ScoreboardScoreResetS2CPacket("l" + i, OBJECTIVE));
        sidebars.put(player.getUuid(), lines);
    }

    /** A player gone: nothing more is sent until they are back. */
    void left(UUID player) {
        sent.remove(player);
        current.remove(player);
        stateSent.remove(player);
        sidebars.remove(player);
        hereSeen.remove(player);
    }
}
