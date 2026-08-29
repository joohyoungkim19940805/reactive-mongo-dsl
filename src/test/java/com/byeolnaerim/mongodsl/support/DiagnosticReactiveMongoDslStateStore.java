package com.byeolnaerim.mongodsl.support;


import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Mono;


public final class DiagnosticReactiveMongoDslStateStore implements ReactiveMongoDslStateStore {

	record TraceEvent(long sequence, String operation, String namespaceKey, BsonTimestamp clusterTime, String detail) {}

	private final ReactiveMongoDslStateStore delegate;

	private final AtomicLong sequence = new AtomicLong();

	private final CopyOnWriteArrayList<TraceEvent> trace = new CopyOnWriteArrayList<>();

	public DiagnosticReactiveMongoDslStateStore(
												ReactiveMongoDslStateStore delegate
	) {

		this.delegate = delegate;

	}

	long mark() {

		return sequence.get();

	}

	List<TraceEvent> traceSince(
		long mark
	) {

		List<TraceEvent> values = trace.stream().filter( event -> event.sequence() > mark ).toList();
		return values.size() <= 100 ? values : values.subList( values.size() - 100, values.size() );

	}

	private void trace(
		String operation, String detail
	) {

		trace( operation, null, null, detail );

	}

	private void trace(
		String operation, String namespaceKey, BsonTimestamp clusterTime, String detail
	) {

		trace.add( new TraceEvent( sequence.incrementAndGet(), operation, namespaceKey, clusterTime, detail ) );

	}

	BsonTimestamp latestInvalidationClusterTimeSince(
		long mark
	) {

		BsonTimestamp latest = null;

		for (TraceEvent event : trace) {
			if (event.sequence() <= mark || event.clusterTime() == null || ! event.operation().startsWith( "invalidate" ))
				continue;
			if (latest == null || compareTimestamp( event.clusterTime(), latest ) > 0)
				latest = event.clusterTime();

		}

		return latest;

	}

	private int compareTimestamp(
		BsonTimestamp left, BsonTimestamp right
	) {

		int seconds = Integer.compareUnsigned( left.getTime(), right.getTime() );
		return seconds != 0 ? seconds : Integer.compareUnsigned( left.getInc(), right.getInc() );

	}

	@Override
	public CursorCacheOptions cursorCacheOptions() { return delegate.cursorCacheOptions(); }

	@Override
	public Mono<Void> putToken(
		String token, CursorTokenState state, Duration ttl
	) {

		return delegate.putToken( token, state, ttl );

	}

	@Override
	public Mono<Optional<CursorTokenState>> resolveToken(
		String token
	) {

		return delegate.resolveToken( token );

	}

	@Override
	public Mono<Optional<CursorAnchor>> floor(
		String queryKey, int pageNumber, long estimatedSkip
	) {

		return delegate.floor( queryKey, pageNumber, estimatedSkip );

	}

	@Override
	public Mono<Void> put(
		String queryKey, CursorAnchor anchor
	) {

		return delegate.put( queryKey, anchor );

	}

	@Override
	public Mono<Long> namespaceVersion(
		String namespaceKey
	) {

		return delegate.namespaceVersion( namespaceKey );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey
	) {

		return Mono.defer( () -> {
			trace( "invalidate-start", namespaceKey, null, namespaceKey );
			return delegate
				.invalidateNamespace( namespaceKey )
				.doOnSuccess( ignored -> trace( "invalidate-success", namespaceKey, null, namespaceKey ) )
				.doOnError( error -> trace( "invalidate-error", namespaceKey, null, namespaceKey + " :: " + error ) );

		} );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey, BsonTimestamp clusterTime
	) {

		return Mono.defer( () -> {
			String detail = namespaceKey + " @ " + clusterTime;
			trace( "invalidate-cluster-start", namespaceKey, clusterTime, detail );
			return delegate
				.invalidateNamespace( namespaceKey, clusterTime )
				.doOnSuccess( ignored -> trace( "invalidate-cluster-success", namespaceKey, clusterTime, detail ) )
				.doOnError( error -> trace( "invalidate-cluster-error", namespaceKey, clusterTime, detail + " :: " + error ) );

		} );

	}

	@Override
	public Mono<BsonDocument> load(
		ChangeStreamScope scope
	) {

		return Mono.defer( () -> {
			trace( "checkpoint-load", String.valueOf( scope ) );
			return delegate.load( scope );

		} );

	}

	@Override
	public Mono<Void> save(
		ChangeStreamScope scope, BsonDocument resumeToken
	) {

		return Mono.defer( () -> {
			trace( "checkpoint-save-start", String.valueOf( scope ) );
			return delegate
				.save( scope, resumeToken )
				.doOnSuccess( ignored -> trace( "checkpoint-save-success", String.valueOf( scope ) ) )
				.doOnError( error -> trace( "checkpoint-save-error", scope + " :: " + error ) );

		} );

	}

	@Override
	public Mono<Void> delete(
		ChangeStreamScope scope
	) {

		return delegate.delete( scope );

	}

	@Override
	public Mono<Boolean> tryAcquire(
		String leaseKey, String ownerId, Duration ttl
	) {

		return delegate.tryAcquire( leaseKey, ownerId, ttl );

	}

	@Override
	public Mono<Boolean> renew(
		String leaseKey, String ownerId, Duration ttl
	) {

		return delegate.renew( leaseKey, ownerId, ttl );

	}

	@Override
	public Mono<Void> release(
		String leaseKey, String ownerId
	) {

		return delegate.release( leaseKey, ownerId );

	}

	@Override
	public boolean requiresDistributedStateScopeKey() {

		return delegate.requiresDistributedStateScopeKey();

	}

	@Override
	public boolean requiresDistributedCursorScopeKey() {

		return delegate.requiresDistributedCursorScopeKey();

	}

	@Override
	public boolean requiresDistributedCheckpointScopeKey() {

		return delegate.requiresDistributedCheckpointScopeKey();

	}

	@Override
	public boolean requiresDistributedEmbeddedSyncScopeKey() {

		return delegate.requiresDistributedEmbeddedSyncScopeKey();

	}

	@Override
	public Mono<Set<String>> changeStreamExcludedCollections(
		MongoExecutionContext executionContext, MongoDatabase database
	) {

		return delegate.changeStreamExcludedCollections( executionContext, database );

	}

	@Override
	public void close() {

		delegate.close();

	}

}
