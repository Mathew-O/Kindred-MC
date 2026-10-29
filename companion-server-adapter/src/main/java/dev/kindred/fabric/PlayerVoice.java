package dev.kindred.fabric;

import dev.kindred.core.ConversationAudience;
import dev.kindred.core.Personality;
import dev.kindred.model.ConversationPrompt;
import dev.kindred.model.Strategist;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.TridentItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * In plain terms: the player's voice. Spoken to, it has the optional model phrase a short reply from what it knows
 * right now, told only what this listener may know; whatever goes wrong, its usual fixed reply is said instead. It only
 * ever chooses words.
 *
 * Technical: The adapter side of E-20a. Gathers a {@link ConversationAudience.Situation} from the player's own state
 * (its body, its task and goal, its run's recent events, its memory of this world) and the runtime's {@link Facts}, has
 * {@link ConversationAudience} withhold what this listener may not know, and asks {@link StrategistBridge#converse}.
 * Holds the last six lines between each player and each speaker for five minutes, in memory only. Runs on the server
 * thread; the bridge's answer comes back to it, and an answer that has not come within the chat lane's deadline and a
 * second's grace is taken as none.
 */
final class PlayerVoice implements CompanionSocials.Voice {
    static final int TRANSCRIPT_LINES = 6;
    static final long TRANSCRIPT_MILLIS = 300_000;
    private static final double SEEN_RANGE = 16;

    /** What the runtime knows of a player as it is spoken to, beyond what its body shows. */
    record Facts(String name, Personality personality, ConversationAudience.Relation relation, boolean controls,
                 boolean stopped, boolean busy, String doing, String goal, String goalProgress, List<String> events,
                 String lastSaid, List<String> remembered) { }

    /** The runtime's side: the facts of a player spoken to by {@code speaker}, or null when it cannot be voiced now. */
    interface Source {
        Facts facts(UUID playerId, ServerPlayerEntity speaker);
    }

    /**
     * The runtime's side of an ask (E-20b): {@code ask} of the player from {@code speaker}, done as the matching command
     * does it, with the speaker's own authority, once the line asking it is said; a refusal handed to {@code refused} in
     * the command's own words.
     */
    interface Asks {
        void ask(UUID playerId, ServerPlayerEntity speaker, Strategist.Ask ask, String item, Consumer<String> refused);
    }

    private record Said(long at, String line) { }

    private final Source source;
    private final Asks asks;
    private final Supplier<Optional<StrategistBridge>> bridge;
    private final MinecraftServer server;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Said>> transcripts = new LinkedHashMap<>();

    PlayerVoice(Source source, Asks asks, Supplier<Optional<StrategistBridge>> bridge, MinecraftServer server, LongSupplier clock) {
        this.source = source;
        this.asks = asks;
        this.bridge = bridge;
        this.server = server;
        this.clock = clock;
    }

    /** Whether the voice is on: unless {@code KINDRED_VOICE=off}, whenever the strategist is. */
    static boolean switchedOn(Map<String, String> environment) {
        return !"off".equalsIgnoreCase(String.valueOf(environment.get("KINDRED_VOICE")).trim());
    }

    @Override
    public boolean answer(ServerPlayerEntity speaker, CompanionSocials.Participant player, String message,
                          Consumer<Optional<CompanionSocials.Spoken>> answered) {
        var lane = bridge.get();
        if (lane.isEmpty()) return false;
        Facts facts = source.facts(player.id(), speaker);
        if (facts == null) return false;
        var situation = situation(facts, player.body(), speaker, transcript(player.id(), speaker.getUuid()));
        var conversation = ConversationPrompt.conversation(facts.name(), facts.personality(), situation, message);
        var asked = lane.get().converse(player.id(), facts.name(), speaker.getName().getString(), conversation, server);
        if (asked.isDone() && asked.join().isEmpty()) return false; // Declined at once (budgets, rates, a line already out).
        // Never left waiting: past the deadline and a second's grace it is taken as no line, and the fixed reply is said.
        asked.completeOnTimeout(Optional.empty(), Strategist.CHAT_DEADLINE.toMillis() + 1_000, TimeUnit.MILLISECONDS)
                .thenAccept(line -> server.execute(() -> answered.accept(line.flatMap(said -> said.say().map(text -> spoken(text, said, player, speaker))))));
        return true;
    }

    /** The line to say, and the ask it carries, if any, to do once it is said (E-20b). */
    private CompanionSocials.Spoken spoken(String text, Strategist.Line line, CompanionSocials.Participant player, ServerPlayerEntity speaker) {
        if (line.ask() == Strategist.Ask.NONE) return new CompanionSocials.Spoken(text, null);
        return new CompanionSocials.Spoken(text, refused -> asks.ask(player.id(), speaker, line.ask(), line.item().orElse(null), refused));
    }

    @Override
    public void replied(ServerPlayerEntity speaker, CompanionSocials.Participant player, String said, String spoken) {
        if (bridge.get().isEmpty()) return; // No voice, no talk kept.
        long now = clock.getAsLong();
        var lines = transcripts.computeIfAbsent(key(player.id(), speaker.getUuid()), ignored -> new ArrayDeque<>());
        lines.addLast(new Said(now, speaker.getName().getString() + ": " + said));
        lines.addLast(new Said(now, player.name() + ": " + spoken));
        while (lines.size() > TRANSCRIPT_LINES) lines.removeFirst();
        while (transcripts.size() > 256) transcripts.remove(transcripts.keySet().iterator().next());
    }

    /** The talk so far between a player and a speaker, oldest first, within the last five minutes. */
    List<String> transcript(UUID playerId, UUID speakerId) {
        var lines = transcripts.get(key(playerId, speakerId));
        if (lines == null) return List.of();
        long now = clock.getAsLong();
        lines.removeIf(said -> now - said.at() > TRANSCRIPT_MILLIS);
        return lines.stream().map(Said::line).toList();
    }

    private static String key(UUID playerId, UUID speakerId) { return playerId + ":" + speakerId; }

    /** What the player knows as it is spoken to: the runtime's facts and what its body shows. */
    static ConversationAudience.Situation situation(Facts facts, LivingEntity body, ServerPlayerEntity speaker, List<String> transcript) {
        int food = body instanceof ServerPlayerEntity player ? player.getHungerManager().getFoodLevel() : 20;
        var world = body.getWorld();
        String dimension = switch (world.getRegistryKey().getValue().getPath()) {
            case "overworld" -> "the Overworld";
            case "the_nether" -> "the Nether";
            case "the_end" -> "the End";
            default -> world.getRegistryKey().getValue().getPath();
        };
        long time = world.getTimeOfDay() % 24_000;
        String daytime = world.getRegistryKey() != net.minecraft.world.World.OVERWORLD ? ""
                : time < 12_000 ? "day" : time < 13_000 ? "evening" : time < 23_000 ? "night" : "dawn";
        String weather = world.getRegistryKey() != net.minecraft.world.World.OVERWORLD ? "" : world.isThundering() ? "a thunderstorm" : world.isRaining() ? "rain" : "clear";
        var pos = body.getBlockPos();
        String position = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        var box = body.getBoundingBox().expand(SEEN_RANGE);
        String threat = world.getEntitiesByClass(HostileEntity.class, box, mob -> mob.isAlive() && body.canSee(mob)).stream()
                .min(java.util.Comparator.comparingDouble(body::squaredDistanceTo)).map(mob -> mob.getName().getString()).orElse("");
        var near = new ArrayList<String>();
        for (var other : world.getPlayers()) {
            if (other == body || !other.isAlive() || other.isSpectator() || other.squaredDistanceTo(body) > SEEN_RANGE * SEEN_RANGE) continue;
            if (near.size() < 6) near.add(other.getName().getString());
        }
        var held = new LinkedHashSet<String>();
        int foodItems = 0;
        var notable = new LinkedHashMap<String, Integer>();
        if (body instanceof ServerPlayerEntity player) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty()) continue;
                var item = stack.getItem();
                if (item instanceof ToolItem || item instanceof BowItem || item instanceof CrossbowItem || item instanceof ShieldItem || item instanceof TridentItem) {
                    if (held.size() < 6) held.add(stack.getName().getString().toLowerCase(java.util.Locale.ROOT));
                } else if (stack.contains(DataComponentTypes.FOOD)) {
                    foodItems += stack.getCount();
                } else if (NOTABLE.contains(item)) {
                    notable.merge(stack.getName().getString().toLowerCase(java.util.Locale.ROOT), stack.getCount(), Integer::sum);
                }
            }
        }
        var notables = notable.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList();
        return new ConversationAudience.Situation(facts.name(), speaker.getName().getString(), facts.relation(), facts.controls(),
                body.getHealth(), food, hurtBy(body), dimension, daytime, weather, facts.stopped(), facts.busy(), facts.doing(),
                facts.goal(), facts.goalProgress(), position, threat, near, List.copyOf(held), foodItems, notables,
                facts.events(), facts.remembered(), facts.lastSaid(), transcript);
    }

    private static final java.util.Set<net.minecraft.item.Item> NOTABLE = java.util.Set.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.OBSIDIAN, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.ENDER_EYE, Items.NETHERITE_INGOT, Items.ANCIENT_DEBRIS,
            Items.FLINT_AND_STEEL, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET);

    /** What hurt it last, as the game recorded it, while that is recent: who, or what kind of harm. */
    private static String hurtBy(LivingEntity body) {
        var source = body.getRecentDamageSource();
        if (source == null) return "";
        if (source.getAttacker() != null) return source.getAttacker().getName().getString();
        return source.getType().msgId().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
    }

    /** A task as the player would say it is at it. */
    static String doing(String skillId) {
        if (skillId == null || skillId.isEmpty()) return "";
        return switch (skillId) {
            case "native_gather" -> "gathering logs";
            case "native_mine" -> "mining";
            case "native_craft" -> "crafting";
            case "native_smelt" -> "smelting";
            case "native_place" -> "placing blocks";
            case "native_wait" -> "waiting";
            case "native_eat" -> "eating";
            case "native_attack" -> "fighting";
            case "native_block" -> "blocking with a shield";
            case "native_retreat" -> "backing off";
            case "native_goto" -> "walking somewhere";
            case "native_plant" -> "planting";
            case "native_till" -> "tilling the ground";
            case "native_ignite_portal" -> "lighting a portal";
            default -> skillId.replaceFirst("^native_", "").replace('_', ' ');
        };
    }

    /** A goal as the player would name it. */
    static String goal(Enum<?> goal) {
        if (goal == null || goal.name().equals("NONE")) return "";
        return goal.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
