package com.byeolnaerim.mongodsl.paging;


import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonTimestamp;
import com.byeolnaerim.mongodsl.internal.cache.InMemoryExpirationWheel;
import reactor.core.publisher.Mono;


/**
 * Adaptive bounded process-local cursor anchor store.
 * <p>This is the zero-configuration default. It is intentionally not a distributed cache.</p>
 */
public final class InMemoryCursorAnchorStore implements CursorAnchorStore {

	private static final long EXPIRED = Long.MIN_VALUE;

	private static final class TokenState {

		private final CursorTokenState value;

		private final AtomicLong expiresAtNanos = new AtomicLong();

		private TokenState(
			CursorTokenState value, long expiresAtNanos
		) {

			this.value = value;
			this.expiresAtNanos.set( expiresAtNanos );

		}

	}

	private static final class QueryCursorState {

		private final AtomicLong expiresAtNanos = new AtomicLong();

		private final AtomicInteger windowHits = new AtomicInteger();

		private final AtomicInteger anchorCount = new AtomicInteger();

		private final ConcurrentSkipListMap<Integer, CursorAnchor> anchors = new ConcurrentSkipListMap<>();

		private volatile long windowStartedAtNanos;

		private volatile boolean admitted;

	}

	private final CursorCacheOptions options;

	private final ConcurrentHashMap<String, QueryCursorState> states = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, AtomicLong> namespaceVersions = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, AtomicReference<BsonTimestamp>> namespaceClusterTimes = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, TokenState> tokens = new ConcurrentHashMap<>();

	private final InMemoryExpirationWheel<String, QueryCursorState> expirationWheel;

	private final InMemoryExpirationWheel<String, TokenState> tokenExpirationWheel;

	public InMemoryCursorAnchorStore() {

		this( CursorCacheOptions.defaults() );

	}

	public InMemoryCursorAnchorStore(
		CursorCacheOptions options
	) {

		this.options = options == null ? CursorCacheOptions.defaults() : options;
		this.expirationWheel = new InMemoryExpirationWheel<>(
			this.options.expirationTick(),
			this.options.expirationWheelSize(),
			state -> state.expiresAtNanos.get(),
			this::expire
		);
		this.tokenExpirationWheel = new InMemoryExpirationWheel<>(
			this.options.expirationTick(),
			this.options.expirationWheelSize(),
			state -> state.expiresAtNanos.get(),
			this::expireToken
		);

	}

	@Override
	public CursorCacheOptions cursorCacheOptions() { return options; }

	@Override
	public Mono<Void> putToken(
		String token, CursorTokenState value, Duration ttl
	) {

		return Mono.fromRunnable( () -> {
			if (token == null || token.isBlank())
				throw new IllegalArgumentException( "token must not be blank" );
			if (value == null)
				throw new IllegalArgumentException( "cursor token state must not be null" );
			if (ttl == null || ttl.isZero() || ttl.isNegative())
				throw new IllegalArgumentException( "cursor token ttl must be > 0" );
			TokenState state = new TokenState( value, System.nanoTime() + ttl.toNanos() );
			tokens.put( token, state );
			tokenExpirationWheel.schedule( token, state );

		} );

	}

	@Override
	public Mono<Optional<CursorTokenState>> resolveToken(
		String token
	) {

		return Mono.fromSupplier( () -> {
			if (token == null || token.isBlank())
				return Optional.empty();
			TokenState state = tokens.get( token );
			if (state == null)
				return Optional.empty();
			long deadline = state.expiresAtNanos.get();
			if (deadline == EXPIRED || System.nanoTime() >= deadline) {
				if (state.expiresAtNanos.compareAndSet( deadline, EXPIRED ))
					tokens.remove( token, state );
				return Optional.empty();

			}
			return Optional.of( state.value );

		} );

	}

	@Override
	public Mono<Optional<CursorAnchor>> floor(
		String queryKey, int pageNumber, long estimatedSkip
	) {

		return Mono.fromSupplier( () -> {
			QueryCursorState state = stateForRequest( queryKey, estimatedSkip );
			if (state == null || ! state.admitted || pageNumber <= 0)
				return Optional.empty();
			var entry = state.anchors.floorEntry( pageNumber );
			return entry == null ? Optional.empty() : Optional.of( entry.getValue() );

		} );

	}

	@Override
	public Mono<Void> put(
		String queryKey, CursorAnchor anchor
	) {

		return Mono.fromRunnable( () -> {
			QueryCursorState state = states.get( queryKey );
			if (state == null || ! state.admitted || ! touch( state ))
				return;
			if (state.anchors.put( anchor.pageNumber(), anchor ) == null)
				state.anchorCount.incrementAndGet();
			while (state.anchorCount.get() > options.maxAnchorsPerQuery()) {
				if (state.anchors.pollFirstEntry() == null)
					break;
				state.anchorCount.decrementAndGet();

			}

		} );

	}

