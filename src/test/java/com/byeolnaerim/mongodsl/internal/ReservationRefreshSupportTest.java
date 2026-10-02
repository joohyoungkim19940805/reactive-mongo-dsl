package com.byeolnaerim.mongodsl.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ReservationRefreshSupportTest {

    @Test
    void burstDuringQueryBecomesOneFollowupWithoutCancellation() {
        var changes = Sinks.many().multicast().<Integer>directBestEffort();
        var first = Sinks.<Integer>one();
        var second = Sinks.<Integer>one();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        Flux<Integer> result = ReservationRefreshSupport.refresh(Mono.empty(), changes.asFlux(), () -> {
            int number = subscriptions.incrementAndGet();
            assertTrue(number <= 2, "invalidation burst must not enqueue 1000 refreshes");
            return (number == 1 ? first.asMono() : second.asMono()).doOnCancel(cancellations::incrementAndGet);
        });
        StepVerifier.create(result)
            .then(() -> {
                assertEquals(1, changes.currentSubscriberCount());
                for (int i = 0; i < 1000; i++) assertEquals(Sinks.EmitResult.OK, changes.tryEmitNext(i));
                assertEquals(1, subscriptions.get());
                assertEquals(0, cancellations.get());
                first.tryEmitValue(1);
            })
            .expectNext(1)
            .then(() -> {
                second.tryEmitValue(2);
            })
            .expectNext(2)
            .then(() -> assertEquals(Sinks.EmitResult.OK, changes.tryEmitComplete()))
            .expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(2, subscriptions.get());
        assertEquals(0, cancellations.get());
        assertEquals(0, changes.currentSubscriberCount());
    }

    @Test
    void changeRaisedBySynchronousInitialQueryIsNotLost() {
        var changes = Sinks.many().multicast().<Integer>directBestEffort();
        AtomicInteger count = new AtomicInteger();
        StepVerifier.create(ReservationRefreshSupport.refresh(Mono.empty(), changes.asFlux(), () -> {
            int value = count.incrementAndGet();
            if (value == 1) assertEquals(Sinks.EmitResult.OK, changes.tryEmitNext(10));
            return Mono.just(value);
        }).take(2)).expectNext(1, 2).verifyComplete();
        assertEquals(0, changes.currentSubscriberCount());
    }

    @Test
    void cancellationClosesBothInflightQueryAndChangeBridge() {
        var changes = Sinks.many().multicast().<Integer>directBestEffort();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        StepVerifier.create(ReservationRefreshSupport.refresh(Mono.empty(), changes.asFlux(),
            () -> Mono.<Integer>never()
                .doOnSubscribe(ignored -> subscriptions.incrementAndGet())
                .doOnCancel(cancellations::incrementAndGet)))
            .then(() -> {
                assertEquals(1, subscriptions.get());
                assertEquals(1, changes.currentSubscriberCount());
                assertEquals(0, cancellations.get());
            })
            .thenCancel().verify(Duration.ofSeconds(5));
        assertEquals(1, cancellations.get());
        assertEquals(0, changes.currentSubscriberCount());
    }

    @Test
    void queryErrorsAreNotSwallowedAndPreparationErrorsDoNotSubscribeChanges() {
        var changes = Sinks.many().multicast().<Integer>directBestEffort();
        StepVerifier.create(ReservationRefreshSupport.refresh(Mono.empty(), changes.asFlux(),
            () -> Mono.error(new IllegalStateException("query-failed"))))
            .expectErrorMessage("query-failed").verify(Duration.ofSeconds(5));
        assertEquals(0, changes.currentSubscriberCount());
        StepVerifier.create(ReservationRefreshSupport.refresh(Mono.error(new IllegalStateException("prepare-failed")),
            changes.asFlux(), () -> Mono.just(1)))
            .expectErrorMessage("prepare-failed").verify(Duration.ofSeconds(5));
        assertEquals(0, changes.currentSubscriberCount());
    }
}
