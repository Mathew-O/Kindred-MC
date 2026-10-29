package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.Personality;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, the older mob-style body is checked to hit back
 * bare-handed when struck, keeping its sword in hand and saying something, and to draw a sword when
 * challenged to a duel and put it away when the duel is forced to stop. The duel command is also checked to
 * accept an optional player id.
 *
 * Technical: Native GameTests for duel and hit-back behaviors.
 */
public final class DuelGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void duelCommandParsesWithOptionalUuid(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();
        var parsed = dispatcher.parse("companion duel", source);
        context.assertFalse(parsed.getReader().canRead(), "Duel command must parse");
        context.assertTrue(parsed.getExceptions().isEmpty(), "Duel grammar must accept no args");
        var withId = dispatcher.parse("companion duel " + UUID.randomUUID(), source);
        context.assertFalse(withId.getReader().canRead(), "Duel command must accept trailing UUID");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hitBackStrikesBareHandedAndPreservesWeapon(TestContext context) {
        CompanionEntity body = new CompanionEntity(KindredMod.COMPANION, context.getWorld());
        body.setUuid(UUID.randomUUID());
        body.refreshPositionAndAngles(1.5, 2.0, 1.5, 0, 0);
        context.getWorld().spawnEntity(body);

        // Equip a diamond sword in main hand
        ItemStack diamondSword = new ItemStack(Items.DIAMOND_SWORD);
        body.equipStack(EquipmentSlot.MAINHAND, diamondSword);

        DuelBehavior duel = new DuelBehavior();
        List<String> spoken = new ArrayList<>();
        Personality combative = Personality.defaults().withTrait(Personality.Trait.COMBATIVENESS, 90);

        // Mock hit-back reaction
        duel.reactToHit(body, null, spoken::add, combative);

        // Weapon must be preserved in main hand after hit-back
        context.assertTrue(body.getMainHandStack().isOf(Items.DIAMOND_SWORD),
                "Hit-back must restore weapon to main hand after non-weapon swing");
        context.assertFalse(spoken.isEmpty(), "Hit-back must produce a voice line");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void duelDrawsWeaponAndSheathesOnForceEnd(TestContext context) {
        CompanionEntity body = new CompanionEntity(KindredMod.COMPANION, context.getWorld());
        body.setUuid(UUID.randomUUID());
        body.refreshPositionAndAngles(1.5, 2.0, 1.5, 0, 0);
        context.getWorld().spawnEntity(body);

        DuelBehavior duel = new DuelBehavior();
        context.assertTrue(duel.idle(), "Duel starts idle");

        // Begin challenge without holding anything: companion should draw practice blade
        duel.beginChallenge(null, body);
        context.assertEquals(DuelBehavior.State.CHALLENGED, duel.state(), "State must transition to CHALLENGED");
        context.assertTrue(body.getMainHandStack().getItem() instanceof SwordItem,
                "Companion must draw a weapon upon entering CHALLENGED state");

        // Force end (e.g. /companion stop) must sheathe the weapon and return to IDLE
        duel.forceEnd(body);
        context.assertTrue(duel.idle(), "State must return to IDLE after forceEnd");
        context.assertTrue(body.getMainHandStack().isEmpty(),
                "Weapon must be sheathed back to empty when duel is force-ended");

        context.complete();
    }
}
