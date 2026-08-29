package com.byeolnaerim.mongodsl.sync;


import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import com.byeolnaerim.mongodsl.change.ChangeStreamHub;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncDefinition.LinkFieldPair;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.reactivestreams.client.MongoCollection;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/** Internal change-stream-driven denormalized embedded snapshot synchronization engine. */
public final class EmbeddedSyncEngine implements AutoCloseable {

	private record PhysicalNode(ChangeStreamScope scope, String collection) {}

	private record TargetPath(PhysicalNode target, String field) {}

	private record ResolvedRelation(
		MongoExecutionContext context,
		ChangeStreamScope scope,
		String sourceCollection,
		String targetCollection,
		EmbeddedSyncDefinition definition
	) {}

	private record RelationKey(
		ChangeStreamScope scope,
		String sourceCollection,
		String targetCollection,
		String targetField,
		Class<?> sourceClass,
		Class<?> targetClass
	) {}

	private record PendingKey(RelationKey relationKey, Object sourceId) {}

	private record PendingChange(ResolvedRelation relation, Object sourceId, OperationType operationType) {}

	private final ChangeStreamHub changeStreamHub;

	private final EmbeddedSyncLeaseStore leaseStore;

	private final String leaseOwnerId = UUID.randomUUID().toString();

	private final Duration leaseTtl = Duration.ofSeconds( 15 );

	private final ConcurrentHashMap<RelationKey, AtomicLong> leaseDeadlines = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<RelationKey, String> leaseKeys = new ConcurrentHashMap<>();

	private final AtomicLong deferFlushUntilNanos = new AtomicLong();

	private final ConcurrentHashMap<RelationKey, ResolvedRelation> relations = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<RelationKey, Disposable> subscriptions = new ConcurrentHashMap<>();

	private final AtomicReference<ConcurrentHashMap<PendingKey, PendingChange>> pending = new AtomicReference<>( new ConcurrentHashMap<>() );

	private final AtomicReference<Disposable> flushTask = new AtomicReference<>();

	private final Object graphLock = new Object();

	private final Map<PhysicalNode, Set<PhysicalNode>> graph = new HashMap<>();

	private final Map<TargetPath, PhysicalNode> targetOwners = new HashMap<>();

	private final Map<TargetPath, EmbeddedSyncDefinition> targetDefinitions = new HashMap<>();

	private final Duration coalesceWindow;

	public EmbeddedSyncEngine(
		ChangeStreamHub changeStreamHub
	) {

		this( changeStreamHub, new InMemoryEmbeddedSyncLeaseStore(), Duration.ofMillis( 50 ) );

	}

	public EmbeddedSyncEngine(
		ChangeStreamHub changeStreamHub, EmbeddedSyncLeaseStore leaseStore
	) {

		this( changeStreamHub, leaseStore, Duration.ofMillis( 50 ) );

	}

	public EmbeddedSyncEngine(
		ChangeStreamHub changeStreamHub, EmbeddedSyncLeaseStore leaseStore, Duration coalesceWindow
	) {

		this.changeStreamHub = Objects.requireNonNull( changeStreamHub, "changeStreamHub must not be null" );
		this.leaseStore = Objects.requireNonNull( leaseStore, "leaseStore must not be null" );
		this.coalesceWindow = Objects.requireNonNull( coalesceWindow, "coalesceWindow must not be null" );

	}

	/** Registers one already validated logical embedded relation for a physical execution context. */
	public Mono<Void> register(
		MongoExecutionContext context, EmbeddedSyncDefinition definition
	) {

		Objects.requireNonNull( context, "context must not be null" );
		Objects.requireNonNull( definition, "definition must not be null" );
		String sourceCollection = context.getCollectionName( definition.sourceClass() );
		String targetCollection = context.getCollectionName( definition.targetClass() );

		return changeStreamHub.scope( context ).flatMap( scope -> {
			boolean requiresDistributedScope = leaseStore instanceof ReactiveMongoDslStateStore stateStore
				? stateStore.requiresDistributedEmbeddedSyncScopeKey()
				: leaseStore.requiresDistributedStateScopeKey();
			if (requiresDistributedScope && scope.persistentKey() == null)
				return Mono.error( new IllegalStateException(
					"Distributed EmbeddedSyncLeaseStore requires MongoExecutionContext#getDistributedStateScopeKey()."
				) );

			ResolvedRelation relation = new ResolvedRelation( context, scope, sourceCollection, targetCollection, definition );
			RelationKey key = relationKey( relation );
			ResolvedRelation existing = relations.get( key );
			if (existing != null) {
				if (! existing.definition().equals( definition ))
					return Mono.error( new IllegalStateException( "Embedded synchronization target already has a different physical definition: " + targetCollection + "." + definition.targetField() ) );
				return Mono.empty();

			}

			validatePhysicalGraph( relation );
			return changeStreamHub.prepare( context ).then( Mono.fromRunnable( () -> {
				ResolvedRelation previous = relations.putIfAbsent( key, relation );
				if (previous != null)
					return;
				leaseKeys.putIfAbsent( key, leaseKey( relation ) );
				Disposable subscription = changeStreamHub
					.watchCollection( context, sourceCollection )
					.filter( this::isSupportedOperation )
					.subscribe( event -> enqueue( relation, event ), ignored -> {} );
				Disposable old = subscriptions.putIfAbsent( key, subscription );
				if (old != null)
					subscription.dispose();

			} ) );

		} ).then();

	}

