package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch03ClaimsTest {

    @Test void expiredTokenCannotReleaseNewOwnerOrRenew() {
        var registry=new ClaimRegistry();var epoch=UUID.randomUUID();
        var old=registry.acquire(UUID.randomUUID(),epoch,List.of("a","b"),100,10).orElseThrow();
        assertTrue(registry.holdsAll(old,109));assertFalse(registry.holdsAll(old,110));
        var next=registry.acquire(UUID.randomUUID(),epoch,List.of("a","b"),110,10).orElseThrow();
        assertEquals(0,registry.release(old));assertTrue(registry.renew(old,111,10).isEmpty());assertTrue(registry.holdsAll(next,111));
    }
    @Test void multiKeyConflictDoesNotPartiallyAcquire() {
        var r=new ClaimRegistry();var epoch=UUID.randomUUID();var held=r.acquire(UUID.randomUUID(),epoch,List.of("b"),1,100).orElseThrow();
        assertTrue(r.acquire(UUID.randomUUID(),epoch,List.of("a","b"),2,100).isEmpty());assertTrue(r.holderOf("a",2).isEmpty());assertTrue(r.holdsAll(held,2));
    }
    @Test void restartInvalidatesOnlyOtherEpochs() {
        var r=new ClaimRegistry();var epoch=UUID.randomUUID();var a=r.acquire(UUID.randomUUID(),epoch,List.of("a"),0,100).orElseThrow();
        r.acquire(UUID.randomUUID(),UUID.randomUUID(),List.of("b"),0,100);assertEquals(1,r.invalidateOtherEpochs(epoch));assertTrue(r.holdsAll(a,1));
    }

}
