package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch04SkillProposalTest {

    private SkillDescriptor descriptor(){return new SkillDescriptor("move_to",Set.of("x"),Set.of("note"),Set.of(),Set.of(),Duration.ofSeconds(2),Duration.ofSeconds(1),2);}
    @Test void strictArgumentsAreBoundedCopiedAndCannotSmuggleUnknownIntent() {
        var d=descriptor();var args=new HashMap<String,String>();args.put("x","1");args.put("note","x".repeat(256));var validated=d.validate(args);args.put("x","2");assertEquals("1",validated.get("x"));
        assertThrows(IllegalArgumentException.class,()->d.validate(Map.of("x","1","exec","rm")));assertThrows(IllegalArgumentException.class,()->d.validate(Map.of()));
        assertThrows(IllegalArgumentException.class,()->d.validate(Map.of("x","x".repeat(257))));assertThrows(UnsupportedOperationException.class,()->validated.clear());
    }
    @Test void plansRequireOneToSixInstalledSkillsAndAllFences() {
        var installed=new HashSet<>(Set.of("wait"));var gate=new ProposalGate(installed);installed.add("invent");
        assertTrue(gate.reject(Collections.nCopies(6,"wait"),true,true,false).isEmpty());assertFalse(gate.reject(Collections.nCopies(7,"wait"),true,true,false).isEmpty());
        assertFalse(gate.reject(List.of("invent"),true,true,false).isEmpty());assertFalse(gate.reject(null,true,true,false).isEmpty());
        assertEquals(3,gate.reject(List.of("wait"),false,false,true).size());assertFalse(gate.reject(Arrays.asList((String)null),true,true,false).isEmpty());
    }

}
