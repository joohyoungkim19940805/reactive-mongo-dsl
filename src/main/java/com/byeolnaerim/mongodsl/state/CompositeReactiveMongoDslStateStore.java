package com.byeolnaerim.mongodsl.state;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import com.byeolnaerim.mongodsl.change.ChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorAnchorStore;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncLeaseStore;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


final class CompositeReactiveMongoDslStateStore implements ReactiveMongoDslStateStore {

	private final CursorAnchorStore cursorStore;

	private final ChangeStreamCheckpointStore checkpointStore;

	private final EmbeddedSyncLeaseStore embeddedSyncLeaseStore;

	private final List<ReactiveMongoDslStateStoreMetadata> metadataStores;

	CompositeReactiveMongoDslStateStore(
		CursorAnchorStore cursorStore,
		ChangeStreamCheckpointStore checkpointStore,
		EmbeddedSyncLeaseStore embeddedSyncLeaseStore
	) {

		this.cursorStore = Objects.requireNonNull( cursorStore, "cursorStore must not be null" );
		this.checkpointStore = Objects.requireNonNull( checkpointStore, "checkpointStore must not be null" );
		this.embeddedSyncLeaseStore = Objects.requireNonNull( embeddedSyncLeaseStore, "embeddedSyncLeaseStore must not be null" );
		Set<ReactiveMongoDslStateStoreMetadata> unique = Collections.newSetFromMap( new IdentityHashMap<>() );
		unique.add( this.cursorStore );
		unique.add( this.checkpointStore );
		unique.add( this.embeddedSyncLeaseStore );
		this.metadataStores = List.copyOf( unique );

	}

	@Override
	public CursorCacheOptions cursorCacheOptions() { return cursorStore.cursorCacheOptions(); }

	@Override
	public Mono<Void> putToken(
		String token, CursorTokenState state, Duration ttl
	) {

		return cursorStore.putToken( token, state, ttl );

	}

	@Override
	public Mono<Optional<CursorTokenState>> resolveToken(
		String token
	) {

		return cursorStore.resolveToken( token );

	}

	@Override
	public Mono<Optional<CursorAnchor>> floor(
		String queryKey, int pageNumber, long estimatedSkip
	) {

		return cursorStore.floor( queryKey, pageNumber, estimatedSkip );

	}

	@Override
	public Mono<Void> put(
		String queryKey, CursorAnchor anchor
	) {

		return cursorStore.put( queryKey, anchor );

	}

	@Override
	public Mono<Long> namespaceVersion(
		String namespaceKey
	) {

		return cursorStore.namespaceVersion( namespaceKey );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey
	) {

		return cursorStore.invalidateNamespace( namespaceKey );

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey, BsonTimestamp clusterTime
	) {

		return cursorStore.invalidateNamespace( namespaceKey, clusterTime );

	}

	@Override
	public Mono<BsonDocument> load(
		ChangeStreamScope scope
	) {

		return checkpointStore.load( scope );

	}

	@Override
	public Mono<Void> save(
		ChangeStreamScope scope, BsonDocument resumeToken
	) {

		return checkpointStore.save( scope, resumeToken );

	}

	@Override
	public Mono<Void> delete(
		ChangeStreamScope scope
	) {

		return checkpointStore.delete( scope );

	}

	@Override
	public Mono<Boolean> tryAcquire(
		String leaseKey, String ownerId, Duration ttl
	) {

		return embeddedSyncLeaseStore.tryAcquire( leaseKey, ownerId, ttl );

	}

	@Override
	public Mono<Boolean> renew(
		String leaseKey, String ownerId, Duration ttl
	) {

		return embeddedSyncLeaseStore.renew( leaseKey, ownerId, ttl );

	}

	@Override
	public Mono<Void> release(
		String leaseKey, String ownerId
	) {

		return embeddedSyncLeaseStore.release( leaseKey, ownerId );

	}

	@Override
	public boolean requiresDistributedStateScopeKey() {

		return metadataStores.stream().anyMatch( ReactiveMongoDslStateStoreMetadata::requiresDistributedStateScopeKey );

	}

	@Override
	public boolean requiresDistributedCursorScopeKey() { return cursorStore.requiresDistributedStateScopeKey(); }

	@Override
	public boolean requiresDistributedCheckpointScopeKey() { return checkpointStore.requiresDistributedStateScopeKey(); }

	@Override
	public boolean requiresDistributedEmbeddedSyncScopeKey() { return embeddedSyncLeaseStore.requiresDistributedStateScopeKey(); }

	@Override
	public Mono<Set<String>> changeStreamExcludedCollections(
		MongoExecutionContext executionContext, MongoDatabase database
	) {

		return Flux
			.fromIterable( metadataStores )
			.concatMap( store -> store.changeStreamExcludedCollections( executionContext, database ) )
			.flatMapIterable( values -> values )
			.collect( java.util.stream.Collectors.toSet() );

	}

	@Override
	public void close() {

		List<RuntimeException> failures = new ArrayList<>();
		for (ReactiveMongoDslStateStoreMetadata store : metadataStores) {
			try {
				store.close();

			} catch (RuntimeException e) {
				failures.add( e );

			}

		}
		if (! failures.isEmpty()) {
			RuntimeException failure = new IllegalStateException( "Failed to close one or more ReactiveMongoDsl state stores" );
			failures.forEach( failure::addSuppressed );
			throw failure;

		}

	}

}
