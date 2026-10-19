package dev.kindred.fabric;

import dev.kindred.core.Personality;
import dev.kindred.core.SurvivalPolicy;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Native GameTests for survival eating and defense behaviors (E-07).
 */
public final class SurvivalGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void companionEatsFoodWhenInjured(TestContext context) {
        CompanionEntity body = new CompanionEntity(KindredMod.COMPANION, context.getWorld());
        body.setUuid(UUID.randomUUID());
        body.refreshPositionAndAngles(1.5, 2.0, 1.5, 0, 0);
        context.getWorld().spawnEntity(body);

        // Injure companion to 10 HP (half health)
        body.setHealth(10.0f);

        // Put 5 bread in companion's inventory
        body.inventory().setStack(0, new ItemStack(Items.BREAD, 5));

        SurvivalBehavior survival = new SurvivalBehavior();
        List<String> spoken = new ArrayList<>();

        // Tick survival
        survival.tick(body, Personality.defaults(), 10000L, spoken::add);

        // Assert companion ate 1 bread and healed
        context.assertTrue(body.getHealth() > 10.0f, "Companion should have healed from eating food");
        context.assertEquals(4, body.inventory().getStack(0).getCount(), "Bread count should decrease by 1");
        context.assertFalse(spoken.isEmpty(), "Companion should have spoken an eating remark");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void companionIgnoresFoodWhenFullHealth(TestContext context) {
        CompanionEntity body = new CompanionEntity(KindredMod.COMPANION, context.getWorld());
        body.setUuid(UUID.randomUUID());
        body.refreshPositionAndAngles(1.5, 2.0, 1.5, 0, 0);
        context.getWorld().spawnEntity(body);

        // Companion is at full health (20 HP)
        body.setHealth(20.0f);

        // Put 5 cooked beef in companion's inventory
        body.inventory().setStack(0, new ItemStack(Items.COOKED_BEEF, 5));

        SurvivalBehavior survival = new SurvivalBehavior();
        List<String> spoken = new ArrayList<>();

        // Tick survival
        survival.tick(body, Personality.defaults(), 10000L, spoken::add);

        // Assert companion did not eat
        context.assertEquals(20.0f, body.getHealth(), "Health should remain at max");
        context.assertEquals(5, body.inventory().getStack(0).getCount(), "Beef count should not decrease");
        context.assertTrue(spoken.isEmpty(), "No remarks should be spoken when not eating");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void companionNeverDefendsAgainstWitherOrBoss(TestContext context) {
        Personality aggressive = Personality.defaults().withTrait(Personality.Trait.COMBATIVENESS, 100);

        for (String boss : SurvivalPolicy.FORBIDDEN_BOSSES) {
            SurvivalPolicy.Action action = SurvivalPolicy.evaluateThreat(boss, 3.0, 20.0f, 20.0f, aggressive);
            context.assertFalse(action == SurvivalPolicy.Action.DEFEND,
                    "Survival policy must never choose DEFEND against forbidden boss " + boss);
        }

        context.complete();
    }
}
