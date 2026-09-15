package com.byeolnaerim.mongodsl.internal;


import java.util.Objects;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;


/** Sequential refreshes with one dirty flag, including invalidations during the initial read. */
public final class ReservationRefreshSupport {

	private ReservationRefreshSupport() {}

	public static <T> Flux<T> refresh(
		Mono<Void> preparation, Flux<?> changes, Supplier<Mono<T>> query
	) {
		Objects.requireNonNull( preparation, "preparation must not be null" );
		Objects.requireNonNull( changes, "changes must not be null" );
		Objects.requireNonNull( query, "query must not be null" );
		return preparation.thenMany( Flux.defer( () -> {
			Sinks.Many<Boolean> dirty = Sinks.many().unicast()
				.onBackpressureBuffer( new BoundedInvalidationQueue<>( 1, ignored -> true ) );
			// Subscribe BEFORE starting the initial query. An asynchronous or synchronous query may
			// overlap writes; all of them become at most one follow-up refresh, not cancellations.
			Disposable bridge = changes.subscribe(
				ignored -> dirty.tryEmitNext( true ), dirty::tryEmitError, dirty::tryEmitComplete
			);
			return Flux.concat( Mono.just( true ), dirty.asFlux() )
				.concatMap( ignored -> Mono.defer( query ), 0 )
				.doFinally( ignored -> bridge.dispose() );
		} ) );
	}

}
