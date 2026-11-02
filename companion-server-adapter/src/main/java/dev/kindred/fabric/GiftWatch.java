package dev.kindred.fabric;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In plain terms: a thing an AI player tossed to someone counts as given only once that person has picked it up, as the
 * game itself counts what a player picks up. If nobody picks it up within a minute and a half, it is noted as tossed and
 * not taken, and nothing is tossed again in its place: a dropped stack is not a gift received, and a second one could be a
 * copy.
 *
 * Technical: E-22a. Each toss through the toss method (a command's, an offer's yes) is watched: the recipient's own
 * {@code minecraft:picked_up} statistic for the item, read before the toss, is checked once a second; a rise of the count
 * tossed is the receipt, attributed to them alone. A receipt is recorded in the giver's ledger (E-50's {@code gave}) and
 * friendship (E-24a's {@code GAVE}), and in an AI recipient's too; no receipt by the deadline leaves an episode and
 * nothing else. Server thread only. A fixture may stage the pickup and must say so.
 */
final class GiftWatch {
    /** How long a toss waits to be picked up: ninety seconds. */
    static final long DEADLINE_TICKS = 1_800;

    /** What happens when a gift is received or left. */
    interface Outcome {
        void received(UUID giver, String giverName, ServerPlayerEntity to, String item, int count, String key);

        void left(UUID giver, String giverName, ServerPlayerEntity to, String item, int count);
    }

    private record Watch(UUID giver, String giverName, UUID to, Item item, int count, int pickedBefore, long until, String key) { }

    private final MinecraftServer server;
    private final Outcome outcome;
    private final List<Watch> watches = new ArrayList<>();
    private long sequence;
    private int received, left;
    private long deadline = DEADLINE_TICKS;

    GiftWatch(MinecraftServer server, Outcome outcome) {
        this.server = server;
        this.outcome = outcome;
    }

    /** A toss of {@code count} {@code item} from {@code giver} to {@code to} has started: watched for its pickup. */
    void watch(UUID giver, String giverName, ServerPlayerEntity to, Item item, int count) {
        long now = server.getTicks();
        String key = "gift:" + giver + ":" + to.getUuid() + ":" + now + ":" + (++sequence);
        watches.add(new Watch(giver, giverName, to.getUuid(), item, count, picked(to, item), now + deadline, key));
        while (watches.size() > 64) watches.removeFirst();
    }

    /** A turn, once a second: each toss picked up by its recipient is received; each past its deadline is left. */
    void tick() {
        long now = server.getTicks();
        watches.removeIf(w -> {
            ServerPlayerEntity to = server.getPlayerManager().getPlayer(w.to());
            if (to != null && picked(to, w.item()) - w.pickedBefore() >= w.count()) {
                received++;
                String item = Registries.ITEM.getId(w.item()).getPath().replace('_', ' ');
                KindredMod.LOGGER.info("{}'s gift of {} {} was picked up by {}", w.giverName(), w.count(), item, to.getName().getString());
                outcome.received(w.giver(), w.giverName(), to, item, w.count(), w.key());
                return true;
            }
            if (now > w.until()) {
                left++;
                String item = Registries.ITEM.getId(w.item()).getPath().replace('_', ' ');
                KindredMod.LOGGER.info("{}'s toss of {} {} was not picked up by {}: noted, nothing tossed again", w.giverName(), w.count(), item,
                        to == null ? w.to() : to.getName().getString());
                if (to != null) outcome.left(w.giver(), w.giverName(), to, item, w.count());
                return true;
            }
            return false;
        });
    }

    /** A shorter wait, staged by a fixture that cannot wait ninety seconds. */
    void deadlineForFixtures(long ticks) { deadline = ticks; }

    /** Gifts picked up, and tosses left, since start, for fixtures. */
    int receivedForFixtures() { return received; }

    int leftForFixtures() { return left; }

    /** Tosses still watched, for fixtures. */
    int watchingForFixtures() { return watches.size(); }

    private static int picked(ServerPlayerEntity player, Item item) {
        return player.getStatHandler().getStat(Stats.PICKED_UP.getOrCreateStat(item));
    }
}
