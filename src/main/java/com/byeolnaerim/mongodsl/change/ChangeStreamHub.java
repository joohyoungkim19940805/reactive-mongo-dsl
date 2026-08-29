package com.byeolnaerim.mongodsl.change;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStoreMetadata;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.reactivestreams.client.ChangeStreamPublisher;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;


/**
 * Shares one physical database change stream per Mongo client/database scope and dispatches it to
 * cursor invalidation, embedded synchronization, reservations, and user subscriptions.
 */
public final class ChangeStreamHub implements AutoCloseable {

	private static final class ScopeState {

		private final Flux<ChangeStreamDocument<Document>> stream;

		private final Mono<BsonTimestamp> initialOperationTime;

		private final AtomicReference<Disposable> keeper = new AtomicReference<>();

		private ScopeState(
			Flux<ChangeStreamDocument<Document>> stream,
			Mono<BsonTimestamp> initialOperationTime
		) {

			this.stream = stream;
			this.initialOperationTime = initialOperationTime;

		}

	}

	private final ChangeStreamCheckpointStore checkpointStore;

	private final List<ReactiveMongoDslStateStoreMetadata> stateStoreMetadata;

	private final ConcurrentHashMap<ChangeStreamScope, ScopeState> scopes = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<ChangeStreamScope, ConcurrentHashMap<Object, Function<ChangeStreamDocument<Document>, Mono<Void>>>> observers = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<ChangeStreamScope, ConcurrentHashMap<Object, Function<List<ChangeStreamDocument<Document>>, Mono<Void>>>> batchObservers = new ConcurrentHashMap<>();

	private static final int INTERNAL_BATCH_SIZE = 256;

	private static final Duration INTERNAL_BATCH_WINDOW = Duration.ofMillis( 10 );

	public ChangeStreamHub() {

		this( new InMemoryChangeStreamCheckpointStore() );

	}

	public ChangeStreamHub(
		ChangeStreamCheckpointStore checkpointStore,
		ReactiveMongoDslStateStoreMetadata... stateStoreMetadata
	) {

		this.checkpointStore = Objects.requireNonNull( checkpointStore, "checkpointStore must not be null" );
		Set<ReactiveMongoDslStateStoreMetadata> unique = Collections.newSetFromMap( new IdentityHashMap<>() );
		unique.add( this.checkpointStore );
		if (stateStoreMetadata != null) {
			for (ReactiveMongoDslStateStoreMetadata metadata : stateStoreMetadata) {
				if (metadata != null)
					unique.add( metadata );

			}

		}
		this.stateStoreMetadata = List.copyOf( unique );

	}

	/** Returns the shared database-wide stream for the supplied execution context. */
	public Flux<ChangeStreamDocument<Document>> watch(
		MongoExecutionContext executionContext
	) {

		Objects.requireNonNull( executionContext, "executionContext must not be null" );
		return executionContext.getDatabase().flatMapMany( database -> state( executionContext, database ).stream );

	}

	/** Returns only events for the requested collection while retaining the shared database stream. */
	public Flux<ChangeStreamDocument<Document>> watchCollection(
		MongoExecutionContext executionContext, String collectionName
	) {

		Objects.requireNonNull( collectionName, "collectionName must not be null" );
		return watch( executionContext )
			.filter(
				event -> event.getNamespace() != null
					&& collectionName.equals( event.getNamespace().getCollectionName() )
			);

	}

	/**
	 * Captures a stable logical start position for the database stream before callers begin work
	 * whose following writes must not be missed by the first physical Change Stream subscription.
	 */
	public Mono<Void> prepare(
		MongoExecutionContext executionContext
	) {

		Objects.requireNonNull( executionContext, "executionContext must not be null" );
		return executionContext
			.getDatabase()
			.flatMap( database -> state( executionContext, database ).initialOperationTime )
			.then();

	}

	/** Ensures the database stream stays subscribed even when only cache invalidation needs it. */
	public Mono<Void> ensureActive(
		MongoExecutionContext executionContext
	) {

		Objects.requireNonNull( executionContext, "executionContext must not be null" );
		return executionContext.getDatabase().flatMap( database -> {
			ScopeState state = state( executionContext, database );
			return state.initialOperationTime.then( Mono.fromRunnable( () -> {
				for (;;) {
					Disposable current = state.keeper.get();
					if (current != null && ! current.isDisposed())
						return;
					Disposable candidate = state.stream.subscribe( ignored -> {}, ignored -> {} );
					if (state.keeper.compareAndSet( current, candidate )) {
						if (current != null)
							current.dispose();
						return;

					}
					candidate.dispose();

				}

			} ) );

		} ).then();

	}

	public Mono<ChangeStreamScope> scope(
		MongoExecutionContext executionContext
	) {

		return executionContext
			.getDatabase()
			.map( database -> new ChangeStreamScope( executionContext.getSessionScope(), database.getName(), executionContext.getDistributedStateScopeKey() ) );

	}