	private Mono<Boolean> ensureLease(
		ResolvedRelation relation
	) {

		RelationKey relationKey = relationKey( relation );
		String leaseKey = leaseKeys.computeIfAbsent( relationKey, ignored -> leaseKey( relation ) );
		AtomicLong localDeadline = leaseDeadlines.computeIfAbsent( relationKey, ignored -> new AtomicLong() );
		long now = System.nanoTime();
		long deadline = localDeadline.get();
		long renewalMargin = leaseTtl.toNanos() / 3L;
		if (deadline - now > renewalMargin)
			return Mono.just( true );

		Mono<Boolean> leaseMono = deadline > now
			? leaseStore.renew( leaseKey, leaseOwnerId, leaseTtl )
			: leaseStore.tryAcquire( leaseKey, leaseOwnerId, leaseTtl );
		return leaseMono.flatMap( acquired -> {
			if (acquired) {
				localDeadline.set( System.nanoTime() + leaseTtl.toNanos() );
				return Mono.just( true );

			}
			localDeadline.set( 0L );
			return Mono.just( false );

		} );

	}

	private String leaseKey(
		ResolvedRelation relation
	) {

		String scopeKey = relation.scope().persistentKey();
		if (scopeKey == null)
			scopeKey = "local:" + Integer.toHexString( System.identityHashCode( relation.scope().sessionScope() ) ) + ":" + relation.scope().databaseName();
		return scopeKey + ":" + relation.sourceCollection() + "->" + relation.targetCollection() + "." + relation.definition().targetField();

	}

	private boolean isSupportedOperation(
		ChangeStreamDocument<Document> event
	) {

		return event.getOperationType() == OperationType.INSERT
			|| event.getOperationType() == OperationType.UPDATE
			|| event.getOperationType() == OperationType.REPLACE
			|| event.getOperationType() == OperationType.DELETE;

	}

	private void enqueue(
		ResolvedRelation relation, ChangeStreamDocument<Document> event
	) {

		if (event.getDocumentKey() == null)
			return;
		Object sourceId = documentKeyId( event );
		if (sourceId == null)
			return;
		RelationKey relationKey = relationKey( relation );
		pending.get().put( new PendingKey( relationKey, sourceId ), new PendingChange( relation, sourceId, event.getOperationType() ) );
		scheduleFlush();

	}

	private void scheduleFlush() {

		if (flushTask.get() != null)
			return;
		long delayNanos = coalesceWindow.toNanos();
		long deferredUntil = deferFlushUntilNanos.get();
		long now = System.nanoTime();
		if (deferredUntil > now)
			delayNanos = Math.max( delayNanos, deferredUntil - now );
		Disposable candidate = Schedulers.parallel().schedule( this::flush, delayNanos, java.util.concurrent.TimeUnit.NANOSECONDS );
		if (! flushTask.compareAndSet( null, candidate ))
			candidate.dispose();

	}

	private void flush() {

		ConcurrentHashMap<PendingKey, PendingChange> batch = pending.getAndSet( new ConcurrentHashMap<>() );
		if (batch.isEmpty()) {
			flushTask.set( null );
			if (! pending.get().isEmpty())
				scheduleFlush();
			return;

		}

		Map<ResolvedRelation, List<PendingChange>> grouped = new LinkedHashMap<>();
		for (PendingChange change : batch.values())
			grouped.computeIfAbsent( change.relation(), ignored -> new ArrayList<>() ).add( change );

		Flux.fromIterable( grouped.entrySet() )
			.flatMap( entry -> processRelationBatch( entry.getKey(), entry.getValue() ), 4 )
			.then()
			.doFinally( ignored -> {
				flushTask.set( null );
				if (! pending.get().isEmpty())
					scheduleFlush();

			} )
			.subscribe( ignored -> {}, ignored -> {} );

	}

