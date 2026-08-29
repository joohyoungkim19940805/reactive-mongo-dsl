package com.byeolnaerim.mongodsl.state;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.byeolnaerim.mongodsl.internal.cache.InMemoryExpirationWheel;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;


final class CursorAdmissionTracker implements AutoCloseable {

	private static final long EXPIRED = Long.MIN_VALUE;

	private static final class State {

		private final AtomicLong expiresAtNanos = new AtomicLong();

		private final AtomicInteger windowHits = new AtomicInteger();

		private volatile long windowStartedAtNanos;

		private volatile boolean admitted;

	}

	private final CursorCacheOptions options;

	private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

	private final InMemoryExpirationWheel<String, State> expirationWheel;

	CursorAdmissionTracker(
		CursorCacheOptions options
	) {

		this.options = options == null ? CursorCacheOptions.defaults() : options;
		this.expirationWheel = new InMemoryExpirationWheel<>(
			this.options.expirationTick(),
			this.options.expirationWheelSize(),
			state -> state.expiresAtNanos.get(),
			this::expire
		);

	}

	boolean admit(
		String queryKey, long estimatedSkip
	) {

		for (;;) {
			long now = System.nanoTime();
			State existing = states.get( queryKey );
			if (existing != null) {
				if (! touch( existing )) {
					states.remove( queryKey, existing );
					continue;

				}
				admitIfNeeded( existing, now, estimatedSkip );
				return existing.admitted;

			}
			if (states.size() >= options.maxQueries())
				return estimatedSkip >= options.deepPageSkipThreshold();
			State created = new State();
			created.windowStartedAtNanos = now;
			created.windowHits.set( 1 );
			created.admitted = options.admissionThreshold() <= 1 || estimatedSkip >= options.deepPageSkipThreshold();
			created.expiresAtNanos.set( now + options.idleTtl().toNanos() );
			if (states.putIfAbsent( queryKey, created ) != null)
				continue;
			expirationWheel.schedule( queryKey, created );
			return created.admitted;

		}

	}

	boolean isAdmitted(
		String queryKey
	) {

		State state = states.get( queryKey );
		return state != null && touch( state ) && state.admitted;

	}

	private void admitIfNeeded(
		State state, long now, long estimatedSkip
	) {

		if (state.admitted)
			return;
		if (estimatedSkip >= options.deepPageSkipThreshold()) {
			state.admitted = true;
			return;

		}
		if (now - state.windowStartedAtNanos > options.admissionWindow().toNanos()) {
			state.windowStartedAtNanos = now;
			state.windowHits.set( 1 );
			return;

		}
		if (state.windowHits.incrementAndGet() >= options.admissionThreshold())
			state.admitted = true;

	}

	private boolean touch(
		State state
	) {

		for (;;) {
			long deadline = state.expiresAtNanos.get();
			if (deadline == EXPIRED)
				return false;
			if (state.expiresAtNanos.compareAndSet( deadline, System.nanoTime() + options.idleTtl().toNanos() ))
				return true;

		}

	}

	private void expire(
		String queryKey, State state
	) {

		long deadline = state.expiresAtNanos.get();
		if (deadline == EXPIRED)
			return;
		if (System.nanoTime() < deadline) {
			expirationWheel.schedule( queryKey, state );
			return;

		}
		if (state.expiresAtNanos.compareAndSet( deadline, EXPIRED ))
			states.remove( queryKey, state );

	}

	@Override
	public void close() {

		expirationWheel.close();
		states.clear();

	}

}