	/**
	 * Registers one internal observer on the shared physical database stream. Observers execute
	 * before the Change Stream checkpoint is advanced so a failed observer cannot make the event
	 * disappear behind an already-saved resume token.
	 */
	public void registerObserver(
		ChangeStreamScope scope,
		Object observerKey,
		Function<ChangeStreamDocument<Document>, Mono<Void>> observer
	) {

		Objects.requireNonNull( scope, "scope must not be null" );
		Objects.requireNonNull( observerKey, "observerKey must not be null" );
		Objects.requireNonNull( observer, "observer must not be null" );
		observers.computeIfAbsent( scope, ignored -> new ConcurrentHashMap<>() ).putIfAbsent( observerKey, observer );

	}

	/**
	 * Registers one batch observer. A batch observer sees the same ordered Change Stream events as
	 * normal observers, but can coalesce namespace-level work before the checkpoint advances.
	 */
	public void registerBatchObserver(
		ChangeStreamScope scope,
		Object observerKey,
		Function<List<ChangeStreamDocument<Document>>, Mono<Void>> observer
	) {

		Objects.requireNonNull( scope, "scope must not be null" );
		Objects.requireNonNull( observerKey, "observerKey must not be null" );
		Objects.requireNonNull( observer, "observer must not be null" );
		batchObservers.computeIfAbsent( scope, ignored -> new ConcurrentHashMap<>() ).putIfAbsent( observerKey, observer );

	}

	/** Removes a previously registered batch observer without affecting the physical stream. */
	public void removeBatchObserver(
		ChangeStreamScope scope, Object observerKey
	) {

		ConcurrentHashMap<Object, Function<List<ChangeStreamDocument<Document>>, Mono<Void>>> scopeObservers = batchObservers.get( scope );
		if (scopeObservers == null)
			return;
		scopeObservers.remove( observerKey );
		if (scopeObservers.isEmpty())
			batchObservers.remove( scope, scopeObservers );

	}

	/** Removes a previously registered internal observer without affecting the physical stream. */
	public void removeObserver(
		ChangeStreamScope scope, Object observerKey
	) {

		ConcurrentHashMap<Object, Function<ChangeStreamDocument<Document>, Mono<Void>>> scopeObservers = observers.get( scope );
		if (scopeObservers == null)
			return;
		scopeObservers.remove( observerKey );
		if (scopeObservers.isEmpty())
			observers.remove( scope, scopeObservers );

	}

	private Mono<Void> notifyObservers(
		ChangeStreamScope scope, ChangeStreamDocument<Document> event
	) {

		ConcurrentHashMap<Object, Function<ChangeStreamDocument<Document>, Mono<Void>>> scopeObservers = observers.get( scope );
		if (scopeObservers == null || scopeObservers.isEmpty())
			return Mono.empty();

		return Flux
			.fromIterable( List.copyOf( scopeObservers.values() ) )
			.concatMap( observer -> Mono.defer( () -> observer.apply( event ) ) )
			.then();

	}

	private Mono<Void> notifyBatchObservers(
		ChangeStreamScope scope, List<ChangeStreamDocument<Document>> events
	) {

		ConcurrentHashMap<Object, Function<List<ChangeStreamDocument<Document>>, Mono<Void>>> scopeObservers = batchObservers.get( scope );
		if (scopeObservers == null || scopeObservers.isEmpty())
			return Mono.empty();
		List<ChangeStreamDocument<Document>> immutableEvents = List.copyOf( events );
		return Flux
			.fromIterable( List.copyOf( scopeObservers.values() ) )
			.concatMap( observer -> Mono.defer( () -> observer.apply( immutableEvents ) ) )
			.then();

	}

	private Mono<Void> saveBatchCheckpoint(
		ChangeStreamScope scope, List<ChangeStreamDocument<Document>> events
	) {

		if (events.isEmpty())
			return Mono.empty();
		for (int i = events.size() - 1; i >= 0; i--) {
			ChangeStreamDocument<Document> event = events.get( i );
			if (event.getOperationType() == OperationType.INVALIDATE)
				return checkpointStore.delete( scope );
			if (event.getResumeToken() != null)
				return checkpointStore.save( scope, event.getResumeToken() );

		}
		return Mono.empty();

	}

	private Flux<ChangeStreamDocument<Document>> processBatch(
		ChangeStreamScope scope, List<ChangeStreamDocument<Document>> events
	) {

		if (events.isEmpty())
			return Flux.empty();
		return notifyBatchObservers( scope, events )
			.thenMany(
				Flux.fromIterable( events )
					.concatMap( event -> notifyObservers( scope, event ) )
			)
			.then( saveBatchCheckpoint( scope, events ) )
			.thenMany( Flux.fromIterable( events ) );

	}