	private Mono<Void> processRelationBatch(
		ResolvedRelation relation, List<PendingChange> changes
	) {

		return ensureLease( relation )
			.flatMap( owner -> {
				if (! owner) {
					requeue( relation, changes );
					return Mono.empty();

				}
				return processOwnedRelationBatch( relation, changes );

			} )
			.onErrorResume( ignored -> {
				requeue( relation, changes );
				return Mono.empty();

			} );

	}

	private void requeue(
		ResolvedRelation relation, List<PendingChange> changes
	) {

		RelationKey relationKey = relationKey( relation );
		ConcurrentHashMap<PendingKey, PendingChange> currentPending = pending.get();
		for (PendingChange change : changes)
			currentPending.put( new PendingKey( relationKey, change.sourceId() ), change );
		deferFlushUntilNanos.accumulateAndGet( System.nanoTime() + Duration.ofSeconds( 1 ).toNanos(), Math::max );

	}

	private Mono<Void> processOwnedRelationBatch(
		ResolvedRelation relation, List<PendingChange> changes
	) {

		List<Object> idsToRead = changes
			.stream()
			.filter( change -> change.operationType() != OperationType.DELETE )
			.map( PendingChange::sourceId )
			.distinct()
			.toList();

		Mono<Map<Object, Document>> currentDocuments = idsToRead.isEmpty()
			? Mono.just( Map.of() )
			: collection( relation.context(), relation.sourceCollection() )
				.flatMapMany( source -> source.find( Filters.in( "_id", idsToRead ) ) )
				.collectMap( document -> document.get( "_id" ) );

		return currentDocuments.flatMapMany( documents -> Flux.fromIterable( changes ).flatMap( change -> {
			Document current = documents.get( change.sourceId() );
			if (change.operationType() == OperationType.DELETE || current == null)
				return synchronizeDelete( relation, change.sourceId() );
			return synchronizeCurrent( relation, change.sourceId(), current );

		}, 8 ) ).then();

	}

	private Mono<Void> synchronizeCurrent(
		ResolvedRelation relation, Object sourceId, Document source
	) {

		Optional<Bson> linkedTargets = buildLinkFilter( relation.definition().links(), source );
		if (relation.definition().links().isEmpty())
			return updateCurrentTargets( relation, sourceId, source, linkedTargets );

		Mono<Void> cleanup = cleanupMovedReference( relation, sourceId, linkedTargets );
		if (linkedTargets.isEmpty())
			return cleanup;

		return updateCurrentTargets( relation, sourceId, source, linkedTargets ).then( cleanup );

	}

	private Mono<Void> updateCurrentTargets(
		ResolvedRelation relation, Object sourceId, Document source, Optional<Bson> linkedTargets
	) {

		String field = relation.definition().targetField();
		return collection( relation.context(), relation.targetCollection() ).flatMap( target -> switch (relation.definition().cardinality()) {
			case SINGLE -> {
				Bson filter = linkedTargets.orElseGet( () -> Filters.eq( field + "._id", sourceId ) );
				yield Mono.from( target.updateMany( filter, Updates.set( field, source ) ) ).then();

			}
			case COLLECTION -> {
				if (linkedTargets.isPresent())
					yield Mono.from( target.updateMany( linkedTargets.get(), collectionUpsertPipeline( field, sourceId, source ) ) ).then();
				UpdateOptions options = new UpdateOptions().arrayFilters( List.of( Filters.eq( "embedded._id", sourceId ) ) );
				yield Mono.from(
					target.updateMany(
						Filters.eq( field + "._id", sourceId ),
						Updates.set( field + ".$[embedded]", source ),
						options
					)
				).then();

			}
			case MAP -> {
				Object mapKeyValue = readPath( source, MongoFieldNameSupport.toMongoField( relation.definition().mapKeyField() ) );
				if (mapKeyValue == null)
					yield Mono.empty();
				String mapKey = String.valueOf( mapKeyValue );
				validateMapKey( mapKey );
				Bson filter = linkedTargets.orElseGet( () -> mapContainsSourceId( field, sourceId ) );
				yield Mono.from( target.updateMany( filter, mapUpsertPipeline( field, sourceId, mapKey, source ) ) ).then();

			}
		} );

	}

