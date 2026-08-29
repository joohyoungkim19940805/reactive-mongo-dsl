package com.byeolnaerim.mongodsl.state;


import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * MongoDB-backed implementation of all ReactiveMongoDsl state-store responsibilities.
 * <p>A single internal collection stores cursor anchors, opaque cursor tokens, namespace versions, Change Stream
 * checkpoints, and embedded-sync leases. When this collection lives in the same database that is
 * being watched, {@link com.byeolnaerim.mongodsl.change.ChangeStreamHub} excludes it from the
 * database Change Stream pipeline to prevent state-write feedback loops.</p>
 * <p>The supplied MongoDB client/database lifecycle remains owned by the caller.</p>
 */
public final class MongoReactiveMongoDslStateStore implements ReactiveMongoDslStateStore {

	private static final String KIND_CURSOR_ANCHOR = "cursorAnchor";

	private static final String KIND_CURSOR_TOKEN = "cursorToken";

	private static final String KIND_NAMESPACE = "namespaceVersion";

	private static final String KIND_CHECKPOINT = "changeStreamCheckpoint";

	private static final String KIND_LEASE = "embeddedSyncLease";

	private final Mono<MongoDatabase> database;

	private final Object stateSessionScope;

	private final MongoDatabase fixedDatabase;

	private final MongoReactiveMongoDslStateStoreOptions options;

	private final CursorCacheOptions cursorOptions;

	private final String changeStreamConsumerId;

	private final CursorAdmissionTracker admissionTracker;

	private final Mono<Void> initialization;

	public MongoReactiveMongoDslStateStore(
		MongoExecutionContext executionContext
	) {

		this( executionContext, MongoReactiveMongoDslStateStoreOptions.defaults() );

	}

	public MongoReactiveMongoDslStateStore(
		MongoExecutionContext executionContext,
		MongoReactiveMongoDslStateStoreOptions options
	) {

		this(
			Objects.requireNonNull( executionContext, "executionContext must not be null" ).getDatabase(),
			executionContext.getSessionScope(),
			null,
			options
		);

	}

	public MongoReactiveMongoDslStateStore(
		MongoDatabase database
	) {

		this(
			Mono.just( Objects.requireNonNull( database, "database must not be null" ) ),
			null,
			database,
			MongoReactiveMongoDslStateStoreOptions.defaults()
		);

	}

	public MongoReactiveMongoDslStateStore(
		MongoDatabase database,
		MongoReactiveMongoDslStateStoreOptions options
	) {

		this(
			Mono.just( Objects.requireNonNull( database, "database must not be null" ) ),
			null,
			database,
			options
		);

	}

	public MongoReactiveMongoDslStateStore(
		Mono<MongoDatabase> database,
		MongoReactiveMongoDslStateStoreOptions options
	) {

		this( database, null, null, options );

	}

	private MongoReactiveMongoDslStateStore(
		Mono<MongoDatabase> database,
		Object stateSessionScope,
		MongoDatabase fixedDatabase,
		MongoReactiveMongoDslStateStoreOptions options
	) {

		this.database = Objects.requireNonNull( database, "database must not be null" ).cache();
		this.stateSessionScope = stateSessionScope;
		this.fixedDatabase = fixedDatabase;
		this.options = options == null ? MongoReactiveMongoDslStateStoreOptions.defaults() : options;
		this.cursorOptions = this.options.cursorCacheOptions();
		this.changeStreamConsumerId = this.options.changeStreamConsumerId() == null
			? UUID.randomUUID().toString()
			: this.options.changeStreamConsumerId();
		this.admissionTracker = new CursorAdmissionTracker( this.cursorOptions );
		this.initialization = Mono.defer( this::initialize ).cache();

	}

	@Override
	public CursorCacheOptions cursorCacheOptions() { return cursorOptions; }

	@Override
	public Mono<Void> putToken(
		String token, CursorTokenState state, Duration ttl
	) {

		Objects.requireNonNull( token, "token must not be null" );
		Objects.requireNonNull( state, "cursor token state must not be null" );
		if (token.isBlank())
			return Mono.error( new IllegalArgumentException( "token must not be blank" ) );
		if (ttl == null || ttl.isZero() || ttl.isNegative())
			return Mono.error( new IllegalArgumentException( "cursor token ttl must be > 0" ) );
		Document document = new Document( "_id", cursorTokenId( token ) )
			.append( "kind", KIND_CURSOR_TOKEN )
			.append( "token", token )
			.append( "queryKey", state.queryKey() )
			.append( "pageSize", state.pageSize() )
			.append( "sortValues", state.sortValues() )
			.append( "expiresAt", new Date( System.currentTimeMillis() + ttl.toMillis() ) );
		return collection()
			.flatMap( collection -> Mono.from( collection.replaceOne( Filters.eq( "_id", document.getString( "_id" ) ), document, new ReplaceOptions().upsert( true ) ) ) )
			.then();

	}

