package com.byeolnaerim.mongodsl.sync;


import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.byeolnaerim.mongodsl.internal.cache.InMemoryExpirationWheel;
import reactor.core.publisher.Mono;


/**
 * Default process-local embedded-sync lease store.
 * <p>Suitable for a single application process. Load-balanced deployments should provide a shared
 * {@link EmbeddedSyncLeaseStore} implementation such as one backed by Redis.</p>
 */
public final class InMemoryEmbeddedSyncLeaseStore implements EmbeddedSyncLeaseStore {

	private static final long EXPIRED = Long.MIN_VALUE;

	private static final class LeaseState {

		private final String ownerId;

		private final AtomicLong expiresAtNanos;

		private LeaseState(
			String ownerId, long expiresAtNanos
		) {

			this.ownerId = ownerId;
			this.expiresAtNanos = new AtomicLong( expiresAtNanos );

		}

	}

	private final ConcurrentHashMap<String, LeaseState> leases = new ConcurrentHashMap<>();

	private final InMemoryExpirationWheel<String, LeaseState> expirationWheel = new InMemoryExpirationWheel<>(
		Duration.ofSeconds( 1 ),
		256,
		state -> state.expiresAtNanos.get(),
		this::expire
	);

	@Override
	public Mono<Boolean> tryAcquire(
		String leaseKey, String ownerId, Duration ttl
	) {

		return Mono.fromSupplier( () -> {
			Objects.requireNonNull( leaseKey, "leaseKey must not be null" );
			Objects.requireNonNull( ownerId, "ownerId must not be null" );
			validateTtl( ttl );
			long now = System.nanoTime();
			long nextDeadline = now + ttl.toNanos();
			boolean[] created = new boolean[1];
			LeaseState state = leases.compute( leaseKey, (key, current) -> {
				if (current == null || current.expiresAtNanos.get() == EXPIRED || now >= current.expiresAtNanos.get()) {
					created[0] = true;
					return new LeaseState( ownerId, nextDeadline );

				}
				if (current.ownerId.equals( ownerId ))
					current.expiresAtNanos.set( nextDeadline );
				return current;

			} );
			if (created[0])
				expirationWheel.schedule( leaseKey, state );
			return state.ownerId.equals( ownerId );

		} );

	}

	@Override
	public Mono<Boolean> renew(
		String leaseKey, String ownerId, Duration ttl
	) {

		return Mono.fromSupplier( () -> {
			validateTtl( ttl );
			LeaseState state = leases.get( leaseKey );
			if (state == null || ! state.ownerId.equals( ownerId ))
				return false;
			for (;;) {
				long deadline = state.expiresAtNanos.get();
				long now = System.nanoTime();
				if (deadline == EXPIRED || now >= deadline)
					return false;
				if (state.expiresAtNanos.compareAndSet( deadline, now + ttl.toNanos() ))
					return true;

			}

		} );

	}

	@Override
	public Mono<Void> release(
		String leaseKey, String ownerId
	) {

		return Mono.fromRunnable( () -> {
			LeaseState state = leases.get( leaseKey );
			if (state == null || ! state.ownerId.equals( ownerId ))
				return;
			long deadline = state.expiresAtNanos.getAndSet( EXPIRED );
			if (deadline != EXPIRED)
				leases.remove( leaseKey, state );

		} );

	}

	private void expire(
		String leaseKey, LeaseState state
	) {

		long deadline = state.expiresAtNanos.get();
		long now = System.nanoTime();
		if (deadline == EXPIRED)
			return;
		if (now < deadline) {
			expirationWheel.schedule( leaseKey, state );
			return;

		}
		if (state.expiresAtNanos.compareAndSet( deadline, EXPIRED ))
			leases.remove( leaseKey, state );

	}

	private static void validateTtl(
		Duration ttl
	) {

		if (ttl == null || ttl.isZero() || ttl.isNegative())
			throw new IllegalArgumentException( "ttl must be > 0" );

	}

	@Override
	public void close() {

		expirationWheel.close();
		leases.clear();

	}

}
