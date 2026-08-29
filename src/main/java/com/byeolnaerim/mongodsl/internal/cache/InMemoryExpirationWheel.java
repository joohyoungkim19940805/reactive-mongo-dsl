package com.byeolnaerim.mongodsl.internal.cache;


import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;


/** Package-private O(1)-touch timing wheel used by in-memory DSL state stores. */
public final class InMemoryExpirationWheel<K, V> implements AutoCloseable {

	private record ExpiryReference<K, V>(K key, V value) {}

	private final ConcurrentLinkedQueue<ExpiryReference<K, V>>[] buckets;

	private final long tickNanos;

	private final ToLongFunction<V> deadlineReader;

	private final BiConsumer<K, V> expirationHandler;

	private final AtomicInteger cursor;

	private final AtomicReference<Disposable> worker = new AtomicReference<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	@SuppressWarnings("unchecked")
	public InMemoryExpirationWheel(
		Duration tick,
		int wheelSize,
		ToLongFunction<V> deadlineReader,
		BiConsumer<K, V> expirationHandler
	) {

		if (tick == null || tick.isZero() || tick.isNegative())
			throw new IllegalArgumentException( "tick must be > 0" );
		if (wheelSize < 8)
			throw new IllegalArgumentException( "wheelSize must be >= 8" );

		this.tickNanos = tick.toNanos();
		this.cursor = new AtomicInteger();
		this.deadlineReader = Objects.requireNonNull( deadlineReader, "deadlineReader" );
		this.expirationHandler = Objects.requireNonNull( expirationHandler, "expirationHandler" );
		this.buckets = new ConcurrentLinkedQueue[wheelSize];

		for (int i = 0; i < wheelSize; i++)
			this.buckets[i] = new ConcurrentLinkedQueue<>();

	}

	/** Registers one outstanding expiry reference. Touches should update the value deadline only. */
	public void schedule(
		K key, V value
	) {

		if (closed.get())
			return;

		long deadline = deadlineReader.applyAsLong( value );
		buckets[bucketIndex( deadline )].offer( new ExpiryReference<>( key, value ) );
		ensureStarted();

	}

	private void ensureStarted() {

		if (closed.get() || worker.get() != null)
			return;

		long currentTick = Math.floorDiv( System.nanoTime(), tickNanos );
		cursor.set( (int) Math.floorMod( currentTick + 1L, buckets.length ) );
		Disposable candidate = Schedulers.parallel().schedulePeriodically(
			this::advance,
			tickNanos,
			tickNanos,
			TimeUnit.NANOSECONDS
		);
		if (closed.get() || ! worker.compareAndSet( null, candidate ))
			candidate.dispose();

	}

	private void advance() {

		if (closed.get())
			return;

		int index = cursor.getAndUpdate( current -> (current + 1) % buckets.length );
		ConcurrentLinkedQueue<ExpiryReference<K, V>> bucket = buckets[index];
		ExpiryReference<K, V> boundary = new ExpiryReference<>( null, null );
		bucket.offer( boundary );
		long now = System.nanoTime();

		for (;;) {
			ExpiryReference<K, V> reference = bucket.poll();
			if (reference == null || reference == boundary)
				break;

			long deadline = deadlineReader.applyAsLong( reference.value() );
			if (now >= deadline) {
				expirationHandler.accept( reference.key(), reference.value() );
				continue;
			}

			buckets[bucketIndex( deadline )].offer( reference );

		}

	}

	private int bucketIndex(
		long deadlineNanos
	) {

		long ticks = Math.floorDiv( deadlineNanos, tickNanos );
		if (Math.floorMod( deadlineNanos, tickNanos ) != 0L)
			ticks++;
		return (int) Math.floorMod( ticks, buckets.length );

	}

	@Override
	public void close() {

		if (! closed.compareAndSet( false, true ))
			return;
		Disposable disposable = worker.getAndSet( null );
		if (disposable != null)
			disposable.dispose();
		for (ConcurrentLinkedQueue<ExpiryReference<K, V>> bucket : buckets)
			bucket.clear();

	}

}