	private QueryCursorState stateForRequest(
		String queryKey, long estimatedSkip
	) {

		for (;;) {
			long now = System.nanoTime();
			QueryCursorState existing = states.get( queryKey );
			if (existing != null) {
				if (touch( existing )) {
					admitIfNeeded( existing, now, estimatedSkip );
					return existing;

				}
				states.remove( queryKey, existing );
				continue;

			}

			if (states.size() >= options.maxQueries())
				return null;

			QueryCursorState created = new QueryCursorState();
			created.windowStartedAtNanos = now;
			created.windowHits.set( 1 );
			created.admitted = options.admissionThreshold() <= 1 || estimatedSkip >= options.deepPageSkipThreshold();
			created.expiresAtNanos.set( now + options.idleTtl().toNanos() );
			if (states.putIfAbsent( queryKey, created ) != null)
				continue;

			expirationWheel.schedule( queryKey, created );
			return created;

		}

	}

	private void admitIfNeeded(
		QueryCursorState state, long now, long estimatedSkip
	) {

		if (state.admitted)
			return;
		if (estimatedSkip >= options.deepPageSkipThreshold()) {
			state.admitted = true;
			return;

		}

		long windowNanos = options.admissionWindow().toNanos();
		if (now - state.windowStartedAtNanos > windowNanos) {
			state.windowStartedAtNanos = now;
			state.windowHits.set( 1 );
			return;

		}

		if (state.windowHits.incrementAndGet() >= options.admissionThreshold())
			state.admitted = true;

	}

	private boolean touch(
		QueryCursorState state
	) {

		for (;;) {
			long deadline = state.expiresAtNanos.get();
			if (deadline == EXPIRED)
				return false;
			long nextDeadline = System.nanoTime() + options.idleTtl().toNanos();
			if (state.expiresAtNanos.compareAndSet( deadline, nextDeadline ))
				return true;

		}

	}

	private void expire(
		String queryKey, QueryCursorState state
	) {

		long deadline = state.expiresAtNanos.get();
		long now = System.nanoTime();

		if (deadline == EXPIRED)
			return;
		if (now < deadline) {
			expirationWheel.schedule( queryKey, state );
			return;

		}

		if (state.expiresAtNanos.compareAndSet( deadline, EXPIRED ))
			states.remove( queryKey, state );

	}

	private void expireToken(
		String token, TokenState state
	) {

		long deadline = state.expiresAtNanos.get();
		if (deadline == EXPIRED)
			return;
		if (System.nanoTime() < deadline) {
			tokenExpirationWheel.schedule( token, state );
			return;

		}
		if (state.expiresAtNanos.compareAndSet( deadline, EXPIRED ))
			tokens.remove( token, state );

	}

	@Override
	public Mono<Long> namespaceVersion(
		String namespaceKey
	) {

		return Mono.fromSupplier( () -> namespaceVersions.computeIfAbsent( namespaceKey, ignored -> new AtomicLong() ).get() );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey
	) {

		return Mono.fromRunnable( () -> namespaceVersions.computeIfAbsent( namespaceKey, ignored -> new AtomicLong() ).incrementAndGet() );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey, BsonTimestamp clusterTime
	) {

		if (clusterTime == null)
			return invalidateNamespace( namespaceKey );
		return Mono.fromRunnable( () -> {
			AtomicReference<BsonTimestamp> latest = namespaceClusterTimes.computeIfAbsent( namespaceKey, ignored -> new AtomicReference<>() );
			for (;;) {
				BsonTimestamp current = latest.get();
				if (current != null && compareTimestamp( current, clusterTime ) >= 0)
					return;
				if (latest.compareAndSet( current, clusterTime )) {
					namespaceVersions.computeIfAbsent( namespaceKey, ignored -> new AtomicLong() ).incrementAndGet();
					return;

				}

			}

		} );

	}

	private int compareTimestamp(
		BsonTimestamp left, BsonTimestamp right
	) {

		int seconds = Integer.compareUnsigned( left.getTime(), right.getTime() );
		return seconds != 0 ? seconds : Integer.compareUnsigned( left.getInc(), right.getInc() );

	}

	@Override
	public void close() {

		expirationWheel.close();
		tokenExpirationWheel.close();
		states.clear();
		tokens.clear();
		namespaceVersions.clear();
		namespaceClusterTimes.clear();

	}

}
