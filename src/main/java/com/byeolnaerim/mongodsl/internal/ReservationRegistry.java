package com.byeolnaerim.mongodsl.internal;


import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;


/**
 * Process-local registry that multiplexes identical active live reservations onto one materialized
 * stream. An entry exists only while at least one downstream subscriber is attached. Once the last
 * subscriber leaves, the entry is removed so a later subscriber creates a fresh replay/source pair
 * instead of reusing a disconnected {@code replay(1)} publisher.
 */
public final class ReservationRegistry {

	private static final class Entry {
		private final Flux<?> stream;
		private final AtomicInteger subscribers = new AtomicInteger();

		private Entry(
			Flux<?> stream
		) {
			this.stream = stream;
		}
	}

	private final ConcurrentHashMap<Object, Entry> entries = new ConcurrentHashMap<>();
	private final Object lifecycleLock = new Object();

	@SuppressWarnings("unchecked")
	public <T> Flux<T> share(
		Object key, Supplier<Flux<T>> sourceFactory
	) {
		Objects.requireNonNull( key, "key must not be null" );
		Objects.requireNonNull( sourceFactory, "sourceFactory must not be null" );
		return Flux.defer( () -> {
			Entry entry;
			synchronized (lifecycleLock) {
				entry = entries.computeIfAbsent( key, ignored -> {
					Flux<T> source = Objects.requireNonNull( sourceFactory.get(), "sourceFactory returned null" );
					return new Entry( source.replay( 1 ).refCount( 1 ) );
				} );
				entry.subscribers.incrementAndGet();
			}

			Flux<T> stream = (Flux<T>) entry.stream;
			AtomicBoolean released = new AtomicBoolean();
			return stream.doFinally( ignored -> {
				if (! released.compareAndSet( false, true ))
					return;
				synchronized (lifecycleLock) {
					int remaining = entry.subscribers.decrementAndGet();
					if (remaining <= 0)
						entries.remove( key, entry );
				}
			} );
		} );
	}

	public void clear() {
		synchronized (lifecycleLock) {
			entries.clear();
		}
	}

}
