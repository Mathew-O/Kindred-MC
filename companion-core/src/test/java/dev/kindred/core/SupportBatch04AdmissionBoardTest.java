package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the limits on how many players may be active at once, how many each owner may run
 * and how many chunks they keep loaded: a request over a limit waits rather than joining a hidden queue, a
 * freed place can be taken again, and a bad or repeated request takes no extra place.
 */
class SupportBatch04AdmissionBoardTest {

    @Test void capacityAndPerPrincipalLimitsReleaseWithoutImplicitQueue() {
        var b=new AdmissionBoard(2,1,9,10);UUID a=UUID.randomUUID(),owner=UUID.randomUUID(),next=UUID.randomUUID();
        assertEquals(AdmissionBoard.Decision.ADMIT,b.request(a,owner,6));assertEquals(AdmissionBoard.Decision.WAITING,b.request(next,owner,1));
        assertEquals(AdmissionBoard.Decision.WAITING,b.request(next,UUID.randomUUID(),5));assertEquals(1,b.active());b.release(a);assertEquals(0,b.active());
        assertEquals(AdmissionBoard.Decision.ADMIT,b.request(next,owner,9));b.release(next);b.release(next);assertEquals(0,b.active());
    }
    @Test void invalidAndDuplicateRequestDoNotConsumeExtraSeats() {
        var b=new AdmissionBoard();UUID player=UUID.randomUUID(),owner=UUID.randomUUID();
        assertEquals(AdmissionBoard.Decision.REJECTED,b.request(player,owner,0));assertEquals(AdmissionBoard.Decision.REJECTED,b.request(player,owner,10));assertEquals(0,b.active());
        assertEquals(AdmissionBoard.Decision.ADMIT,b.request(player,owner,4));assertEquals(AdmissionBoard.Decision.ADMIT,b.request(player,owner,4));assertEquals(1,b.active());
        // Existing-seat identity/resize policy is unchanged; this test only repeats an identical request.
    }

}
