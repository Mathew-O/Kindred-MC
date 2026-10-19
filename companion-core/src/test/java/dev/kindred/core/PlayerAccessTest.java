package dev.kindred.core;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerAccessTest {
    @Test void grantsAreIndependentOfIdentityAndCopiedImmutably() {
        UUID player = UUID.randomUUID();
        UUID controller = UUID.randomUUID();
        var source = new HashSet<>(Set.of(controller));
        PlayerAccess access = new PlayerAccess(player, source);
        source.clear();
        assertTrue(access.mayControl(Actor.player(controller)));
        assertTrue(access.mayControl(Actor.console()));
        assertFalse(access.mayControl(Actor.player(player)));
        assertFalse(access.mayControl(Actor.player(UUID.randomUUID())));
        assertThrows(UnsupportedOperationException.class, () -> access.controllers().clear());
    }
}
