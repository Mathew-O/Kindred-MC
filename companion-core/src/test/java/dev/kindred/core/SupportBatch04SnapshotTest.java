package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that what a player is shown about blocks and people nearby is its own copy: later
 * changes to the originals do not reach it, it cannot be changed, and it lists at most sixteen people.
 */
class SupportBatch04SnapshotTest {

    @Test void observationsOwnNestedMapsAndLists() {
        var counts=new HashMap<String,Integer>();counts.put("minecraft:stone",1);var exposed=new ArrayList<BlockObservation>();exposed.add(new BlockObservation(new WorkSetup.BlockPoint(0,0,0),"minecraft:stone",0));
        var section=new BlockObservations.SectionFacts(0,counts,exposed);var sections=new HashMap<SectionKey,BlockObservations.SectionFacts>();sections.put(new SectionKey(0,0,0),section);var interest=new HashSet<>(counts.keySet());
        var snapshot=new BlockObservations("minecraft:overworld",0,interest,sections);counts.clear();exposed.clear();sections.clear();interest.clear();
        assertEquals(1,snapshot.matchCount("minecraft:stone"));assertEquals(1,snapshot.exposedCount("minecraft:stone"));
        assertThrows(UnsupportedOperationException.class,()->snapshot.sections().clear());assertThrows(UnsupportedOperationException.class,()->section.matches().clear());
        var summary=snapshot.summary();summary.get("minecraft:stone")[0]=99;assertEquals(1,snapshot.matchCount("minecraft:stone"));
    }
    @Test void socialSnapshotCopiesContactsAndEnforcesBound() {
        var contacts=new ArrayList<SocialSnapshot.Contact>();contacts.add(new SocialSnapshot.Contact(UUID.randomUUID(),"visible",SocialSnapshot.Kind.PLAYER));
        var snapshot=new SocialSnapshot(true,false,false,false,null,contacts);contacts.clear();assertEquals(1,snapshot.contacts().size());
        assertThrows(UnsupportedOperationException.class,()->snapshot.contacts().clear());
        assertThrows(IllegalArgumentException.class,()->new SocialSnapshot(true,false,false,false,null,Collections.nCopies(17,snapshot.contacts().getFirst())));
    }

}