	@Override
	public Mono<Optional<CursorTokenState>> resolveToken(
		String token
	) {

		if (token == null || token.isBlank())
			return Mono.just( Optional.empty() );
		Date now = new Date();
		return collection().flatMap( collection -> Mono
			.from( collection.find( Filters.and( Filters.eq( "_id", cursorTokenId( token ) ), Filters.gt( "expiresAt", now ) ) ).first() )
			.map( document -> Optional.of(
				new CursorTokenState(
					document.getString( "queryKey" ),
					document.getInteger( "pageSize" ),
					document.get( "sortValues", Document.class )
				)
			) )
			.defaultIfEmpty( Optional.empty() )
		);

	}

	@Override
	public Mono<Optional<CursorAnchor>> floor(
		String queryKey, int pageNumber, long estimatedSkip
	) {

		Objects.requireNonNull( queryKey, "queryKey must not be null" );
		if (pageNumber <= 0 || ! admissionTracker.admit( queryKey, estimatedSkip ))
			return Mono.just( Optional.empty() );
		Date now = new Date();
		return collection().flatMap( collection -> Mono
			.from(
				collection
					.find(
						Filters.and(
							Filters.eq( "kind", KIND_CURSOR_ANCHOR ),
							Filters.eq( "queryKey", queryKey ),
							Filters.lte( "pageNumber", pageNumber ),
							Filters.gt( "expiresAt", now )
						)
					)
					.sort( Sorts.descending( "pageNumber" ) )
					.limit( 1 )
					.first()
			)
			.map( document -> Optional.of( new CursorAnchor( document.getInteger( "pageNumber" ), document.get( "sortValues", Document.class ) ) ) )
			.defaultIfEmpty( Optional.empty() )
		);

	}

	@Override
	public Mono<Void> put(
		String queryKey, CursorAnchor anchor
	) {

		Objects.requireNonNull( queryKey, "queryKey must not be null" );
		Objects.requireNonNull( anchor, "anchor must not be null" );
		if (! admissionTracker.isAdmitted( queryKey ))
			return Mono.empty();
		Document document = new Document( "_id", cursorAnchorId( queryKey, anchor.pageNumber() ) )
			.append( "kind", KIND_CURSOR_ANCHOR )
			.append( "queryKey", queryKey )
			.append( "pageNumber", anchor.pageNumber() )
			.append( "sortValues", anchor.sortValues() )
			.append( "expiresAt", new Date( System.currentTimeMillis() + cursorOptions.idleTtl().toMillis() ) );
		return collection()
			.flatMap( collection -> Mono.from( collection.replaceOne( Filters.eq( "_id", document.getString( "_id" ) ), document, new ReplaceOptions().upsert( true ) ) ) )
			.then();

	}

	@Override
	public Mono<Long> namespaceVersion(
		String namespaceKey
	) {

		Objects.requireNonNull( namespaceKey, "namespaceKey must not be null" );
		return collection().flatMap( collection -> Mono
			.from( collection.find( Filters.eq( "_id", namespaceId( namespaceKey ) ) ).first() )
			.map( document -> Optional.ofNullable( document.get( "version", Number.class ) ).map( Number::longValue ).orElse( 0L ) )
			.defaultIfEmpty( 0L )
		);

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey
	) {

		Objects.requireNonNull( namespaceKey, "namespaceKey must not be null" );
		return collection()
			.flatMap( collection -> Mono.from(
				collection.updateOne(
					Filters.eq( "_id", namespaceId( namespaceKey ) ),
					Updates.combine(
						Updates.setOnInsert( "kind", KIND_NAMESPACE ),
						Updates.setOnInsert( "namespaceKey", namespaceKey ),
						Updates.inc( "version", 1L )
					),
					new UpdateOptions().upsert( true )
				)
			))
			.then();

	}