	private Mono<Void> cleanupMovedReference(
		ResolvedRelation relation, Object sourceId, Optional<Bson> currentLinkFilter
	) {

		String field = relation.definition().targetField();
		Bson hasReference = switch (relation.definition().cardinality()) {
			case SINGLE, COLLECTION -> Filters.eq( field + "._id", sourceId );
			case MAP -> mapContainsSourceId( field, sourceId );
		};
		Bson filter = currentLinkFilter
			.<Bson>map( link -> Filters.and( hasReference, Filters.nor( link ) ) )
			.orElse( hasReference );

		return collection( relation.context(), relation.targetCollection() ).flatMap( target -> switch (relation.definition().cardinality()) {
			case SINGLE -> Mono.from( target.updateMany( filter, Updates.unset( field ) ) ).then();
			case COLLECTION -> Mono.from( target.updateMany( filter, new Document( "$pull", new Document( field, new Document( "_id", sourceId ) ) ) ) ).then();
			case MAP -> Mono.from( target.updateMany( filter, mapRemovePipeline( field, sourceId ) ) ).then();
		} );

	}

	private Mono<Void> synchronizeDelete(
		ResolvedRelation relation, Object sourceId
	) {

		if (relation.definition().deletePolicy() == EmbeddedDeletePolicy.IGNORE)
			return Mono.empty();
		String field = relation.definition().targetField();

		return collection( relation.context(), relation.targetCollection() ).flatMap( target -> switch (relation.definition().cardinality()) {
			case SINGLE -> Mono.from( target.updateMany( Filters.eq( field + "._id", sourceId ), Updates.unset( field ) ) ).then();
			case COLLECTION -> Mono
				.from(
					target.updateMany(
						Filters.eq( field + "._id", sourceId ),
						new Document( "$pull", new Document( field, new Document( "_id", sourceId ) ) )
					)
				)
				.then();
			case MAP -> Mono.from( target.updateMany( mapContainsSourceId( field, sourceId ), mapRemovePipeline( field, sourceId ) ) ).then();
		} );

	}

	private Optional<Bson> buildLinkFilter(
		List<LinkFieldPair> links, Document source
	) {

		if (links.isEmpty())
			return Optional.empty();
		List<Bson> filters = new ArrayList<>();

		for (LinkFieldPair link : links) {
			Object value = readPath( source, link.fromField() );
			if (value == null)
				return Optional.empty();
			if (link.intoIdAlias() && value instanceof String stringValue && ObjectId.isValid( stringValue ))
				value = new ObjectId( stringValue );
			filters.add( Filters.eq( link.intoField(), value ) );

		}
		return Optional.of( filters.size() == 1 ? filters.get( 0 ) : Filters.and( filters ) );

	}

	private List<Bson> collectionUpsertPipeline(
		String field, Object sourceId, Document source
	) {

		Document items = new Document( "$ifNull", List.of( "$" + field, List.of() ) );
		Document ids = new Document( "$map", new Document( "input", "$$items" ).append( "as", "item" ).append( "in", "$$item._id" ) );
		Document replace = new Document(
			"$map",
			new Document( "input", "$$items" )
				.append( "as", "item" )
				.append(
					"in",
					new Document(
						"$cond",
						List.of(
							new Document( "$eq", List.of( "$$item._id", sourceId ) ),
							new Document( "$literal", source ),
							"$$item"
						)
					)
				)
		);
		Document append = new Document( "$concatArrays", List.of( "$$items", List.of( new Document( "$literal", source ) ) ) );
		Document value = new Document(
			"$let",
			new Document( "vars", new Document( "items", items ) )
				.append( "in", new Document( "$cond", List.of( new Document( "$in", List.of( sourceId, ids ) ), replace, append ) ) )
		);
		return List.of( new Document( "$set", new Document( field, value ) ) );

	}

	private List<Bson> mapUpsertPipeline(
		String field, Object sourceId, String mapKey, Document source
	) {

		Document withoutCurrent = mapFilteredEntries( field, sourceId );
		Document newEntry = new Document( "k", mapKey ).append( "v", new Document( "$literal", source ) );
		Document value = new Document( "$arrayToObject", new Document( "$concatArrays", List.of( withoutCurrent, List.of( newEntry ) ) ) );
		return List.of( new Document( "$set", new Document( field, value ) ) );

	}

	private List<Bson> mapRemovePipeline(
		String field, Object sourceId
	) {

		return List.of( new Document( "$set", new Document( field, new Document( "$arrayToObject", mapFilteredEntries( field, sourceId ) ) ) ) );

	}

	private Document mapFilteredEntries(
		String field, Object sourceId
	) {

		Document entries = new Document( "$objectToArray", new Document( "$ifNull", List.of( "$" + field, new Document() ) ) );
		return new Document(
			"$filter",
			new Document( "input", entries )
				.append( "as", "entry" )
				.append( "cond", new Document( "$ne", List.of( "$$entry.v._id", sourceId ) ) )
		);

	}

