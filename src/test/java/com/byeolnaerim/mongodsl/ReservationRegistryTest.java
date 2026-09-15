package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.ReservationRegistry;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;


class ReservationRegistryTest {

	@Test
	void sharesOnlyWhileSubscribersAreActiveAndRebuildsAfterDisconnect() {

		ReservationRegistry registry = new ReservationRegistry();
		AtomicInteger sourceSubscriptions = new AtomicInteger();
		Flux<Integer> shared = registry.share(
			"same-query",
			() -> Flux.defer( () -> {
				sourceSubscriptions.incrementAndGet();
				return Flux.just( 1 ).concatWith( Flux.never() );
			} )
		);

		Disposable first = shared.subscribe();
		Disposable second = shared.subscribe();
		assertEquals( 1, sourceSubscriptions.get() );

		first.dispose();
		second.dispose();

		Disposable third = shared.subscribe();
		try {
			assertEquals( 2, sourceSubscriptions.get(), "a disconnected replay publisher must not be reused" );
		} finally {
			third.dispose();
		}

	}

	@Test
	void separateShareCallsForTheSameKeyUseOneActiveSource() {

		ReservationRegistry registry = new ReservationRegistry();
		AtomicInteger sourceSubscriptions = new AtomicInteger();
		java.util.function.Supplier<Flux<Integer>> source = () -> Flux.defer( () -> {
			sourceSubscriptions.incrementAndGet();
			return Flux.just( 7 ).concatWith( Flux.never() );
		} );

		Flux<Integer> firstShared = registry.share( "same-query", source );
		Flux<Integer> secondShared = registry.share( "same-query", source );
		Disposable first = firstShared.subscribe();
		Disposable second = secondShared.subscribe();
		try {
			assertEquals( 1, sourceSubscriptions.get() );
		} finally {
			first.dispose();
			second.dispose();
		}

	}

}