	private ScopeState state(
		MongoExecutionContext executionContext, MongoDatabase database
	) {

		ChangeStreamScope scope = new ChangeStreamScope( executionContext.getSessionScope(), database.getName(), executionContext.getDistributedStateScopeKey() );
		return scopes.computeIfAbsent( scope, ignored -> createState( executionContext, scope, database ) );

	}

	private ScopeState createState(
		MongoExecutionContext executionContext, ChangeStreamScope scope, MongoDatabase database
	) {

		boolean requiresDistributedScope = checkpointStore instanceof ReactiveMongoDslStateStore stateStore
			? stateStore.requiresDistributedCheckpointScopeKey()
			: checkpointStore.requiresDistributedStateScopeKey();
		if (requiresDistributedScope && scope.persistentKey() == null)
			throw new IllegalStateException(
				"Distributed ChangeStreamCheckpointStore requires MongoExecutionContext#getDistributedStateScopeKey()."
			);

		Mono<BsonTimestamp> initialOperationTime = currentOperationTime( database )
			.switchIfEmpty( Mono.error( new IllegalStateException( "MongoDB did not expose an operation time required to initialize the Change Stream safely." ) ) )
			.cache();

		Flux<ChangeStreamDocument<Document>> source = Flux
			.defer(
				() -> Mono
					.zip(
						checkpointStore.load( scope ).defaultIfEmpty( new BsonDocument() ),
						excludedCollections( executionContext, database ),
						initialOperationTime
					)
					.flatMapMany( tuple -> createPublisher( database, tuple.getT1(), tuple.getT2(), tuple.getT3() ) )
			)
			.bufferTimeout( INTERNAL_BATCH_SIZE, INTERNAL_BATCH_WINDOW )
			.filter( events -> ! events.isEmpty() )
			.concatMap( events -> processBatch( scope, events ) )
			.retryWhen( Retry.backoff( Long.MAX_VALUE, Duration.ofMillis( 250 ) ).maxBackoff( Duration.ofSeconds( 10 ) ) )
			.repeat()
			.publish()
			.refCount( 1 );

		return new ScopeState( source, initialOperationTime );

	}

	private Mono<Set<String>> excludedCollections(
		MongoExecutionContext executionContext, MongoDatabase database
	) {

		return Flux
			.fromIterable( stateStoreMetadata )
			.concatMap( metadata -> metadata.changeStreamExcludedCollections( executionContext, database ) )
			.flatMapIterable( values -> values )
			.filter( value -> value != null && ! value.isBlank() )
			.collect( java.util.stream.Collectors.toSet() );

	}

	private Mono<BsonTimestamp> currentOperationTime(
		MongoDatabase database
	) {

		return Mono
			.from( database.runCommand( new Document( "ping", 1 ) ) )
			.flatMap( result -> Mono.justOrEmpty( extractOperationTime( result ) ) );

	}

	private BsonTimestamp extractOperationTime(
		Document commandResult
	) {

		if (commandResult == null)
			return null;
		Object operationTime = commandResult.get( "operationTime" );
		if (operationTime instanceof BsonTimestamp timestamp)
			return timestamp;

		Object clusterTime = commandResult.get( "$clusterTime" );
		if (clusterTime instanceof Document clusterTimeDocument) {
			Object timestamp = clusterTimeDocument.get( "clusterTime" );
			if (timestamp instanceof BsonTimestamp bsonTimestamp)
				return bsonTimestamp;

		}
		if (clusterTime instanceof BsonDocument clusterTimeDocument) {
			var timestamp = clusterTimeDocument.get( "clusterTime" );
			if (timestamp instanceof BsonTimestamp bsonTimestamp)
				return bsonTimestamp;

		}
		return null;

	}

	private Flux<ChangeStreamDocument<Document>> createPublisher(
		MongoDatabase database,
		BsonDocument resumeToken,
		Set<String> excludedCollections,
		BsonTimestamp initialOperationTime
	) {

		List<Bson> pipeline = new ArrayList<>();
		if (excludedCollections != null && ! excludedCollections.isEmpty())
			pipeline.add( Aggregates.match( Filters.nin( "ns.coll", excludedCollections ) ) );
		ChangeStreamPublisher<Document> publisher = pipeline.isEmpty() ? database.watch() : database.watch( pipeline );
		if (resumeToken != null && ! resumeToken.isEmpty())
			publisher = publisher.resumeAfter( resumeToken );
		else if (initialOperationTime != null)
			publisher = publisher.startAtOperationTime( initialOperationTime );
		return Flux.from( publisher );

	}

	@Override
	public void close() {

		for (ScopeState state : scopes.values()) {
			Disposable disposable = state.keeper.getAndSet( null );
			if (disposable != null)
				disposable.dispose();

		}
		scopes.clear();
		observers.clear();
		batchObservers.clear();

	}

}
