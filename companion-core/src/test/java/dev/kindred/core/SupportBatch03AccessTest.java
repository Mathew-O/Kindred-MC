package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks who may give a player orders: the list of those allowed cannot be changed from
 * outside once set, the server console always may, and the player's own identity gives no control over it.
 */
class SupportBatch03AccessTest {

    @Test void controllerSetCannotBeMutatedThroughOriginalOrAccessor() {
        UUID controller=UUID.randomUUID(),stranger=UUID.randomUUID();var ids=new HashSet<UUID>();ids.add(controller);var access=new PlayerAccess(UUID.randomUUID(),ids);
        ids.clear();ids.add(stranger);assertTrue(access.mayControl(Actor.player(controller)));assertFalse(access.mayControl(Actor.player(stranger)));
        assertTrue(access.mayControl(Actor.console()));assertThrows(UnsupportedOperationException.class,()->access.controllers().add(stranger));
    }
    @Test void identityAloneDoesNotGrantAdministrativeControl() {
        UUID player=UUID.randomUUID();var access=new PlayerAccess(player,Set.of());assertFalse(access.mayControl(Actor.player(player)));assertTrue(access.mayControl(Actor.console()));
    }

}
