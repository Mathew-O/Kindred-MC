package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;

import java.util.EnumSet;
import java.util.Set;

/**
 * In plain terms: which channels each way of playing uses: Co-op its team and private messages; Manhunt each side's
 * own channel; Speedrun only public posts; Builder public plots and private designs; Factions all of them. The
 * operators' channel is open in every world.
 *
 * Technical: F-34's per-mode table. A world with no mode chosen keeps every channel, as before modes existed.
 * {@link Channel#PUBLIC} in Co-op and Manhunt carries the mode's own announcements and earshot notices.
 */
public final class ModeChannels {
    private ModeChannels() { }

    public static Set<Channel> of(GameModes.WorldMode mode) {
        if (mode == null) return EnumSet.allOf(Channel.class);
        return switch (mode) {
            case COOP_SURVIVAL -> EnumSet.of(Channel.TEAM, Channel.PRIVATE, Channel.PUBLIC, Channel.OPERATOR);
            case MANHUNT -> EnumSet.of(Channel.SIDE, Channel.PRIVATE, Channel.PUBLIC, Channel.OPERATOR);
            case SPEEDRUN -> EnumSet.of(Channel.PUBLIC, Channel.OPERATOR);
            case BUILDER -> EnumSet.of(Channel.PUBLIC, Channel.PRIVATE, Channel.OPERATOR);
            case FACTIONS -> EnumSet.allOf(Channel.class);
        };
    }

    public static boolean allows(GameModes.WorldMode mode, Channel channel) { return of(mode).contains(channel); }
}