	private Bson mapContainsSourceId(
		String field, Object sourceId
	) {

		Document entries = new Document( "$objectToArray", new Document( "$ifNull", List.of( "$" + field, new Document() ) ) );
		Document ids = new Document( "$map", new Document( "input", entries ).append( "as", "entry" ).append( "in", "$$entry.v._id" ) );
		return Filters.expr( new Document( "$in", List.of( sourceId, ids ) ) );

	}

	private Mono<MongoCollection<Document>> collection(
		MongoExecutionContext context, String collectionName
	) {

		return context.getDatabase().map( database -> database.getCollection( collectionName ) );

	}

	private Object documentKeyId(
		ChangeStreamDocument<Document> event
	) {

		return event.getDocumentKey() == null ? null : MongoBsonSupport.toDocument( event.getDocumentKey() ).get( "_id" );

	}

	private Object readPath(
		Document source, String path
	) {

		Object current = source;
		for (String segment : MongoFieldNameSupport.toMongoField( path ).split( "\\." )) {
			if (! (current instanceof Document currentDocument))
				return null;
			current = currentDocument.get( segment );

		}
		return current;

	}

	private void validateMapKey(
		String key
	) {

		if (key.isBlank() || key.contains( "." ) || key.startsWith( "$" ))
			throw new IllegalArgumentException( "MongoDB map key cannot be blank, contain '.', or start with '$': " + key );

	}

	private RelationKey relationKey(
		ResolvedRelation relation
	) {

		return new RelationKey(
			relation.scope(),
			relation.sourceCollection(),
			relation.targetCollection(),
			relation.definition().targetField(),
			relation.definition().sourceClass(),
			relation.definition().targetClass()
		);

	}

	private void validatePhysicalGraph(
		ResolvedRelation relation
	) {

		PhysicalNode source = new PhysicalNode( relation.scope(), relation.sourceCollection() );
		PhysicalNode target = new PhysicalNode( relation.scope(), relation.targetCollection() );
		TargetPath targetPath = new TargetPath( target, relation.definition().targetField() );

		synchronized (graphLock) {
			PhysicalNode owner = targetOwners.get( targetPath );
			if (owner != null && ! owner.equals( source ))
				throw new IllegalStateException(
					"Embedded synchronization target already has another source: " + target.collection() + "." + targetPath.field()
				);
			EmbeddedSyncDefinition existingDefinition = targetDefinitions.get( targetPath );
			if (existingDefinition != null && ! existingDefinition.equals( relation.definition() ))
				throw new IllegalStateException(
					"Embedded synchronization target already has a different physical definition: " + target.collection() + "." + targetPath.field()
				);

			graph.computeIfAbsent( source, ignored -> new HashSet<>() ).add( target );
			if (hasPath( target, source )) {
				graph.getOrDefault( source, Set.of() ).remove( target );
				throw new IllegalStateException(
					"Embedded synchronization cycle detected: " + source.collection() + " -> " + target.collection()
				);

			}
			targetOwners.put( targetPath, source );
			targetDefinitions.put( targetPath, relation.definition() );

		}

	}

	private boolean hasPath(
		PhysicalNode start, PhysicalNode target
	) {

		Deque<PhysicalNode> queue = new ArrayDeque<>();
		Set<PhysicalNode> visited = new HashSet<>();
		queue.push( start );

		while (! queue.isEmpty()) {
			PhysicalNode current = queue.pop();
			if (! visited.add( current ))
				continue;
			if (current.equals( target ))
				return true;
			for (PhysicalNode next : graph.getOrDefault( current, Set.of() ))
				queue.push( next );

		}
		return false;

	}

	@Override
	public void close() {

		Disposable task = flushTask.getAndSet( null );
		if (task != null)
			task.dispose();
		for (Disposable subscription : subscriptions.values())
			subscription.dispose();
		subscriptions.clear();
		for (Map.Entry<RelationKey, String> entry : leaseKeys.entrySet()) {
			AtomicLong deadline = leaseDeadlines.get( entry.getKey() );
			if (deadline != null && deadline.get() > System.nanoTime())
				leaseStore.release( entry.getValue(), leaseOwnerId ).subscribe( ignored -> {}, ignored -> {} );

		}
		leaseKeys.clear();
		leaseDeadlines.clear();
		relations.clear();
		pending.get().clear();
		synchronized (graphLock) {
			graph.clear();
			targetOwners.clear();
			targetDefinitions.clear();

		}

	}

}
