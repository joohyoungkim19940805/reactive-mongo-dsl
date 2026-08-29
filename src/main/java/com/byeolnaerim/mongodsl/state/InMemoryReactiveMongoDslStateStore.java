package com.byeolnaerim.mongodsl.state;


import java.time.Duration;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.change.InMemoryChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.paging.InMemoryCursorAnchorStore;
import com.byeolnaerim.mongodsl.sync.InMemoryEmbeddedSyncLeaseStore;
import reactor.core.publisher.Mono;


/** Zero-configuration process-local state store used by all three optional DSL features. */
public final class InMemoryReactiveMongoDslStateStore implements ReactiveMongoDslStateStore {

	private final InMemoryCursorAnchorStore cursorStore;

	private final InMemoryChangeStreamCheckpointStore checkpointStore = new InMemoryChangeStreamCheckpointStore();

	private final InMemoryEmbeddedSyncLeaseStore embeddedSyncLeaseStore = new InMemoryEmbeddedSyncLeaseStore();

	public InMemoryReactiveMongoDslStateStore() {

		this( CursorCacheOptions.defaults() );

	}

	public InMemoryReactiveMongoDslStateStore(
		CursorCacheOptions cursorCacheOptions
	) {

		this.cursorStore = new InMemoryCursorAnchorStore( cursorCacheOptions );

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
	public void close() {

		cursorStore.close();
		embeddedSyncLeaseStore.close();

	}

}
