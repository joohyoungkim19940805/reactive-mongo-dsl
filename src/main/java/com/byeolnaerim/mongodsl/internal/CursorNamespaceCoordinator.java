package com.byeolnaerim.mongodsl.internal;


import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonTimestamp;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import org.bson.Document;
import com.byeolnaerim.mongodsl.change.ChangeStreamHub;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.paging.CursorAnchorStore;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/** Internal coordinator for cursor-cache namespace identity and change-stream invalidation. */
public final class CursorNamespaceCoordinator implements AutoCloseable {

	private final ChangeStreamHub changeStreamHub;

	private final CursorAnchorStore cursorAnchorStore;

	private final Map<Object, Long> localScopeIds = Collections.synchronizedMap( new IdentityHashMap<>() );

	private final AtomicLong localScopeSequence = new AtomicLong();

	private record InvalidationRegistration(ChangeStreamScope scope, String stateScope) {}

	private final ConcurrentHashMap<InvalidationRegistration, Object> registrations = new ConcurrentHashMap<>();

	public CursorNamespaceCoordinator(
		ChangeStreamHub changeStreamHub, CursorAnchorStore cursorAnchorStore
	) {

		this.changeStreamHub = Objects.requireNonNull( changeStreamHub, "changeStreamHub must not be null" );
		this.cursorAnchorStore = Objects.requireNonNull( cursorAnchorStore, "cursorAnchorStore must not be null" );

	}

	public Mono<String> version(
		MongoExecutionContext context, String collectionName
	) {

		return changeStreamHub.scope( context ).flatMap( scope -> {
			String namespaceKey = namespaceKey( context, scope, collectionName );
			return ensureInvalidation( context, scope )
				.then( cursorAnchorStore.namespaceVersion( namespaceKey ) )
				.map( version -> namespaceKey + ":" + version );

		} );

	}

	/** Returns a stable namespace identity without subscribing to invalidation or reading a namespace version. */
	public Mono<String> identity(
		MongoExecutionContext context, String collectionName
	) {

		return changeStreamHub.scope( context ).map( scope -> namespaceKey( context, scope, collectionName ) );

	}

	private String namespaceKey(
		MongoExecutionContext context, ChangeStreamScope scope, String collectionName
	) {

		return stateScope( context, scope ) + ":" + scope.databaseName() + ":" + collectionName;

	}

	private String stateScope(
		MongoExecutionContext context, ChangeStreamScope scope
	) {

		String distributedScopeKey = context.getDistributedStateScopeKey();
		boolean requiresDistributedScope = cursorAnchorStore instanceof ReactiveMongoDslStateStore stateStore
			? stateStore.requiresDistributedCursorScopeKey()
			: cursorAnchorStore.requiresDistributedStateScopeKey();
		if (requiresDistributedScope && (distributedScopeKey == null || distributedScopeKey.isBlank()))
			throw new IllegalStateException(
				"Distributed CursorAnchorStore requires MongoExecutionContext#getDistributedStateScopeKey()."
			);
		return distributedScopeKey == null || distributedScopeKey.isBlank()
			? "local-" + localScopeId( scope.sessionScope() )
			: distributedScopeKey.trim();

	}

	private long localScopeId(
		Object sessionScope
	) {

		synchronized (localScopeIds) {
			return localScopeIds.computeIfAbsent( sessionScope, ignored -> localScopeSequence.incrementAndGet() );

		}

	}

	private Mono<Void> ensureInvalidation(
		MongoExecutionContext context, ChangeStreamScope scope
	) {

		String stateScope = stateScope( context, scope );
		InvalidationRegistration registration = new InvalidationRegistration( scope, stateScope );

		return changeStreamHub
			.prepare( context )
			.then( Mono.fromRunnable( () -> registrations.computeIfAbsent( registration, ignored -> {
				Object observerKey = new Object();
				changeStreamHub.registerBatchObserver( scope, observerKey, events -> invalidateBatch( stateScope, scope, events ) );
				return observerKey;

			} ) ) )
			.then( changeStreamHub.ensureActive( context ) );

	}


	private Mono<Void> invalidateBatch(
		String stateScope,
		ChangeStreamScope scope,
		List<ChangeStreamDocument<Document>> events
	) {

		Map<String, BsonTimestamp> latestClusterTimeByCollection = new LinkedHashMap<>();
		for (ChangeStreamDocument<Document> event : events) {
			if (event.getNamespace() == null || event.getNamespace().getCollectionName() == null)
				continue;
			latestClusterTimeByCollection.put( event.getNamespace().getCollectionName(), event.getClusterTime() );

		}
		return Flux
			.fromIterable( latestClusterTimeByCollection.entrySet() )
			.concatMap( entry -> {
				String namespaceKey = stateScope + ":" + scope.databaseName() + ":" + entry.getKey();
				return cursorAnchorStore.invalidateNamespace( namespaceKey, entry.getValue() );

			} )
			.then();

	}

	@Override
	public void close() {

		registrations.forEach( (registration, observerKey) -> changeStreamHub.removeBatchObserver( registration.scope(), observerKey ) );
		registrations.clear();
		localScopeIds.clear();

	}

}