	@Override
	public Mono<Void> invalidateNamespace(
		String namespaceKey, BsonTimestamp clusterTime
	) {

		Objects.requireNonNull( namespaceKey, "namespaceKey must not be null" );
		if (clusterTime == null)
			return invalidateNamespace( namespaceKey );

		long seconds = Integer.toUnsignedLong( clusterTime.getTime() );
		long increment = Integer.toUnsignedLong( clusterTime.getInc() );
		Bson newerThanStored = Filters.or(
			Filters.exists( "clusterTimeSeconds", false ),
			Filters.lt( "clusterTimeSeconds", seconds ),
			Filters.and( Filters.eq( "clusterTimeSeconds", seconds ), Filters.lt( "clusterTimeIncrement", increment ) )
		);

		return collection()
			.flatMap( collection -> Mono.from(
				collection.updateOne(
					Filters.and( Filters.eq( "_id", namespaceId( namespaceKey ) ), newerThanStored ),
					Updates.combine(
						Updates.setOnInsert( "kind", KIND_NAMESPACE ),
						Updates.setOnInsert( "namespaceKey", namespaceKey ),
						Updates.inc( "version", 1L ),
						Updates.set( "clusterTimeSeconds", seconds ),
						Updates.set( "clusterTimeIncrement", increment )
					),
					new UpdateOptions().upsert( true )
				)
			))
			// The same or an older event can lose the upsert race against the existing _id.
			.onErrorResume( this::isDuplicateKey, ignored -> Mono.empty() )
			.then();

	}

	@Override
	public Mono<BsonDocument> load(
		ChangeStreamScope scope
	) {

		String persistentKey = persistentScopeKey( scope );
		return collection().flatMapMany( collection -> Flux
			.from( collection.find( Filters.and(
				Filters.eq( "_id", checkpointId( persistentKey ) ),
				Filters.gt( "expiresAt", new Date() )
			) ).limit( 1 ) )
			.map( document -> document.getString( "resumeToken" ) )
			.filter( Objects::nonNull )
			.map( BsonDocument::parse )
		).next();

	}

	@Override
	public Mono<Void> save(
		ChangeStreamScope scope, BsonDocument resumeToken
	) {

		Objects.requireNonNull( resumeToken, "resumeToken must not be null" );
		String persistentKey = persistentScopeKey( scope );
		Document document = new Document( "_id", checkpointId( persistentKey ) )
			.append( "kind", KIND_CHECKPOINT )
			.append( "scopeKey", persistentKey )
			.append( "consumerId", changeStreamConsumerId )
			.append( "resumeToken", resumeToken.toJson() )
			.append( "expiresAt", new Date( System.currentTimeMillis() + Duration.ofDays( 7 ).toMillis() ) );
		return collection()
			.flatMap( collection -> Mono.from( collection.replaceOne( Filters.eq( "_id", document.getString( "_id" ) ), document, new ReplaceOptions().upsert( true ) ) ) )
			.then();

	}

	@Override
	public Mono<Void> delete(
		ChangeStreamScope scope
	) {

		String persistentKey = persistentScopeKey( scope );
		return collection()
			.flatMap( collection -> Mono.from( collection.deleteOne( Filters.eq( "_id", checkpointId( persistentKey ) ) ) ) )
			.then();

	}

	@Override
	public Mono<Boolean> tryAcquire(
		String leaseKey, String ownerId, Duration ttl
	) {

		validateLease( leaseKey, ownerId, ttl );
		Date now = new Date();
		Date expiresAt = new Date( now.getTime() + ttl.toMillis() );
		return collection().flatMap( collection -> Mono
			.from(
				collection.updateOne(
					Filters.and(
						Filters.eq( "_id", leaseId( leaseKey ) ),
						Filters.or( Filters.eq( "ownerId", ownerId ), Filters.lte( "expiresAt", now ) )
					),
					Updates.combine(
						Updates.set( "kind", KIND_LEASE ),
						Updates.set( "leaseKey", leaseKey ),
						Updates.set( "ownerId", ownerId ),
						Updates.set( "expiresAt", expiresAt )
					),
					new UpdateOptions().upsert( true )
				)
			)
			.map( result -> result.getMatchedCount() > 0 || result.getUpsertedId() != null )
			.onErrorResume( this::isDuplicateKey, ignored -> Mono.just( false ) )
		);

	}

