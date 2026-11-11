package dev.kindred.fabric;

import dev.kindred.core.match.MatchPresentation;
import dev.kindred.core.match.MatchRegistry;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * In plain terms: shows a match to the players in it, the way an ordinary game shows things: big titles with
 * sounds for the countdown, the start and each player's result, and a bar at the top of the screen with the
 * players and the clock while it lasts.
 *
 * Technical: Shows a match to the players in it (M-00b), as {@link MatchPresentation} works it out: titles
 * with their sounds for the countdown, the start and each player's result, a boss bar with the players and the
 * clock, a sidebar with each player's standing, the player's own part over its hotbar, and particles at the
 * start and round the winners. Onlookers (players not in it within {@link #ONLOOKER_RANGE} blocks of one who
 * is) get the bar, the sidebar and the result. A title for a player who is dead when it comes is held until it
 * is on its feet again: the death screen would cover it. The sidebar is sent to each viewer alone, as packets
 * of an objective the server's own scoreboard never holds. All of it is what a vanilla client shows unaided.
 * Server thread only.
 */
final class MatchPresenter {
    /** Titles kept for each player, most recent last: what a fixture or a status line reads back. */
    static final int SHOWN_KEPT = 8;
    /** How near a player not in a match must be to one in it to watch it. */
    static final int ONLOOKER_RANGE = 48;
    /** The sidebar objective's name on each client: one sidebar a viewer. */
    static final String SIDEBAR = "kindred_match";

    /** A title as it was sent: what it said, and whether its player was alive to see it. */
    record Sent(String title, boolean alive) { }

    /** A viewer's sidebar as last sent: whose match, and its lines. */
    private record Shown(UUID match, String title, List<String> lines) { }

    private final MinecraftServer server;
    private final Map<UUID, ServerBossBar> bars = new HashMap<>();
    private final Map<UUID, ArrayDeque<String>> shown = new HashMap<>();
    /** The countdown title each match last showed, so each second is shown once whichever tick it starts on. */
    private final Map<UUID, String> counted = new HashMap<>();
    /** Titles held for players who were dead when they came, in order. */
    private final Map<UUID, ArrayDeque<MatchPresentation.Title>> held = new HashMap<>();
    private final Map<UUID, ArrayDeque<Sent>> sent = new HashMap<>();
    private final Map<UUID, Shown> sidebars = new HashMap<>();
    /** The onlookers of each match now. */
    private final Map<UUID, List<UUID>> onlookers = new HashMap<>();
    /** The particle bursts shown round each player, for checking. */
    private final Map<UUID, List<String>> bursts = new HashMap<>();
    /** When each player was last told a split over its hotbar, so the clock does not write over it at once. */
    private final Map<UUID, Long> splitShown = new HashMap<>();
    /** A scoreboard of our own, never the server's, only to build the sidebar's packets with. */
    private final Scoreboard board = new Scoreboard();

    MatchPresenter(MinecraftServer server) { this.server = server; }

    /** Each tick: the bars kept up to date, and the countdown's seconds shown as they come. */
    void tick(List<MatchRegistry.Match> matches, long tick, Function<UUID, String> names, Function<UUID, Float> progress) {
        tick(matches, tick, names, progress, (match, player) -> "");
    }

    /**
     * Each tick: the bars, sidebars and onlookers kept up to date, the countdown's seconds shown as they come, the
     * players' own lines over their hotbars each second, and titles held for the dead shown once they are back.
     * {@code standing} is a player's word on the sidebar (a racer's splits).
     */
    void tick(List<MatchRegistry.Match> matches, long tick, Function<UUID, String> names, Function<UUID, Float> progress,
              BiFunction<MatchRegistry.Match, UUID, String> standing) {
        var live = new HashSet<UUID>();
        var viewing = new HashSet<UUID>();
        // Players in a match of their own: never onlookers of another (its sidebar would take the place of their own).
        var busy = new HashSet<UUID>();
        for (var match : matches) if (match.state() != MatchRegistry.State.ENDED) busy.addAll(match.invited());
        for (var match : matches) {
            if (match.state() == MatchRegistry.State.ENDED) continue;
            live.add(match.id());
            var bar = bars.computeIfAbsent(match.id(), id -> new ServerBossBar(Text.literal(""), BossBar.Color.RED, BossBar.Style.NOTCHED_10));
            if (tick % 10 == 0 || bar.getPlayers().isEmpty()) {
                var shownBar = MatchPresentation.bar(match, tick, names, progress.apply(match.id()));
                bar.setName(Text.literal(shownBar.text()));
                bar.setPercent(shownBar.progress());
                bar.setColor(match.state() == MatchRegistry.State.RUNNING ? BossBar.Color.RED : BossBar.Color.YELLOW);
                var watchers = watchers(match, busy);
                onlookers.put(match.id(), watchers);
                var everyone = new ArrayList<UUID>(match.invited());
                everyone.addAll(watchers);
                for (UUID viewer : everyone) {
                    var player = server.getPlayerManager().getPlayer(viewer);
                    if (player != null && !bar.getPlayers().contains(player)) bar.addPlayer(player);
                }
                for (var player : new ArrayList<>(bar.getPlayers())) {
                    if (player.isRemoved() || server.getPlayerManager().getPlayer(player.getUuid()) != player
                            || !everyone.contains(player.getUuid())) bar.removePlayer(player);
                }
                var sidebar = MatchPresentation.sidebar(match, tick, names, player -> standing.apply(match, player));
                for (UUID viewer : everyone) {
                    if (!viewing.add(viewer)) continue;
                    sidebar(viewer, match.id(), sidebar);
                }
            } else {
                viewing.addAll(match.invited());
                viewing.addAll(onlookers.getOrDefault(match.id(), List.of()));
            }
            if (tick % 20 == 0) {
                for (UUID invited : match.invited()) {
                    var player = server.getPlayerManager().getPlayer(invited);
                    if (player == null || tick - splitShown.getOrDefault(invited, Long.MIN_VALUE / 2) < 60) continue;
                    MatchPresentation.actionBar(match, invited, tick, names)
                            .ifPresent(line -> player.sendMessage(Text.literal(line).formatted(Formatting.GOLD), true));
                }
            }
            MatchPresentation.countdown(match, tick).ifPresent(title -> {
                if (title.title().equals(counted.put(match.id(), title.title()))) return;
                for (UUID invited : match.invited()) show(invited, title);
            });
        }
        counted.keySet().retainAll(live);
        onlookers.keySet().retainAll(live);
        bars.keySet().removeIf(id -> {
            if (live.contains(id)) return false;
            bars.get(id).clearPlayers();
            return true;
        });
        // A sidebar whose match is over, or whose viewer has wandered off, taken down.
        if (tick % 10 == 0) {
            for (UUID viewer : new ArrayList<>(sidebars.keySet())) if (!viewing.contains(viewer)) clearSidebar(viewer);
        }
        // A title held for a player who was dead, shown once it is on its feet.
        for (UUID playerId : new ArrayList<>(held.keySet())) {
            var player = server.getPlayerManager().getPlayer(playerId);
            if (player == null || !player.isAlive()) continue;
            var titles = held.remove(playerId);
            // Only the latest counts by now: the countdown seconds it missed are long past.
            send(player, titles.getLast());
        }
    }

    /** Players in no match of their own, near one who is in this one and in the same world: its onlookers. */
    private List<UUID> watchers(MatchRegistry.Match match, java.util.Set<UUID> busy) {
        var found = new ArrayList<UUID>();
        for (var player : server.getPlayerManager().getPlayerList()) {
            if (busy.contains(player.getUuid())) continue;
            for (UUID invited : match.invited()) {
                var in = server.getPlayerManager().getPlayer(invited);
                if (in != null && in.getServerWorld() == player.getServerWorld()
                        && in.squaredDistanceTo(player) <= ONLOOKER_RANGE * ONLOOKER_RANGE) {
                    found.add(player.getUuid());
                    break;
                }
            }
        }
        return found;
    }

    /** A viewer's sidebar brought up to date: what changed sent, and nothing more. */
    private void sidebar(UUID viewer, UUID matchId, MatchPresentation.Sidebar sidebar) {
        var player = server.getPlayerManager().getPlayer(viewer);
        if (player == null) return;
        var was = sidebars.get(viewer);
        var objective = objective(sidebar.title());
        if (was == null || !was.match().equals(matchId)) {
            if (was != null) player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
            was = new Shown(matchId, sidebar.title(), List.of());
        }
        var lines = sidebar.lines();
        // One holder a line, scored so the first line is on top; a line's text as its display name, no number shown.
        for (int i = 0; i < lines.size(); i++) {
            if (i < was.lines().size() && was.lines().get(i).equals(lines.get(i))) continue;
            player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket("line" + i, SIDEBAR, lines.size() - i,
                    java.util.Optional.of(Text.literal(lines.get(i))), java.util.Optional.of(BlankNumberFormat.INSTANCE)));
        }
        if (lines.size() != was.lines().size()) {
            for (int i = 0; i < lines.size(); i++) {
                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket("line" + i, SIDEBAR, lines.size() - i,
                        java.util.Optional.of(Text.literal(lines.get(i))), java.util.Optional.of(BlankNumberFormat.INSTANCE)));
            }
            for (int i = lines.size(); i < was.lines().size(); i++) player.networkHandler.sendPacket(new ScoreboardScoreResetS2CPacket("line" + i, SIDEBAR));
        }
        sidebars.put(viewer, new Shown(matchId, sidebar.title(), List.copyOf(lines)));
    }

    private ScoreboardObjective objective(String title) {
        return new ScoreboardObjective(board, SIDEBAR, ScoreboardCriterion.DUMMY, Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD),
                ScoreboardCriterion.RenderType.INTEGER, false, BlankNumberFormat.INSTANCE);
    }

    /** A viewer's sidebar taken down. */
    private void clearSidebar(UUID viewer) {
        var was = sidebars.remove(viewer);
        var player = server.getPlayerManager().getPlayer(viewer);
        if (was == null || player == null) return;
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective(was.title()), ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
    }

    /** A burst of particles round a player, seen by everyone near. */
    private void burst(UUID playerId, String kind) {
        var player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return;
        bursts.computeIfAbsent(playerId, id -> new ArrayList<>()).add(kind);
        var world = player.getServerWorld();
        switch (kind) {
            case "start" -> world.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 1, player.getZ(), 30, 0.6, 0.8, 0.6, 0.05);
            case "win" -> world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 60, 0.6, 1.0, 0.6, 0.3);
            default -> { }
        }
    }

    /** A match changed state: the fight's start or each player's result. */
    void changed(MatchRegistry.Match match, Function<UUID, String> names) {
        switch (match.state()) {
            case RUNNING -> {
                for (UUID invited : match.invited()) {
                    show(invited, MatchPresentation.start(match, invited));
                    if (match.joined().contains(invited)) burst(invited, "start");
                }
            }
            case ENDED -> {
                for (UUID invited : match.invited()) show(invited, MatchPresentation.result(match, invited, names));
                // Onlookers see who won as well.
                for (UUID watcher : onlookers.getOrDefault(match.id(), List.of())) show(watcher, MatchPresentation.result(match, watcher, names));
                for (UUID winner : match.winners()) burst(winner, "win");
                var bar = bars.remove(match.id());
                if (bar != null) bar.clearPlayers();
                // The sidebar shows the result a moment longer, then comes down with the next look round.
                var sidebar = MatchPresentation.sidebar(match, server.getTicks(), names, player -> "");
                var everyone = new ArrayList<UUID>(match.invited());
                everyone.addAll(onlookers.getOrDefault(match.id(), List.of()));
                for (UUID viewer : everyone) {
                    var shown = sidebars.get(viewer);
                    if (shown != null && shown.match().equals(match.id())) sidebar(viewer, match.id(), sidebar);
                }
            }
            default -> { }
        }
    }

    /** A split reached in a race: every runner told in chat and over its hotbar, and the runner who reached it a ping. */
    void split(MatchRegistry.Match match, dev.kindred.core.match.SpeedrunSplits.Reached reached, Function<UUID, String> names) {
        String line = MatchPresentation.split(reached, names);
        for (UUID invited : match.invited()) {
            var player = server.getPlayerManager().getPlayer(invited);
            if (player == null) continue;
            player.sendMessage(Text.literal(line).formatted(Formatting.AQUA), false);
            player.sendMessage(Text.literal(line).formatted(Formatting.AQUA), true);
            splitShown.put(invited, (long) server.getTicks());
            if (invited.equals(reached.runner())) player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.4f);
        }
    }

    /** Every bar taken down, as the server stops. */
    void clear() {
        for (var bar : bars.values()) bar.clearPlayers();
        bars.clear();
        for (UUID viewer : new ArrayList<>(sidebars.keySet())) clearSidebar(viewer);
        held.clear();
    }

    /** The titles sent to {@code player}, oldest first, each with whether it was alive to see it. */
    List<Sent> sent(UUID player) {
        var titles = sent.get(player);
        return titles == null ? List.of() : List.copyOf(titles);
    }

    /** The sidebar lines {@code player} was last sent, or none. */
    List<String> sidebarLines(UUID player) {
        var shown = sidebars.get(player);
        return shown == null ? List.of() : shown.lines();
    }

    /** The onlookers a match's bar and sidebar go to now. */
    List<UUID> onlookers(UUID matchId) { return List.copyOf(onlookers.getOrDefault(matchId, List.of())); }

    /** The particle bursts shown round {@code player}: "start", "win". */
    List<String> bursts(UUID player) { return List.copyOf(bursts.getOrDefault(player, List.of())); }

    /** The titles last shown to {@code player}, oldest first. */
    List<String> shown(UUID player) {
        var titles = shown.get(player);
        return titles == null ? List.of() : List.copyOf(titles);
    }

    /** The players a match's bar shows to, for a fixture. */
    List<UUID> barPlayers(UUID matchId) {
        var bar = bars.get(matchId);
        return bar == null ? List.of() : bar.getPlayers().stream().map(ServerPlayerEntity::getUuid).toList();
    }

    /** The words a match's bar shows now, for checking. */
    String barText(UUID matchId) {
        var bar = bars.get(matchId);
        return bar == null ? "" : bar.getName().getString();
    }

    /** Who else hears of each title shown (F-34c: the Wire, for an AI player's own), by the player's body id. */
    private java.util.function.BiConsumer<UUID, MatchPresentation.Title> onShown = (player, title) -> { };

    /** Sets who hears of each title as it is shown. */
    void onShown(java.util.function.BiConsumer<UUID, MatchPresentation.Title> sink) {
        onShown = sink == null ? (player, title) -> { } : sink;
    }

    /** One title shown to one player, with its sound, and kept among what it was shown. */
    void show(UUID playerId, MatchPresentation.Title title) {
        onShown.accept(playerId, title);
        var titles = shown.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        if (titles.size() == SHOWN_KEPT) titles.removeFirst();
        titles.addLast(title.title());
        var player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return;
        // A dead player's screen is the death screen, which covers any title: it is held until the player is back.
        if (!player.isAlive()) {
            held.computeIfAbsent(playerId, id -> new ArrayDeque<>()).addLast(title);
            return;
        }
        send(player, title);
    }

    /** A title and its sound sent to a player now. */
    private void send(ServerPlayerEntity player, MatchPresentation.Title title) {
        var record = sent.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        if (record.size() == SHOWN_KEPT) record.removeFirst();
        record.addLast(new Sent(title.title(), player.isAlive()));
        Formatting colour = switch (title.cue()) {
            case COUNT -> Formatting.YELLOW;
            case START, WIN -> Formatting.GREEN;
            case LOSE -> Formatting.RED;
            case DRAW -> Formatting.GRAY;
        };
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(3, title.cue() == MatchPresentation.Cue.COUNT ? 14 : 40, 6));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(title.subtitle())));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title.title()).formatted(colour, Formatting.BOLD)));
        switch (title.cue()) {
            case COUNT -> player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.MASTER, 1f, 1f);
            case START -> player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.MASTER, 1f, 2f);
            case WIN -> player.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
            case LOSE -> player.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1f, 1f);
            case DRAW -> player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.MASTER, 1f, 1f);
        }
    }
}
