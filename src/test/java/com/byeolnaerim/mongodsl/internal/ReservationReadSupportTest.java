package com.byeolnaerim.mongodsl.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonTimestamp;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.support.ReservationTestContext;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReservationReadSupportTest {
    @Test
    void defaultsAndMajorityAreAllowedButExplicitAndInheritedUnsafeReadsAreNot() {
        var context = new ReservationTestContext();
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, null, null).isEmpty());
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, ReadPreference.primary(), ReadConcern.MAJORITY).isEmpty());
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, null, ReadConcern.LOCAL).isPresent());
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, ReadPreference.secondaryPreferred(), null).isPresent());
        context.readPreference = ReadPreference.nearest();
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, null, null).isPresent());
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, ReadPreference.primary(), null).isEmpty());
        context.readConcern = ReadConcern.AVAILABLE;
        assertTrue(ReservationReadSupport.unsupportedReason(context.database, ReadPreference.primary(), null).isPresent());
    }

    @Test
    void collationProbeDoesNotAssumeSimpleWhenMetadataIsDenied() {
        var context = new ReservationTestContext();
        assertEquals(true, ReservationReadSupport.hasSimpleCollation(context.database, "items").block());
        context.simpleCollation = false;
        assertEquals(false, ReservationReadSupport.hasSimpleCollation(context.database, "items").block());
        context.metadataFailure = true;
        assertEquals(false, ReservationReadSupport.hasSimpleCollation(context.database, "items").block());
    }

    @Test
    void causalFenceIsAppliedBeforeReadAndSessionIsClosed() {
        var context = new ReservationTestContext();
        var fence = new BsonTimestamp(20, 4);
        StepVerifier.create(ReservationReadSupport.causalRead(context, fence, session -> {
            assertEquals(fence, context.advancedTime.get());
            return Flux.just(1, 2);
        })).expectNext(1, 2).verifyComplete();
        assertEquals(1, context.sessionsClosed.get());
    }

    @Test
    void nonCausalSessionFailsRatherThanPretendingReadFenceWasHonored() {
        var context = new ReservationTestContext();
        context.causal = false;
        StepVerifier.create(ReservationReadSupport.causalRead(context, new BsonTimestamp(20, 0), session -> Mono.just(1)))
            .expectError(ReservationReadSupport.UnsafeSessionException.class).verify();
        assertEquals(1, context.sessionsClosed.get());
    }

    @Test
    void errorEmptyAndCancellationAllCloseTheSession() {
        var context = new ReservationTestContext();
        var fence = new BsonTimestamp(20, 0);
        StepVerifier.create(ReservationReadSupport.causalRead(context, fence, session -> Mono.empty())).verifyComplete();
        StepVerifier.create(ReservationReadSupport.causalRead(context, fence, session -> Mono.error(new IllegalStateException("read"))))
            .expectErrorMessage("read").verify();
        AtomicInteger readSubscriptions = new AtomicInteger();
        AtomicInteger readCancellations = new AtomicInteger();
        StepVerifier.create(ReservationReadSupport.causalRead(context, fence, session -> Mono.never()
                .doOnSubscribe(ignored -> readSubscriptions.incrementAndGet())
                .doOnCancel(readCancellations::incrementAndGet)))
            .then(() -> {
                assertEquals(1, readSubscriptions.get());
                assertEquals(2, context.sessionsClosed.get());
            })
            .thenCancel().verify(Duration.ofSeconds(3));
        assertEquals(1, readCancellations.get());
        assertEquals(3, context.sessionsClosed.get());
    }
}