	@Override
	public Mono<Boolean> renew(
		String leaseKey, String ownerId, Duration ttl
	) {

		validateLease( leaseKey, ownerId, ttl );
		Date now = new Date();
		Date expiresAt = new Date( now.getTime() + ttl.toMillis() );
		return collection().flatMap( collection -> Mono
			.from(
				collection.updateOne(
					Filters.and(
						Filters.eq( "_id", leaseId( leaseKey ) ),
						Filters.eq( "ownerId", ownerId ),
						Filters.gt( "expiresAt", now )
					),
					Updates.set( "expiresAt", expiresAt )
				)
			)
			.map( result -> result.getMatchedCount() > 0 )
		);

	}

	@Override
	public Mono<Void> release(
		String leaseKey, String ownerId
	) {

		Objects.requireNonNull( leaseKey, "leaseKey must not be null" );
		Objects.requireNonNull( ownerId, "ownerId must not be null" );
		return collection()
			.flatMap( collection -> Mono.from( collection.deleteOne( Filters.and( Filters.eq( "_id", leaseId( leaseKey ) ), Filters.eq( "ownerId", ownerId ) ) ) ) )
			.then();

	}

	@Override
	public boolean requiresDistributedStateScopeKey() { return true; }

	@Override
	public Mono<Set<String>> changeStreamExcludedCollections(
		MongoExecutionContext executionContext, MongoDatabase watchedDatabase
	) {

		Objects.requireNonNull( executionContext, "executionContext must not be null" );
		Objects.requireNonNull( watchedDatabase, "watchedDatabase must not be null" );
		return database.map( stateDatabase -> {
			if (! stateDatabase.getName().equals( watchedDatabase.getName() ))
				return Set.of();
			boolean sameScope = stateSessionScope != null
				? stateSessionScope == executionContext.getSessionScope()
				: fixedDatabase != null && fixedDatabase == watchedDatabase;
			return sameScope ? Set.of( options.collectionName() ) : Set.of();

		} );

	}

	private Mono<MongoCollection<Document>> collection() {

		return initialization.then( database.map( value -> value.getCollection( options.collectionName() ) ) );

	}

	private Mono<Void> initialize() {

		if (! options.ensureIndexes())
			return Mono.empty();
		return database.flatMap( value -> {
			MongoCollection<Document> collection = value.getCollection( options.collectionName() );
			return Flux.concat(
				Mono.from(
					collection.createIndex(
						Indexes.ascending( "expiresAt" ),
						new IndexOptions().name( "idx_reactive_mongo_dsl_state_ttl" ).expireAfter( 0L, TimeUnit.SECONDS )
					)
				),
				Mono.from(
					collection.createIndex(
						Indexes.compoundIndex( Indexes.ascending( "kind", "queryKey" ), Indexes.descending( "pageNumber" ) ),
						new IndexOptions().name( "idx_reactive_mongo_dsl_cursor_floor" )
					)
				)
			).then();

		} );

	}

	private String persistentScopeKey(
		ChangeStreamScope scope
	) {

		Objects.requireNonNull( scope, "scope must not be null" );
		String key = scope.persistentKey();
		if (key == null)
			throw new IllegalStateException( "MongoReactiveMongoDslStateStore requires a distributed state scope key." );
		return key;

	}

	private boolean isDuplicateKey(
		Throwable error
	) {

		Throwable current = error;
		while (current != null) {
			if (current instanceof MongoException mongoException && mongoException.getCode() == 11000)
				return true;
			current = current.getCause();

		}
		return false;

	}

	private static void validateLease(
		String leaseKey, String ownerId, Duration ttl
	) {

		Objects.requireNonNull( leaseKey, "leaseKey must not be null" );
		Objects.requireNonNull( ownerId, "ownerId must not be null" );
		if (ttl == null || ttl.isZero() || ttl.isNegative())
			throw new IllegalArgumentException( "ttl must be > 0" );

	}

	private static String cursorTokenId(
		String token
	) { return "cursorToken:" + token; }

	private static String cursorAnchorId(
		String queryKey, int pageNumber
	) { return "cursor:" + queryKey + ":" + pageNumber; }

	private static String namespaceId(
		String namespaceKey
	) { return "namespace:" + namespaceKey; }

	private String checkpointId(
		String scopeKey
	) { return "checkpoint:" + scopeKey + ":" + changeStreamConsumerId; }

	private static String leaseId(
		String leaseKey
	) { return "lease:" + leaseKey; }

	@Override
	public void close() {

		admissionTracker.close();

	}

}
