package dev.kindred.fabric;

import dev.kindred.core.match.MatchPresentation;
import dev.kindred.core.match.MatchRegistry;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
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
import java.util.function.Function;

/**
 * Shows a match to the players in it (M-00b), as {@link MatchPresentation} works it out: titles with their sounds for
 * the countdown, the start and each player's result, and a boss bar with the players and the clock while it lasts.
 * All of it is what a vanilla client shows unaided. Server thread only.
 */
final class MatchPresenter {
    /** Titles kept for each player, most recent last: what a fixture or a status line reads back. */
    static final int SHOWN_KEPT = 8;

    private final MinecraftServer server;
    private final Map<UUID, ServerBossBar> bars = new HashMap<>();
    private final Map<UUID, ArrayDeque<String>> shown = new HashMap<>();
    /** The countdown title each match last showed, so each second is shown once whichever tick it starts on. */
    private final Map<UUID, String> counted = new HashMap<>();

    MatchPresenter(MinecraftServer server) { this.server = server; }

    /** Each tick: the bars kept up to date, and the countdown's seconds shown as they come. */
    void tick(List<MatchRegistry.Match> matches, long tick, Function<UUID, String> names, Function<UUID, Float> progress) {
        var live = new HashSet<UUID>();
        for (var match : matches) {
            if (match.state() == MatchRegistry.State.ENDED) continue;
            live.add(match.id());
            var bar = bars.computeIfAbsent(match.id(), id -> new ServerBossBar(Text.literal(""), BossBar.Color.RED, BossBar.Style.NOTCHED_10));
            if (tick % 10 == 0 || bar.getPlayers().isEmpty()) {
                var shownBar = MatchPresentation.bar(match, tick, names, progress.apply(match.id()));
                bar.setName(Text.literal(shownBar.text()));
                bar.setPercent(shownBar.progress());
                bar.setColor(match.state() == MatchRegistry.State.RUNNING ? BossBar.Color.RED : BossBar.Color.YELLOW);
                for (UUID invited : match.invited()) {
                    var player = server.getPlayerManager().getPlayer(invited);
                    if (player != null && !bar.getPlayers().contains(player)) bar.addPlayer(player);
                }
                for (var player : new ArrayList<>(bar.getPlayers())) {
                    if (player.isRemoved() || server.getPlayerManager().getPlayer(player.getUuid()) != player) bar.removePlayer(player);
                }
            }
            MatchPresentation.countdown(match, tick).ifPresent(title -> {
                if (title.title().equals(counted.put(match.id(), title.title()))) return;
                for (UUID invited : match.invited()) show(invited, title);
            });
        }
        counted.keySet().retainAll(live);
        bars.keySet().removeIf(id -> {
            if (live.contains(id)) return false;
            bars.get(id).clearPlayers();
            return true;
        });
    }

    /** A match changed state: the fight's start or each player's result. */
    void changed(MatchRegistry.Match match, Function<UUID, String> names) {
        switch (match.state()) {
            case RUNNING -> { for (UUID invited : match.invited()) show(invited, MatchPresentation.start(match, invited)); }
            case ENDED -> {
                for (UUID invited : match.invited()) show(invited, MatchPresentation.result(match, invited, names));
                var bar = bars.remove(match.id());
                if (bar != null) bar.clearPlayers();
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
            if (invited.equals(reached.runner())) player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.4f);
        }
    }

    /** Every bar taken down, as the server stops. */
    void clear() {
        for (var bar : bars.values()) bar.clearPlayers();
        bars.clear();
    }

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

    String barText(UUID matchId) {
        var bar = bars.get(matchId);
        return bar == null ? "" : bar.getName().getString();
    }

    /** One title shown to one player, with its sound, and kept among what it was shown. */
    void show(UUID playerId, MatchPresentation.Title title) {
        var titles = shown.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        if (titles.size() == SHOWN_KEPT) titles.removeFirst();
        titles.addLast(title.title());
        var player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return;
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
