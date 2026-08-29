package com.byeolnaerim.mongodsl;


import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bson.BinaryVector;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.reactivestreams.Publisher;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.ExecuteBuilder;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.CountAggregation;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.CountExecute;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.ExistsAggregation;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.ExistsExecute;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAggregation;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAllAggregation;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAllExecute;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindExecute;
import com.byeolnaerim.mongodsl.change.ChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.change.ChangeStreamHub;
import com.byeolnaerim.mongodsl.criteria.FieldsPair;
import com.byeolnaerim.mongodsl.criteria.FieldsPairBsonSupport;
import com.byeolnaerim.mongodsl.internal.CursorNamespaceCoordinator;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorAnchorStore;
import com.byeolnaerim.mongodsl.paging.CursorPaginationSupport;
import com.byeolnaerim.mongodsl.paging.CursorSkipExceededAction;
import com.byeolnaerim.mongodsl.paging.CursorSkipLimitExceededException;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.result.CursorPage;
import com.byeolnaerim.mongodsl.result.PageResult;
import com.byeolnaerim.mongodsl.result.PageStream;
import com.byeolnaerim.mongodsl.result.ResultTuple;
import com.byeolnaerim.mongodsl.search.AtlasSearchOperator;
import com.byeolnaerim.mongodsl.search.AutocompleteClause;
import com.byeolnaerim.mongodsl.search.EqualsClause;
import com.byeolnaerim.mongodsl.search.ExistsClause;
import com.byeolnaerim.mongodsl.search.InClause;
import com.byeolnaerim.mongodsl.search.PhraseClause;
import com.byeolnaerim.mongodsl.search.RangeClause;
import com.byeolnaerim.mongodsl.search.SearchCountType;
import com.byeolnaerim.mongodsl.search.SearchHighlightSpec;
import com.byeolnaerim.mongodsl.search.SearchOperators;
import com.byeolnaerim.mongodsl.search.SearchPathResolver;
import com.byeolnaerim.mongodsl.search.SearchScoreSpec;
import com.byeolnaerim.mongodsl.search.TextClause;
import com.byeolnaerim.mongodsl.sort.SortSpec;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import com.byeolnaerim.mongodsl.state.InMemoryReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncEngine;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncLeaseStore;
import com.byeolnaerim.mongodsl.sync.InMemoryEmbeddedSyncLeaseStore;
import com.mongodb.ExplainVerbosity;
import com.mongodb.ReadPreference;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.Facet;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UnwindOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.Variable;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.search.CompoundSearchOperator;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchHighlight;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchOptions;
import com.mongodb.client.model.search.SearchScore;
import com.mongodb.client.model.search.ShouldCompoundSearchOperator;
import com.mongodb.client.model.search.TextVectorSearchQuery;
import com.mongodb.client.model.search.VectorSearchNestedOptions;
import com.mongodb.client.model.search.VectorSearchOptions;
import com.mongodb.client.model.search.VectorSearchQuery;
import com.mongodb.client.model.search.VectorSearchScoreMode;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.AggregatePublisher;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoCollection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


/**
 * Fluent reactive MongoDB DSL built on top of {@link MongoExecutionContext}.
 * <p>This DSL helps compose dynamic criteria, aggregation pipelines, lookup joins,
 * bulk operations, and atomic updates in a reactive style.</p>
 * <p>Mongo execution-context resolution is delegated to {@link MongoTemplateResolver},
 * which makes this DSL suitable for multi-database or multi-tenant use cases.</p>
 * <p>The DSL is intentionally an application-level convenience layer, not a replacement for the
 * MongoDB Java Driver. Convenience methods delegate MongoDB syntax and serialization to driver
 * builders whenever a typed driver API exists, while driver-native {@link Bson} escape hatches
 * remain available for advanced or newly introduced MongoDB features.</p>
 *
 * @param <K>
 *            the logical key type used to resolve the target Mongo execution context
 */
public class ReactiveMongoDsl<K> implements AutoCloseable {

	private final MongoTemplateResolver<K> resolver;

	private final ObjectMapper objectMapper;

	private final ReactiveMongoDslStateStore stateStore;

	private final CursorAnchorStore cursorAnchorStore;

	private final ChangeStreamHub changeStreamHub;

	private final EmbeddedSyncEngine embeddedSyncEngine;

	private final EmbeddedSyncLeaseStore embeddedSyncLeaseStore;

	private final Mono<Void> embeddedSyncInitialization;

	private final CursorNamespaceCoordinator cursorNamespaceCoordinator;

	private static final Object CLIENT_SESSION_CONTEXT_KEY = new Object();

	private static final String LOOKUP_LEFT_RESULT_FIELD = "__reactiveMongoDslLookupLeft";

	private static final String LOOKUP_RIGHT_RESULT_FIELD = "__reactiveMongoDslLookupRight";

	private record SessionBinding(Object sessionScope, ClientSession session) {}

	private record CursorSkipResolution(long relativeSkip, boolean returnEmpty) {}

	/**
	 * Creates a new DSL instance using the given resolver and the default process-local unified
	 * state store.
	 */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver
	) {

		this( resolver, JsonMapper.builder().build(), (EmbeddedSyncConfig<K>) null, new InMemoryReactiveMongoDslStateStore() );

	}

	/** Creates a new DSL instance with embedded synchronization and the default unified state store. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							EmbeddedSyncConfig<K> embeddedSyncConfig
	) {

		this( resolver, JsonMapper.builder().build(), embeddedSyncConfig, new InMemoryReactiveMongoDslStateStore() );

	}

	/** Uses one state store for cursor paging, Change Stream checkpoints, and embedded-sync leases. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ReactiveMongoDslStateStore stateStore
	) {

		this( resolver, JsonMapper.builder().build(), (EmbeddedSyncConfig<K>) null, stateStore );

	}

	/** Uses one state store for all three features together with embedded synchronization. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							EmbeddedSyncConfig<K> embeddedSyncConfig,
							ReactiveMongoDslStateStore stateStore
	) {

		this( resolver, JsonMapper.builder().build(), embeddedSyncConfig, stateStore );

	}

	/** Creates a new DSL instance using the given resolver and object mapper. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ObjectMapper objectMapper
	) {

		this( resolver, objectMapper, (EmbeddedSyncConfig<K>) null, new InMemoryReactiveMongoDslStateStore() );

	}

	/** Creates a DSL instance with an explicit mapper and one unified state store. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ObjectMapper objectMapper,
							ReactiveMongoDslStateStore stateStore
	) {

		this( resolver, objectMapper, (EmbeddedSyncConfig<K>) null, stateStore );

	}

	/**
	 * Primary constructor. The supplied state store is used by cursor anchors, namespace versions,
	 * Change Stream checkpoints, and embedded-sync leases unless EmbeddedSyncConfig explicitly
	 * overrides only the lease store.
	 */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ObjectMapper objectMapper,
							EmbeddedSyncConfig<K> embeddedSyncConfig,
							ReactiveMongoDslStateStore stateStore
	) {

		this.resolver = Objects.requireNonNull( resolver, "resolver must not be null" );
		this.objectMapper = Objects.requireNonNull( objectMapper, "objectMapper must not be null" );
		this.stateStore = Objects.requireNonNull( stateStore, "stateStore must not be null" );
		this.cursorAnchorStore = this.stateStore;
		this.embeddedSyncLeaseStore = embeddedSyncConfig == null ? this.stateStore : embeddedSyncConfig.leaseStoreOr( this.stateStore );
		this.changeStreamHub = new ChangeStreamHub( this.stateStore, this.stateStore, this.embeddedSyncLeaseStore );
		this.cursorNamespaceCoordinator = new CursorNamespaceCoordinator( this.changeStreamHub, this.cursorAnchorStore );

		if (embeddedSyncConfig == null) {
			this.embeddedSyncEngine = null;
			this.embeddedSyncInitialization = Mono.empty();

		} else {
			this.embeddedSyncEngine = new EmbeddedSyncEngine( this.changeStreamHub, this.embeddedSyncLeaseStore );
			this.embeddedSyncInitialization = Flux
				.fromIterable( embeddedSyncConfig.registrations() )
				.concatMap(
					registration -> Flux
						.fromIterable( registration.keys() )
						.concatMap( key -> this.embeddedSyncEngine.register( getMongoTemplate( key ), registration.definition() ) )
						.then()
				)
				.then()
				.cache();
			this.embeddedSyncInitialization.subscribe( ignored -> {}, ignored -> {} );

		}

	}

	/**
	 * Advanced compatibility constructor for independently configured cursor/checkpoint stores.
	 * Prefer
	 * {@link ReactiveMongoDslStateStore#of(CursorAnchorStore, ChangeStreamCheckpointStore, EmbeddedSyncLeaseStore)}
	 * when all three features intentionally use different backends.
	 */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							CursorAnchorStore cursorAnchorStore,
							ChangeStreamCheckpointStore checkpointStore
	) {

		this( resolver, JsonMapper.builder().build(), (EmbeddedSyncConfig<K>) null, legacyStateStore( cursorAnchorStore, checkpointStore ) );

	}

	/** Advanced compatibility constructor with embedded synchronization and separate stores. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							EmbeddedSyncConfig<K> embeddedSyncConfig,
							CursorAnchorStore cursorAnchorStore,
							ChangeStreamCheckpointStore checkpointStore
	) {

		this( resolver, JsonMapper.builder().build(), embeddedSyncConfig, legacyStateStore( cursorAnchorStore, checkpointStore ) );

	}

	/** Advanced compatibility constructor with an explicit mapper and separate stores. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ObjectMapper objectMapper,
							CursorAnchorStore cursorAnchorStore,
							ChangeStreamCheckpointStore checkpointStore
	) {

		this( resolver, objectMapper, (EmbeddedSyncConfig<K>) null, legacyStateStore( cursorAnchorStore, checkpointStore ) );

	}

	/** Advanced compatibility constructor with embedded synchronization and separate stores. */
	public ReactiveMongoDsl(
							MongoTemplateResolver<K> resolver,
							ObjectMapper objectMapper,
							EmbeddedSyncConfig<K> embeddedSyncConfig,
							CursorAnchorStore cursorAnchorStore,
							ChangeStreamCheckpointStore checkpointStore
	) {

		this( resolver, objectMapper, embeddedSyncConfig, legacyStateStore( cursorAnchorStore, checkpointStore ) );

	}

	private static ReactiveMongoDslStateStore legacyStateStore(
		CursorAnchorStore cursorAnchorStore, ChangeStreamCheckpointStore checkpointStore
	) {

		return ReactiveMongoDslStateStore
			.of(
				Objects.requireNonNull( cursorAnchorStore, "cursorAnchorStore must not be null" ),
				Objects.requireNonNull( checkpointStore, "checkpointStore must not be null" ),
				new InMemoryEmbeddedSyncLeaseStore()
			);

	}

	Mono<Void> embeddedSyncInitialization() {

		return embeddedSyncInitialization;

	}


	/**
	 * Returns the {@link MongoExecutionContext} resolved for the given key.
	 *
	 * @param key
	 *            the logical Mongo execution-context key
	 * 
	 * @return the resolved Mongo execution context
	 */
	public MongoExecutionContext getMongoTemplate(
		K key
	) {

		return resolver.getTemplate( key );

	}

	/** Returns the shared change-stream facade used by all DSL change-stream features. */
	public ChangeStreams changeStreams() {

		return new ChangeStreams();

	}

	/** Shared ChangeStreamHub access without opening duplicate physical MongoDB streams. */
	public final class ChangeStreams {

		public Flux<ChangeStreamDocument<Document>> watch(
			K key
		) {

			return changeStreamHub.watch( getMongoTemplate( key ) );

		}

		public Flux<ChangeStreamDocument<Document>> watch(
			K key, Class<?> entityClass
		) {

			MongoExecutionContext context = getMongoTemplate( key );
			return changeStreamHub.watchCollection( context, context.getCollectionName( entityClass ) );

		}

		public Flux<ChangeStreamDocument<Document>> watch(
			K key, String collectionName
		) {

			return changeStreamHub.watchCollection( getMongoTemplate( key ), collectionName );

		}

	}

	/**
	 * Executes the supplied reactive job in a MongoDB client-session transaction.
	 * The session is propagated through Reactor Context and picked up by DSL terminal operations.
	 *
	 * @param <T>
	 *            the result type
	 * @param key
	 *            the logical Mongo execution-context key
	 * @param supplier
	 *            the deferred reactive job to execute
	 * 
	 * @return a transactional {@link Mono} wrapping the supplied job
	 */
	public <T> Mono<T> getTxJob(
		K key, Supplier<? extends Mono<? extends T>> supplier
	) {

		MongoExecutionContext executionContext = resolver.getTemplate( key );

		return Mono
			.usingWhen(
				executionContext.startSession(),
				session -> {
					session.startTransaction();
					return Mono
						.defer( supplier )
						.contextWrite( context -> context.put( CLIENT_SESSION_CONTEXT_KEY, new SessionBinding( executionContext.getSessionScope(), session ) ) )
						.flatMap( value -> commitTransaction( session ).thenReturn( value ) )
						.switchIfEmpty( commitTransaction( session ).then( Mono.empty() ) );

				},
				session -> Mono.fromRunnable( session::close ),
				(session, error) -> abortTransactionIfActive( session ).then( Mono.fromRunnable( session::close ) ),
				session -> abortTransactionIfActive( session ).then( Mono.fromRunnable( session::close ) )
			);

	}

	private Mono<Void> commitTransaction(
		ClientSession session
	) {

		return Mono.defer( () -> Mono.from( session.commitTransaction() ).then() );

	}

	private Mono<Void> abortTransactionIfActive(
		ClientSession session
	) {

		return Mono
			.defer( () -> session.hasActiveTransaction() ? Mono.from( session.abortTransaction() ).then() : Mono.empty() )
			.onErrorResume( ignored -> Mono.empty() );

	}


	private static Document copyDocument(
		Document source
	) {

		Document copy = new Document();
		source.forEach( (key, value) -> copy.put( key, copyDocumentValue( value ) ) );
		return copy;

	}

	private static Object copyDocumentValue(
		Object value
	) {

		if (value instanceof Document document)
			return copyDocument( document );

		if (value instanceof Map<?, ?> map) {
			Document copy = new Document();
			map.forEach( (key, nestedValue) -> copy.put( String.valueOf( key ), copyDocumentValue( nestedValue ) ) );
			return copy;

		}

		if (value instanceof Collection<?> collection)
			return collection.stream().map( ReactiveMongoDsl::copyDocumentValue ).toList();
		if (value instanceof byte[] bytes)
			return bytes.clone();
		if (value instanceof java.util.Date date)
			return new java.util.Date( date.getTime() );
		return value;

	}

	private static Object readDocumentPath(
		Document document, String path
	) {

		Object current = document;

		for (String segment : path.split( "\\." )) {
			if (! (current instanceof Document currentDocument))
				return null;
			current = currentDocument.get( segment );

		}

		return current;

	}

	private static void removeDocumentPath(
		Document document, String path
	) {

		String[] segments = path.split( "\\." );
		Document current = document;

		for (int i = 0; i < segments.length - 1; i++) {
			Object nested = current.get( segments[i] );
			if (! (nested instanceof Document nestedDocument))
				return;
			current = nestedDocument;

		}

		current.remove( segments[segments.length - 1] );

	}

	private static <T> List<T> readLookupValues(
		MongoExecutionContext executionContext, Class<T> targetClass, Object rawValue
	) {

		if (rawValue instanceof Document document)
			return List.of( executionContext.read( targetClass, document ) );
		if (! (rawValue instanceof Collection<?> collection))
			return List.of();
		return collection
			.stream()
			.filter( Document.class::isInstance )
			.map( Document.class::cast )
			.map( document -> executionContext.read( targetClass, document ) )
			.toList();

	}

	private static void appendLookupStages(
		List<Bson> operations, String rightCollection, String rightAs, Optional<Bson> rightCriteria, LookupSpec spec
	) {

		List<Bson> pipeline = new ArrayList<>();
		rightCriteria.ifPresent( criteria -> pipeline.add( Aggregates.match( criteria ) ) );

		Document let = spec.getLetDoc() == null ? new Document() : new Document( spec.getLetDoc() );

		if (spec.getLocalField() != null && spec.getForeignField() != null && (! pipeline.isEmpty() || ! let.isEmpty() || ! spec.getPipelineDocs().isEmpty())) {
			String localVariable = "vlf";
			let.put( localVariable, "$" + spec.getLocalField() );
			pipeline
				.add(
					Aggregates
						.match(
							new Document(
								"$expr",
								new Document( "$eq", List.of( "$" + spec.getForeignField(), "$$" + localVariable ) )
							)
						)
				);

		}

		pipeline.addAll( spec.getPipelineDocs() );

		if (spec.getLocalField() != null && spec.getForeignField() != null && pipeline.isEmpty() && let.isEmpty()) {
			operations.add( Aggregates.lookup( rightCollection, spec.getLocalField(), spec.getForeignField(), rightAs ) );

		} else if (let.isEmpty()) {
			operations.add( Aggregates.lookup( rightCollection, pipeline, rightAs ) );

		} else {
			List<Variable<Object>> variables = let
				.entrySet()
				.stream()
				.map( entry -> new Variable<Object>( entry.getKey(), entry.getValue() ) )
				.toList();
			operations.add( Aggregates.lookup( rightCollection, variables, pipeline, rightAs ) );

		}

		if (spec.isUnwind()) {
			operations
				.add(
					Aggregates
						.unwind(
							"$" + rightAs,
							new UnwindOptions().preserveNullAndEmptyArrays( spec.isPreserveNullAndEmptyArrays() )
						)
				);

		}

		operations.addAll( spec.getOuterStages() );

	}

	private static final class FindSpec {

		private Bson filter = new Document();

		private Bson sort;

		private Bson projection;

		private long skip;

		private int limit;

		private ReadPreference readPreference;

		private Boolean allowDiskUse;

		private Consumer<FindPublisher<Document>> customizer = ignored -> {};

		FindSpec filter(
			Bson filter
		) {

			this.filter = filter == null ? new Document() : filter;
			return this;

		}

		FindSpec sort(
			Bson sort
		) {

			this.sort = sort;
			return this;

		}

		FindSpec projection(
			Bson projection
		) {

			this.projection = projection;
			return this;

		}

		FindSpec skip(
			long skip
		) {

			this.skip = skip;
			return this;

		}

		FindSpec limit(
			int limit
		) {

			this.limit = limit;
			return this;

		}

		FindSpec readPreference(
			ReadPreference readPreference
		) {

			this.readPreference = readPreference;
			return this;

		}

		FindSpec allowDiskUse(
			Boolean allowDiskUse
		) {

			this.allowDiskUse = allowDiskUse;
			return this;

		}

		FindSpec customize(
			Consumer<FindPublisher<Document>> customizer
		) {

			if (customizer != null)
				this.customizer = this.customizer.andThen( customizer );
			return this;

		}

	}


	private static final class AggregationSpec {

		private final List<Bson> pipeline;

		private ReadPreference readPreference;

		private Boolean allowDiskUse;

		private Consumer<AggregatePublisher<Document>> customizer = ignored -> {};

		private AggregationSpec(
								List<? extends Bson> pipeline
		) {

			this.pipeline = List.copyOf( pipeline );

		}

		AggregationSpec readPreference(
			ReadPreference readPreference
		) {

			this.readPreference = readPreference;
			return this;

		}

		AggregationSpec allowDiskUse(
			Boolean allowDiskUse
		) {

			this.allowDiskUse = allowDiskUse;
			return this;

		}

		AggregationSpec customize(
			Consumer<AggregatePublisher<Document>> customizer
		) {

			if (customizer != null)
				this.customizer = this.customizer.andThen( customizer );
			return this;

		}

	}

	private static final class UpdateSpec {

		private final Bson update;

		private final List<Bson> pipeline;

		private UpdateSpec(
							Bson update,
							List<Bson> pipeline
		) {

			this.update = update;
			this.pipeline = pipeline;

		}

		static UpdateSpec document(
			Bson update
		) {

			return new UpdateSpec( Objects.requireNonNull( update, "update must not be null" ), List.of() );

		}

		static UpdateSpec pipeline(
			List<? extends Bson> pipeline
		) {

			return new UpdateSpec( null, List.copyOf( pipeline ) );

		}

		boolean isPipeline() { return ! pipeline.isEmpty(); }

	}

	private <T> Mono<T> executeWithSession(
		MongoExecutionContext executionContext, Function<ClientSession, ? extends Publisher<T>> withSession, Supplier<? extends Publisher<T>> withoutSession
	) {

		return Mono
			.deferContextual( context -> {
				SessionBinding binding = context.getOrDefault( CLIENT_SESSION_CONTEXT_KEY, null );
				return binding != null && binding.sessionScope() == executionContext.getSessionScope()
					? Mono.from( withSession.apply( binding.session() ) )
					: Mono.from( withoutSession.get() );

			} );

	}

	private <T> Flux<T> executeFluxWithSession(
		MongoExecutionContext executionContext, Function<ClientSession, ? extends Publisher<T>> withSession, Supplier<? extends Publisher<T>> withoutSession
	) {

		return Flux
			.deferContextual( context -> {
				SessionBinding binding = context.getOrDefault( CLIENT_SESSION_CONTEXT_KEY, null );
				return binding != null && binding.sessionScope() == executionContext.getSessionScope()
					? Flux.from( withSession.apply( binding.session() ) )
					: Flux.from( withoutSession.get() );

			} );

	}

	private String resolveCollectionName(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName
	) {

		return explicitCollectionName != null && ! explicitCollectionName.isBlank()
			? explicitCollectionName
			: executionContext.getCollectionName( entityClass );

	}

	private Mono<MongoCollection<Document>> resolveCollection(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName
	) {

		return executionContext
			.getDatabase()
			.map( database -> database.getCollection( resolveCollectionName( executionContext, entityClass, explicitCollectionName ) ) );

	}

	private FindPublisher<Document> applyQuery(
		MongoCollection<Document> collection, FindSpec query, ClientSession session
	) {

		MongoCollection<Document> target = query.readPreference == null
			? collection
			: collection.withReadPreference( query.readPreference );
		FindPublisher<Document> publisher = session == null
			? target.find( query.filter )
			: target.find( session, query.filter );

		if (query.sort != null)
			publisher = publisher.sort( query.sort );
		if (query.projection != null)
			publisher = publisher.projection( query.projection );
		if (query.skip > 0)
			publisher = publisher.skip( Math.toIntExact( query.skip ) );
		if (query.limit > 0)
			publisher = publisher.limit( query.limit );
		if (query.allowDiskUse != null)
			publisher = publisher.allowDiskUse( query.allowDiskUse );
		query.customizer.accept( publisher );
		return publisher;

	}

	private Flux<Document> findDocuments(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMapMany(
				collection -> executeFluxWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ),
					() -> applyQuery( collection, query, null )
				)
			);

	}

	private <T> Flux<T> find(
		MongoExecutionContext executionContext, Class<T> entityClass, String explicitCollectionName, FindSpec query
	) {

		return findDocuments( executionContext, entityClass, explicitCollectionName, query )
			.map( document -> executionContext.read( entityClass, document ) );

	}

	private <T> Mono<T> findOne(
		MongoExecutionContext executionContext, Class<T> entityClass, String explicitCollectionName, FindSpec query
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).first(),
					() -> applyQuery( collection, query, null ).first()
				)
			)
			.map( document -> executionContext.read( entityClass, document ) );

	}

	private AggregatePublisher<Document> applyAggregation(
		MongoCollection<Document> collection, AggregationSpec aggregation, ClientSession session
	) {

		MongoCollection<Document> target = aggregation.readPreference == null
			? collection
			: collection.withReadPreference( aggregation.readPreference );
		AggregatePublisher<Document> publisher = session == null
			? target.aggregate( aggregation.pipeline )
			: target.aggregate( session, aggregation.pipeline );
		if (aggregation.allowDiskUse != null)
			publisher = publisher.allowDiskUse( aggregation.allowDiskUse );
		aggregation.customizer.accept( publisher );
		return publisher;

	}

	private Document previewFind(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, String operation, FindSpec query
	) {

		Document preview = new Document( "operation", operation )
			.append( "collection", resolveCollectionName( executionContext, entityClass, explicitCollectionName ) )
			.append( "filter", MongoBsonSupport.toDocument( query.filter ) );

		if (query.sort != null)
			preview.append( "sort", MongoBsonSupport.toDocument( query.sort ) );
		if (query.projection != null)
			preview.append( "projection", MongoBsonSupport.toDocument( query.projection ) );
		if (query.skip > 0)
			preview.append( "skip", query.skip );
		if (query.limit > 0)
			preview.append( "limit", query.limit );
		if (query.readPreference != null)
			preview.append( "readPreference", query.readPreference.toString() );
		if (query.allowDiskUse != null)
			preview.append( "allowDiskUse", query.allowDiskUse );

		return preview;

	}

	private Document previewCount(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		Document preview = new Document( "operation", "count" )
			.append( "collection", resolveCollectionName( executionContext, entityClass, explicitCollectionName ) )
			.append( "filter", MongoBsonSupport.toDocument( query.filter ) );

		if (query.skip > 0)
			preview.append( "skip", query.skip );
		if (query.limit > 0)
			preview.append( "limit", query.limit );
		if (query.readPreference != null)
			preview.append( "readPreference", query.readPreference.toString() );

		return preview;

	}

	private Document previewAggregation(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, AggregationSpec aggregation
	) {

		Document preview = new Document( "operation", "aggregate" )
			.append( "collection", resolveCollectionName( executionContext, entityClass, explicitCollectionName ) )
			.append( "pipeline", MongoBsonSupport.toDocuments( aggregation.pipeline ) );

		if (aggregation.readPreference != null)
			preview.append( "readPreference", aggregation.readPreference.toString() );
		if (aggregation.allowDiskUse != null)
			preview.append( "allowDiskUse", aggregation.allowDiskUse );

		return preview;

	}

	private Mono<Document> explainFind(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).explain(),
					() -> applyQuery( collection, query, null ).explain()
				)
			);

	}

	private Mono<Document> explainFind(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query, ExplainVerbosity verbosity
	) {

		Objects.requireNonNull( verbosity, "verbosity" );
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).explain( verbosity ),
					() -> applyQuery( collection, query, null ).explain( verbosity )
				)
			);

	}

	private Mono<Document> explainFindFirst(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).limit( -1 ).explain(),
					() -> applyQuery( collection, query, null ).limit( -1 ).explain()
				)
			);

	}

	private Mono<Document> explainFindFirst(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query, ExplainVerbosity verbosity
	) {

		Objects.requireNonNull( verbosity, "verbosity" );
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).limit( -1 ).explain( verbosity ),
					() -> applyQuery( collection, query, null ).limit( -1 ).explain( verbosity )
				)
			);

	}

	private Mono<Document> explainAggregation(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, AggregationSpec aggregation
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyAggregation( collection, aggregation, session ).explain(),
					() -> applyAggregation( collection, aggregation, null ).explain()
				)
			);

	}

	private Mono<Document> explainAggregation(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, AggregationSpec aggregation, ExplainVerbosity verbosity
	) {

		Objects.requireNonNull( verbosity, "verbosity" );
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyAggregation( collection, aggregation, session ).explain( verbosity ),
					() -> applyAggregation( collection, aggregation, null ).explain( verbosity )
				)
			);

	}

	private <T> Flux<T> distinct(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, String field, Bson filter, Class<T> resultClass
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMapMany(
				collection -> executeFluxWithSession(
					executionContext,
					session -> collection.distinct( session, field, filter, resultClass ),
					() -> collection.distinct( field, filter, resultClass )
				)
			);

	}

	private Flux<Document> aggregateDocuments(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, AggregationSpec aggregation
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMapMany(
				collection -> executeFluxWithSession(
					executionContext,
					session -> applyAggregation( collection, aggregation, session ),
					() -> applyAggregation( collection, aggregation, null )
				)
			);

	}

	private <T> Flux<T> aggregate(
		MongoExecutionContext executionContext, Class<?> sourceClass, String explicitCollectionName, AggregationSpec aggregation, Class<T> targetClass
	) {

		return aggregateDocuments( executionContext, sourceClass, explicitCollectionName, aggregation )
			.map( document -> executionContext.read( targetClass, document ) );

	}

	private <T> Mono<T> preparePersistEntity(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, T entity
	) {

		return executionContext.beforePersist( entity, resolveCollectionName( executionContext, entityClass, explicitCollectionName ) );

	}

	private <T> Mono<T> saveEntity(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, T entity
	) {

		String collectionName = resolveCollectionName( executionContext, entityClass, explicitCollectionName );
		return preparePersistEntity( executionContext, entityClass, collectionName, entity ).flatMap( preparedEntity -> {
			Document document = executionContext.write( preparedEntity );
			Object id = executionContext.getId( preparedEntity );
			return resolveCollection( executionContext, entityClass, collectionName ).flatMap( collection -> {

				if (id == null) {
					return executeWithSession(
						executionContext,
						session -> collection.insertOne( session, document ),
						() -> collection.insertOne( document )
					)
						.doOnSuccess( ignored -> {
							if (document.get( "_id" ) != null)
								executionContext.setId( preparedEntity, document.get( "_id" ) );

						} )
						.then( Mono.defer( () -> executionContext.afterPersist( preparedEntity, document, collectionName ) ) );

				}

				return executeWithSession(
					executionContext,
					session -> collection.replaceOne( session, new Document( "_id", id ), document, new ReplaceOptions().upsert( true ) ),
					() -> collection.replaceOne( new Document( "_id", id ), document, new ReplaceOptions().upsert( true ) )
				).then( Mono.defer( () -> executionContext.afterPersist( preparedEntity, document, collectionName ) ) );

			} );

		} );

	}

	private <T> Flux<T> insertEntities(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, List<T> entities
	) {

		if (entities.isEmpty())
			return Flux.empty();
		List<Document> documents = entities.stream().map( executionContext::write ).toList();
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> collection.insertMany( session, documents ),
					() -> collection.insertMany( documents )
				)
			)
			.doOnSuccess( ignored -> {

				for (int i = 0; i < entities.size(); i++) {
					Object generatedId = documents.get( i ).get( "_id" );
					if (generatedId != null && executionContext.getId( entities.get( i ) ) == null)
						executionContext.setId( entities.get( i ), generatedId );

				}

			} )
			.thenMany( Flux.fromIterable( entities ) );

	}

	private Mono<Void> insertDocuments(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, List<Document> documents
	) {

		if (documents.isEmpty())
			return Mono.empty();
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> collection.insertMany( session, documents ),
					() -> collection.insertMany( documents )
				)
			)
			.then();

	}

	private Mono<BulkWriteResult> bulkWrite(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, List<? extends WriteModel<Document>> writes
	) {

		if (writes.isEmpty())
			return Mono.empty();
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> collection.bulkWrite( session, writes, new BulkWriteOptions().ordered( false ) ),
					() -> collection.bulkWrite( writes, new BulkWriteOptions().ordered( false ) )
				)
			);

	}

	private Mono<DeleteResult> deleteByFilter(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, Bson filter, boolean many
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> many ? collection.deleteMany( session, filter ) : collection.deleteOne( session, filter ),
					() -> many ? collection.deleteMany( filter ) : collection.deleteOne( filter )
				)
			);

	}

	private Mono<Long> count(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName ).flatMap( collection -> {
			MongoCollection<Document> target = query.readPreference == null
				? collection
				: collection.withReadPreference( query.readPreference );
			CountOptions options = new CountOptions();
			if (query.skip > 0)
				options.skip( Math.toIntExact( query.skip ) );
			if (query.limit > 0)
				options.limit( query.limit );
			return executeWithSession(
				executionContext,
				session -> target.countDocuments( session, query.filter, options ),
				() -> target.countDocuments( query.filter, options )
			);

		} );

	}

	private Mono<Boolean> exists(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, FindSpec query
	) {

		query.limit( 1 );
		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> applyQuery( collection, query, session ).first(),
					() -> applyQuery( collection, query, null ).first()
				).hasElement()
			);

	}

	private Mono<UpdateResult> update(
		MongoExecutionContext executionContext, Class<?> entityClass, String explicitCollectionName, Bson filter, UpdateSpec updateSpec, boolean multi, boolean upsert
	) {

		return resolveCollection( executionContext, entityClass, explicitCollectionName )
			.flatMap(
				collection -> executeWithSession(
					executionContext,
					session -> {
						UpdateOptions options = new UpdateOptions().upsert( upsert );

						if (updateSpec.isPipeline()) {
							return multi
								? collection.updateMany( session, filter, updateSpec.pipeline, options )
								: collection.updateOne( session, filter, updateSpec.pipeline, options );

						}

						return multi
							? collection.updateMany( session, filter, updateSpec.update, options )
							: collection.updateOne( session, filter, updateSpec.update, options );

					},
					() -> {
						UpdateOptions options = new UpdateOptions().upsert( upsert );

						if (updateSpec.isPipeline()) { return multi
							? collection.updateMany( filter, updateSpec.pipeline, options )
							: collection.updateOne( filter, updateSpec.pipeline, options ); }

						return multi
							? collection.updateMany( filter, updateSpec.update, options )
							: collection.updateOne( filter, updateSpec.update, options );

					}
				)
			);

	}


	private Mono<String> cursorNamespaceVersion(
		MongoExecutionContext context, String collectionName
	) {

		return cursorNamespaceCoordinator.version( context, collectionName );

	}

	private void validateCursorPageSize(
		int pageSize
	) {

		if (pageSize <= 0)
			throw new IllegalArgumentException( "cursor pageSize must be > 0" );
		int maxPageSize = cursorAnchorStore.cursorCacheOptions().maxPageSize();
		if (pageSize > maxPageSize)
			throw new IllegalArgumentException( "cursor pageSize " + pageSize + " exceeds configured maxPageSize " + maxPageSize );

	}

	private CursorSkipResolution resolveCursorRelativeSkip(
		int targetPageNumber,
		int anchorPageNumber,
		int pageSize,
		long maxRelativeSkip,
		CursorSkipExceededAction onExceeded
	) {

		long pageDistance = Math.max( 0L, (long) targetPageNumber - anchorPageNumber );
		long relativeSkip = Math.multiplyExact( pageDistance, (long) pageSize );
		if (relativeSkip <= maxRelativeSkip) {
			validateCursorDriverSkip( relativeSkip );
			return new CursorSkipResolution( relativeSkip, false );

		}

		return switch (Objects.requireNonNull( onExceeded, "onExceeded must not be null" )) {
			case FAIL -> throw new CursorSkipLimitExceededException(
				targetPageNumber, anchorPageNumber, pageSize, relativeSkip, maxRelativeSkip
			);
			case RETURN_EMPTY -> new CursorSkipResolution( 0L, true );
			case EXECUTE_ANYWAY -> {
				validateCursorDriverSkip( relativeSkip );
				yield new CursorSkipResolution( relativeSkip, false );

			}
		};

	}

	private void validateCursorDriverSkip(
		long relativeSkip
	) {

		if (relativeSkip > Integer.MAX_VALUE)
			throw new IllegalArgumentException(
				"cursor relative skip " + relativeSkip + " exceeds the MongoDB Java Driver skip limit " + Integer.MAX_VALUE
			);

	}

	private Mono<Optional<CursorTokenState>> resolveCursorToken(
		String queryKey, int pageSize, String token
	) {

		if (token == null || token.isBlank())
			return Mono.just( Optional.empty() );
		if (! CursorPaginationSupport.isTokenId( token ))
			return Mono.error( new IllegalArgumentException( "cursor token format is invalid" ) );
		return cursorAnchorStore.resolveToken( token ).map( optional -> {
			CursorTokenState state = optional.orElseThrow( () -> new IllegalArgumentException( "cursor token is invalid or expired" ) );
			if (! queryKey.equals( state.queryKey() ))
				throw new IllegalArgumentException( "cursor token does not belong to the current namespace/query" );
			if (pageSize != state.pageSize())
				throw new IllegalArgumentException( "cursor pageSize must match the pageSize used when the token was issued" );
			return Optional.of( state );

		} );

	}

	private Mono<String> issueCursorToken(
		String queryKey, int pageSize, Document sortValues
	) {

		String token = CursorPaginationSupport.tokenId( queryKey, pageSize, sortValues );
		CursorTokenState state = new CursorTokenState( queryKey, pageSize, sortValues );
		return cursorAnchorStore
			.putToken( token, state, cursorAnchorStore.cursorCacheOptions().tokenTtl() )
			.thenReturn( token );

	}

	private Mono<String> cursorQueryKey(
		MongoExecutionContext context, String collectionName, String operation, Bson criteria, Document normalizedSort, int pageSize, String projectionFingerprint, String extraFingerprint, Collection<String> additionalDependencies
	) {

		List<String> dependencies = new ArrayList<>();
		dependencies.add( collectionName );
		if (additionalDependencies != null)
			additionalDependencies.stream().filter( Objects::nonNull ).filter( value -> ! value.isBlank() ).forEach( dependencies::add );

		return Flux
			.fromIterable( dependencies.stream().distinct().sorted().toList() )
			.concatMap( dependency -> cursorNamespaceVersion( context, dependency ) )
			.collectList()
			.map(
				versions -> CursorPaginationSupport
					.fingerprint(
						operation,
						versions,
						MongoBsonSupport.toDocument( criteria ),
						normalizedSort,
						pageSize,
						projectionFingerprint,
						extraFingerprint
					)
			);

	}

	private Mono<String> cursorTokenQueryKey(
		MongoExecutionContext context, String collectionName, String operation, Bson criteria, Document normalizedSort, int pageSize, String projectionFingerprint, String extraFingerprint, Collection<String> additionalDependencies
	) {

		List<String> dependencies = new ArrayList<>();
		dependencies.add( collectionName );
		if (additionalDependencies != null)
			additionalDependencies.stream().filter( Objects::nonNull ).filter( value -> ! value.isBlank() ).forEach( dependencies::add );

		return Flux
			.fromIterable( dependencies.stream().distinct().sorted().toList() )
			.concatMap( dependency -> cursorNamespaceCoordinator.identity( context, dependency ) )
			.collectList()
			.map(
				namespaces -> CursorPaginationSupport
					.fingerprint(
						"opaque-token-v1",
						operation,
						namespaces,
						MongoBsonSupport.toDocument( criteria ),
						normalizedSort,
						pageSize,
						projectionFingerprint,
						extraFingerprint
					)
			);

	}

	private String lookupFingerprint(
		LookupSpec spec, Bson rightCriteria, String rightCollection
	) {

		return CursorPaginationSupport
			.fingerprint(
				rightCollection,
				MongoBsonSupport.toDocument( rightCriteria ),
				spec.getAs(),
				spec.getLocalField(),
				spec.getForeignField(),
				spec.getLetDoc(),
				MongoBsonSupport.toDocuments( spec.getPipelineDocs() ),
				MongoBsonSupport.toDocuments( spec.getOuterStages() ),
				spec.isUnwind(),
				spec.isPreserveNullAndEmptyArrays()
			);

	}

	private Set<String> lookupDependencyCollections(
		String rightCollection, LookupSpec spec
	) {

		Set<String> collections = new HashSet<>();
		collections.add( rightCollection );
		for (Document document : MongoBsonSupport.toDocuments( spec.getPipelineDocs() ))
			collectLookupDependencyCollections( document, collections );
		for (Document document : MongoBsonSupport.toDocuments( spec.getOuterStages() ))
			collectLookupDependencyCollections( document, collections );
		return collections;

	}

	private void collectLookupDependencyCollections(
		Object value, Set<String> collections
	) {

		if (value instanceof Document document) {
			Object lookup = document.get( "$lookup" );
			if (lookup instanceof Document lookupDocument && lookupDocument.get( "from" ) instanceof String from)
				collections.add( from );
			Object graphLookup = document.get( "$graphLookup" );
			if (graphLookup instanceof Document graphDocument && graphDocument.get( "from" ) instanceof String from)
				collections.add( from );
			Object unionWith = document.get( "$unionWith" );
			if (unionWith instanceof String collection)
				collections.add( collection );
			if (unionWith instanceof Document unionDocument && unionDocument.get( "coll" ) instanceof String collection)
				collections.add( collection );
			document.values().forEach( nested -> collectLookupDependencyCollections( nested, collections ) );
			return;

		}

		if (value instanceof Collection<?> collection)
			collection.forEach( nested -> collectLookupDependencyCollections( nested, collections ) );

	}



	/**
	 * Logical operators used to combine criteria groups in the field builder.
	 */
	public enum LogicalOperator {
		/** Matches only when all nested criteria are satisfied. */
		AND, //
		/** Matches when at least one nested criterion is satisfied. */
		OR, //
		/** Matches only when none of the nested criteria are satisfied. */
		NOR
	}

	private static class CriteriaGroup {

		LogicalOperator operator;

		List<Bson> criteriaList;

		CriteriaGroup(
						LogicalOperator operator
		) {

			this.operator = operator;
			this.criteriaList = new ArrayList<>();

		}

	}

	/**
	 * Base class for execution-context-specific query builders.
	 * <p>This class provides common persistence operations, criteria entry points,
	 * and transitions to terminal query builders such as find, count, exists,
	 * delete, and atomic update.</p>
	 *
	 * @param <E>
	 *            the target entity or mapped result type
	 * @param <T>
	 *            the concrete builder type
	 */
	public abstract class AbstractQueryBuilder<E, T extends AbstractQueryBuilder<E, T>> {


		protected MongoExecutionContext mongoExecutionContext;

		protected Mono<Class<E>> executeClassMono;

		protected String collectionName;

		protected FieldBuilder<E> fieldBuilder = new FieldBuilder<>( LogicalOperator.AND );

		protected AbstractQueryBuilder<E, T> executeBuilder;


		/** Saves a single entity using the resolved Mongo execution context. */
		public Mono<E> save(
			E e
		) {

			Objects.requireNonNull( e, "entity must not be null" );
			return executeClassMono.flatMap( entityClass -> saveEntity( mongoExecutionContext, entityClass, collectionName, e ) );

		}

		/** Saves a single entity emitted by the given publisher. */
		public Mono<E> save(
			Mono<E> e
		) {

			return e.flatMap( this::save );

		}

		public Flux<E> saveAll(
			Iterable<E> entities
		) {

			return saveAll( Flux.fromIterable( entities ) );

		}

		public Flux<E> saveAll(
			Collection<E> entities
		) {

			return saveAll( Flux.fromIterable( entities ) );

		}

		public Flux<E> saveAll(
			Flux<E> entityFlux
		) {

			return entityFlux.flatMap( this::save );

		}

		public Flux<E> saveAllBulk(
			Iterable<E> entities
		) {

			return saveAllBulk( Flux.fromIterable( entities ) );

		}

		public Flux<E> saveAllBulk(
			Collection<E> entities
		) {

			return saveAllBulk( Flux.fromIterable( entities ) );

		}

		public Flux<E> saveAllBulk(
			Flux<E> entityFlux
		) {

			return entityFlux
				.collectList()
				.flatMapMany(
					entities -> entities.isEmpty()
						? Flux.empty()
						: executeClassMono.flatMapMany( entityClass -> insertEntities( mongoExecutionContext, entityClass, collectionName, entities ) )
				);

		}

		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Iterable<E> entities
		) {

			Objects.requireNonNull( entities, "entities must not be null" );
			List<E> values = new ArrayList<>();
			entities.forEach( values::add );
			return saveAllBulkUpsert( values );

		}

		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Collection<E> entities
		) {

			if (entities == null || entities.isEmpty())
				return Mono.empty();

			return executeClassMono.flatMap( entityClass -> {
				List<WriteModel<Document>> writes = new ArrayList<>();

				for (E entity : entities) {
					Document document = mongoExecutionContext.write( entity );
					Object id = mongoExecutionContext.getId( entity );

					if (id == null) {
						writes.add( new InsertOneModel<>( document ) );
						continue;

					}

					document.remove( "_id" );

					if (! document.isEmpty()) {
						writes
							.add(
								new UpdateOneModel<>(
									new Document( "_id", id ),
									new Document( "$set", document ),
									new UpdateOptions().upsert( true )
								)
							);

					}

				}

				return bulkWrite( mongoExecutionContext, entityClass, collectionName, writes );

			} );

		}

		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Flux<E> entityFlux
		) {

			return entityFlux.collectList().flatMap( this::saveAllBulkUpsert );

		}

		public Mono<BulkWriteResult> saveAllBulkUpsertByKey(
			Flux<E> entityFlux, String... keyFieldName
		) {

			if (entityFlux == null)
				return Mono.error( new IllegalArgumentException( "entityFlux must not be null" ) );
			return entityFlux.collectList().flatMap( entities -> saveAllBulkUpsertByKey( entities, keyFieldName ) );

		}

		public Mono<BulkWriteResult> saveAllBulkUpsertByKey(
			Collection<E> entities, String... keyFieldName
		) {

			if (entities == null || entities.isEmpty())
				return Mono.empty();
			if (keyFieldName == null || keyFieldName.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must not be null/empty" ) );

			String[] keys = Arrays
				.stream( keyFieldName )
				.filter( Objects::nonNull )
				.map( String::trim )
				.filter( value -> ! value.isBlank() )
				.map( MongoFieldNameSupport::toMongoField )
				.toArray( String[]::new );
			if (keys.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must contain at least 1 non-blank field" ) );

			return executeClassMono.flatMap( entityClass -> {
				List<WriteModel<Document>> writes = new ArrayList<>();

				for (E entity : entities) {
					Document document = mongoExecutionContext.write( entity );
					Document keyDocument = new Document();
					boolean missingKey = false;

					for (String key : keys) {
						Object value = readDocumentPath( document, key );

						if (value == null) {
							missingKey = true;
							break;

						}

						keyDocument.append( key, value );

					}

					if (missingKey) {
						writes.add( new InsertOneModel<>( document ) );
						continue;

					}

					document.remove( "_id" );
					for (String key : keys)
						removeDocumentPath( document, key );

					Document updateDocument = new Document( "$setOnInsert", new Document( keyDocument ) );
					if (! document.isEmpty())
						updateDocument.append( "$set", document );

					writes
						.add(
							new UpdateOneModel<>(
								keyDocument,
								updateDocument,
								new UpdateOptions().upsert( true )
							)
						);

				}

				return bulkWrite( mongoExecutionContext, entityClass, collectionName, writes );

			} );

		}


		private String resolveRemoveCollectionName(
			Class<?> clazz
		) {

			return (collectionName != null && ! collectionName.isBlank()
				? collectionName
				: mongoExecutionContext.getCollectionName( clazz )) + "_remove";

		}

		public Mono<BulkWriteResult> deleteBulk(
			Iterable<E> entities
		) {

			return deleteBulk( Flux.fromIterable( entities ), false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Collection<E> entities
		) {

			return deleteBulk( Flux.fromIterable( entities ), false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Flux<E> entityFlux
		) {

			return deleteBulk( entityFlux, false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Iterable<E> entities, boolean isBackup
		) {

			return deleteBulk( Flux.fromIterable( entities ), isBackup );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Collection<E> entities, boolean isBackup
		) {

			return deleteBulk( Flux.fromIterable( entities ), isBackup );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Flux<E> entityFlux, boolean isBackup
		) {

			return entityFlux.collectList().flatMap( entities -> {
				if (entities.isEmpty())
					return Mono.empty();

				Class<?> entityClass = entities.get( 0 ).getClass();
				Mono<Void> backup = isBackup
					? insertDocuments(
						mongoExecutionContext,
						entityClass,
						resolveRemoveCollectionName( entityClass ),
						entities.stream().map( mongoExecutionContext::write ).toList()
					)
					: Mono.empty();
				List<WriteModel<Document>> writes = entities
					.stream()
					.map( mongoExecutionContext::getId )
					.filter( Objects::nonNull )
					.map( id -> (WriteModel<Document>) new DeleteOneModel<Document>( new Document( "_id", id ) ) )
					.toList();

				return backup.then( bulkWrite( mongoExecutionContext, entityClass, collectionName, writes ) );

			} );

		}

		public Mono<DeleteResult> delete(
			E e
		) {

			return delete( e, false );

		}

		public Mono<DeleteResult> delete(
			Mono<E> e
		) {

			return delete( e, false );

		}

		public Mono<DeleteResult> delete(
			E e, boolean isBackup
		) {

			Objects.requireNonNull( e, "entity must not be null" );
			return executeClassMono.flatMap( entityClass -> {
				Object id = mongoExecutionContext.getId( e );
				Document filter = id == null ? mongoExecutionContext.write( e ) : new Document( "_id", id );
				return deleteByFilter( mongoExecutionContext, entityClass, collectionName, filter, false )
					.flatMap(
						result -> ! isBackup
							? Mono.just( result )
							: insertDocuments(
								mongoExecutionContext,
								entityClass,
								resolveRemoveCollectionName( entityClass ),
								List.of( mongoExecutionContext.write( e ) )
							).thenReturn( result )
					);

			} );

		}

		public Mono<DeleteResult> delete(
			Mono<E> eMono, boolean isBackup
		) {

			return eMono.flatMap( entity -> delete( entity, isBackup ) );

		}

		public Mono<Void> createHistory(
			E e
		) {

			return createHistory( e, "history", objectMapper );

		}

		public Mono<Void> createHistory(
			E e, String prefix
		) {

			return createHistory( e, prefix, objectMapper );

		}

		public Mono<Void> createHistory(
			E e, ObjectMapper objectMapper
		) {

			return createHistory( e, "history", objectMapper );

		}

		public Mono<Void> createHistory(
			E e, String prefix, ObjectMapper objectMapper
		) {

			Objects.requireNonNull( e, "entity must not be null" );
			Objects.requireNonNull( objectMapper, "objectMapper must not be null" );
			String suffix = prefix == null || prefix.isBlank()
				? "history"
				: (prefix.charAt( 0 ) == '_' ? prefix.substring( 1 ) : prefix);
			Class<?> entityClass = e.getClass();
			Document snapshot = copyDocument( mongoExecutionContext.write( e ) );
			snapshot.remove( "_id" );
			String sourceCollection = collectionName != null && ! collectionName.isBlank()
				? collectionName
				: mongoExecutionContext.getCollectionName( entityClass );
			return insertDocuments(
				mongoExecutionContext,
				entityClass,
				sourceCollection + "_" + suffix,
				List.of( snapshot )
			);

		}

		public FieldBuilder<E> fields() {

			return fields( LogicalOperator.AND );

		}

		/**
		 * Starts criteria construction with the given root logical operator.
		 *
		 * @param logicalOperator
		 *            the root logical operator
		 * 
		 * @return the field builder for composing criteria
		 */
		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator
		) {

			return createFirstOperator( logicalOperator );

		}

		/**
		 * Starts criteria construction with a root {@link LogicalOperator#AND} group
		 * and immediately adds the given field conditions.
		 *
		 * @param fieldsPairs
		 *            the initial field conditions
		 * 
		 * @return the field builder for composing criteria
		 */
		public FieldBuilder<E> fields(
			FieldsPair<?, ?>... fieldsPairs
		) {

			return fields( LogicalOperator.AND, fieldsPairs );

		}

		/**
		 * Starts criteria construction with a root {@link LogicalOperator#AND} group
		 * and immediately adds the given field conditions.
		 *
		 * @param fieldsPairs
		 *            the initial field conditions
		 * 
		 * @return the field builder for composing criteria
		 */
		public FieldBuilder<E> fields(
			Collection<FieldsPair<?, ?>> fieldsPairs
		) {

			return fields( LogicalOperator.AND, fieldsPairs );

		}

		/**
		 * Starts criteria construction with a MongoDB driver-native filter. Use this
		 * escape hatch when the driver supports a filter that does not need a dedicated
		 * {@link FieldsPair.Condition} convenience mapping.
		 *
		 * @param filter
		 *            the driver-native filter
		 *
		 * @return the field builder for composing additional criteria
		 */
		public FieldBuilder<E> driverFilter(
			Bson filter
		) {

			return createFirstOperator( LogicalOperator.AND ).driverFilter( filter );

		}

		/**
		 * Starts criteria construction with the given root logical operator.
		 *
		 * @param logicalOperator
		 *            the root logical operator
		 * 
		 * @return the field builder for composing criteria
		 */
		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator, FieldsPair<?, ?>... fieldsPairs
		) {

			if (fieldsPairs == null || fieldsPairs.length == 0)
				return createFirstOperator( logicalOperator );
			return createFirstOperator( logicalOperator ).fields( fieldsPairs );

		}

		/**
		 * Starts criteria construction with the given root logical operator.
		 *
		 * @param logicalOperator
		 *            the root logical operator
		 * 
		 * @return the field builder for composing criteria
		 */
		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator, Collection<FieldsPair<?, ?>> fieldsPairs
		) {

			if (fieldsPairs == null || fieldsPairs.isEmpty())
				return createFirstOperator( logicalOperator );
			return createFirstOperator( logicalOperator ).fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );

		}



		private FieldBuilder<E> createFirstOperator(
			LogicalOperator logicalOperator
		) {

			this.fieldBuilder = new FieldBuilder<>( logicalOperator );
			return this.fieldBuilder;

		}

		/**
		 * Builder for aggregation-based grouping queries.
		 * <p>This builder supports group keys, common accumulator operations,
		 * optional lookup joins, and custom key/value conversion.</p>
		 *
		 * @param <KK>
		 *            the grouped key type
		 * @param <V>
		 *            the grouped value type
		 */
		public abstract class Grouping<KK, V> {

			private final List<String> keyFields = new ArrayList<>();

			private final List<BsonField> accumulators = new ArrayList<>();

			private boolean hasAccumulator = false; // 아무것도 지정 안 하면 count 기본

			protected Class<KK> keyType;

			protected Class<V> valueType;

			private Function<Document, KK> keyConverter;

			private Function<Document, V> valueConverter;

			@SuppressWarnings("rawtypes")
			private final QueryBuilderAccesser accessor;

			/**
			 * Starts a grouping query on top of the current criteria and query options.
			 *
			 * @param <KK>
			 *            the grouped key type
			 * @param <V>
			 *            the grouped value type
			 * @param k
			 *            the target key type
			 * @param v
			 *            the target value type
			 * 
			 * @return a grouping builder
			 */
			@SuppressWarnings({
				"unchecked", "rawtypes"
			})
			public Grouping(
							Class<KK> k,
							Class<V> v,
							QueryBuilderAccesser accessor
			) {

				this.keyType = k;
				this.valueType = v;
				this.accessor = Objects.requireNonNull( accessor, "accessor" );
				this.keyConverter = (Document kk) -> {
					Object key = kk.get( "_id" );

					return (KK) key;

				};
				this.valueConverter = (Document vv) -> {

					return mongoExecutionContext.read( this.valueType, vv );

				};

			}

			/**
			 * Sets a custom converter for the aggregation group key document.
			 *
			 * @param fn
			 *            the key converter
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> keyConverter(
				Function<Document, KK> fn
			) {

				if (fn != null) {
					this.keyConverter = fn;

				}

				return this;

			}

			/**
			 * Sets a custom converter for the aggregation result value document.
			 *
			 * @param fn
			 *            the value converter
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> valueConverter(
				Function<Document, V> fn
			) {

				if (fn != null) {
					this.valueConverter = fn;

				}

				return this;

			}

			/**
			 * Defines one or more fields to be used as the group key.
			 *
			 * @param keys
			 *            the group key field names
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> by(
				String... keys
			) {

				if (keys == null || keys.length == 0) { throw new IllegalArgumentException( "group by keys must not be empty." ); }

				for (String k : keys) {
					if (k == null || k.isBlank())
						continue;
					keyFields.add( MongoFieldNameSupport.toMongoField( k ) );

				}

				if (keyFields.isEmpty()) { throw new IllegalArgumentException( "valid group by key required." ); }

				return this;

			}

			/**
			 * Adds a count accumulator using the default alias {@code count}.
			 *
			 * @return this builder
			 */
			public Grouping<KK, V> count() {

				return countAs( "count" );

			}

			/**
			 * Adds a count accumulator using the given alias.
			 *
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> countAs(
				String as
			) {

				return accumulator( Accumulators.sum( as, 1 ) );

			}

			/**
			 * Adds a {@code $sum} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> sum(
				String field, String as
			) {

				return accumulator( Accumulators.sum( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds an {@code $avg} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> avg(
				String field, String as
			) {

				return accumulator( Accumulators.avg( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds a {@code $min} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> min(
				String field, String as
			) {

				return accumulator( Accumulators.min( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds a {@code $max} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> max(
				String field, String as
			) {

				return accumulator( Accumulators.max( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds an {@code $addToSet} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> addToSet(
				String field, String as
			) {

				return accumulator( Accumulators.addToSet( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds a {@code $push} accumulator for the given field.
			 *
			 * @param field
			 *            the source field
			 * @param as
			 *            the accumulator alias
			 * 
			 * @return this builder
			 */
			public Grouping<KK, V> push(
				String field, String as
			) {

				return accumulator( Accumulators.push( as, "$" + MongoFieldNameSupport.toMongoField( field ) ) );

			}

			/**
			 * Adds a MongoDB driver-native accumulator. This is the extension point for
			 * accumulator operators that do not need a dedicated DSL convenience method.
			 *
			 * @param accumulator
			 *            the driver-native accumulator field
			 *
			 * @return this builder
			 */
			public Grouping<KK, V> accumulator(
				BsonField accumulator
			) {

				this.accumulators.add( Objects.requireNonNull( accumulator, "accumulator" ) );
				this.hasAccumulator = true;
				return this;

			}

			/**
			 * Executes the grouping query without a lookup join.
			 *
			 * @return a {@link Mono} emitting the grouped result map
			 */
			public Mono<Map<KK, V>> execute() {

				return buildAndRun( null );

			}

			/**
			 * Executes the grouping query with a lookup join.
			 *
			 * @param rightBuilder
			 *            the right-side query builder used for the join target
			 * @param spec
			 *            the lookup specification
			 * @param <R2>
			 *            the right-side mapped type
			 * 
			 * @return a {@link Mono} emitting the grouped result map
			 */
			public <R2> Mono<Map<KK, V>> executeLookup(
				ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Objects.requireNonNull( rightBuilder, "rightBuilder is required" );
				Objects.requireNonNull( spec, "LookupSpec is required" );
				return buildAndRun( new LookupCtx<>( rightBuilder, spec ) );

			}

			// 내부: 파이프라인 구성/실행
			private <R2> Mono<Map<KK, V>> buildAndRun(
				LookupCtx<R2> lookup
			) {

				if (keyFields.isEmpty())
					throw new IllegalStateException( "group by keys are not specified." );
				if (! hasAccumulator)
					count();

				Mono<Class<E>> leftClassMono = executeClassMono;

				return Mono
					.zip( fieldBuilder.buildCriteria(), leftClassMono )
					.flatMap( tuple -> {
						Optional<Bson> leftMatch = tuple.getT1();
						Class<E> leftClass = tuple.getT2();

						String leftColl = (collectionName != null && ! collectionName.isBlank())
							? collectionName
							: mongoExecutionContext.getCollectionName( leftClass );

						List<Bson> ops = new ArrayList<>();
						leftMatch.ifPresent( c -> ops.add( Aggregates.match( c ) ) );

						Mono<List<Bson>> opsMono = (lookup == null)
							? Mono.just( ops )
							: Mono.zip( lookup.rightClass(), lookup.rightBuilder.getFieldBuilderCriteria() ).map( rightTuple -> {
								Class<R2> rightClass = rightTuple.getT1();
								String rightColl = (lookup.rightCollectionName() != null && ! lookup.rightCollectionName().isBlank())
									? lookup.rightCollectionName()
									: lookup.rightBuilder.getMongoExecutionContext().getCollectionName( rightClass );
								String rightAs = lookup.spec.getAs() != null && ! lookup.spec.getAs().isBlank()
									? lookup.spec.getAs()
									: rightClass.getSimpleName();
								appendLookupStages( ops, rightColl, rightAs, rightTuple.getT2(), lookup.spec );
								return ops;

							} );

						return opsMono.flatMap( opList -> {
							Object groupId = (keyFields.size() == 1)
								? "$" + keyFields.get( 0 )
								: new Document().append( keyFields.get( 0 ), "$" + keyFields.get( 0 ) ); // 아래에서 제대로 채움

							if (keyFields.size() > 1) {
								Document gid = new Document();
								for (String k : keyFields)
									gid.append( k, "$" + k );
								groupId = gid;

							}

							opList.add( Aggregates.group( groupId, this.accumulators ) );

							AggregationSpec aggregation = accessor.applyAggOptions( opList );

							Flux<Document> flux = aggregateDocuments( mongoExecutionContext, leftClass, leftColl, aggregation );

							return flux.collect( LinkedHashMap::new, (LinkedHashMap<KK, V> map, Document d) -> {
								KK key = this.keyConverter.apply( d );
								Document vd = new Document( d );
								vd.remove( "_id" );
								V v = this.valueConverter.apply( vd );
								map.put( key, v );

							} );

						} );

					} );

			}

			// $lookup 컨텍스트 Helper
			private class LookupCtx<R2> {

				final ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder;

				final LookupSpec spec;

				LookupCtx(
							ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rb,
							LookupSpec sp
				) {

					this.rightBuilder = rb;
					this.spec = sp;

				}

				Mono<Class<R2>> rightClass() {

					return rightBuilder.getExecuteClassMono();

				}

				String rightCollectionName() {

					return rightBuilder.getCollectionName();

				}

			}

		}




		public interface ExecuteBuilder {

		}


		protected abstract class QueryBuilderAccesser<Q, A> {

			protected ReadPreference readPreference = null;

			protected Boolean isAllowDiskUse = null;

			protected Consumer<FindPublisher<Document>> queryCustomizer = ignored -> {};

			protected Consumer<AggregatePublisher<Document>> aggregationCustomizer = ignored -> {};

			protected boolean queryCustomized;

			protected boolean aggregationCustomized;

			public interface Runner {}

			/** Applies a MongoDB driver FindPublisher customization directly. */
			@SuppressWarnings("unchecked")
			public final Q customizeQuery(
				Consumer<FindPublisher<Document>> customizer
			) {

				if (customizer != null) {
					this.queryCustomizer = this.queryCustomizer.andThen( customizer );
					this.queryCustomized = true;

				}

				return (Q) this;

			}

			/** Applies a MongoDB driver AggregatePublisher customization directly. */
			@SuppressWarnings("unchecked")
			public final A customizeAggregation(
				Consumer<AggregatePublisher<Document>> customizer
			) {

				if (customizer != null) {
					this.aggregationCustomizer = this.aggregationCustomizer.andThen( customizer );
					this.aggregationCustomized = true;

				}

				return (A) this;

			}

			public QueryBuilderAccesser<Q, A> readPreference(
				ReadPreference rp
			) {

				this.readPreference = rp;
				return this;

			}

			public QueryBuilderAccesser<Q, A> isAllowDiskUse(
				Boolean allow
			) {

				this.isAllowDiskUse = allow;
				return this;

			}

			protected AggregationSpec applyAggOptions(
				List<? extends Bson> pipeline
			) {

				return new AggregationSpec( pipeline )
					.readPreference( readPreference )
					.allowDiskUse( isAllowDiskUse )
					.customize( aggregationCustomizer );

			}

			protected FindSpec applyQueryOptions(
				FindSpec query
			) {

				return query
					.readPreference( readPreference )
					.allowDiskUse( isAllowDiskUse )
					.customize( queryCustomizer );

			}

			public <KK, V> Grouping<KK, V> group(
				Class<KK> k, Class<V> v
			) {

				return new Grouping<KK, V>( k, v, this ) {};

			}

			protected String resolveCollectionName(
				Class<?> clazz
			) {

				return mongoExecutionContext.getCollectionName( clazz );

			}

			protected String simpleName(
				Class<?> clazz
			) {

				return clazz.getSimpleName();

			}


			protected MongoExecutionContext getMongoExecutionContext() { return AbstractQueryBuilder.this.mongoExecutionContext; }

			protected Mono<Class<E>> getExecuteClassMono() { return executeClassMono; }

			protected String getCollectionName() { return collectionName; }

			protected Mono<Optional<Bson>> getFieldBuilderCriteria() { return fieldBuilder.buildCriteria(); }


			public interface FindAllExecute<E> extends Runner {

				Flux<E> execute();

				Mono<Document> preview();

				Mono<Document> explain();

				Mono<Document> explain(
					ExplainVerbosity verbosity
				);

				/**
				 * Executes the current query as a reactive page.
				 * <p>The data part remains a {@link Flux}; use {@code data()} for streaming
				 * processing and {@code totalCount()} only when the total count is needed.</p>
				 *
				 * @return a reactive page wrapper containing streamed data and total count
				 */
				PageStream<E> executePageStream();

			}

			public interface FindAllAggregation<E> extends Runner {

				/**
				 * Executes the current query as an aggregation pipeline and emits mapped
				 * entities directly. This method is intended for batch/streaming workloads.
				 *
				 * @return a {@link Flux} emitting aggregation results one by one
				 */
				Flux<E> executeAggregationStream();

				/**
				 * Executes the current aggregation as a reactive page. The page data remains
				 * a {@link Flux}; total count is exposed as a separate {@link Mono}.
				 *
				 * @return a reactive page wrapper for aggregation results
				 */
				PageStream<E> executeAggregationPageStream();

				Mono<PageResult<E>> executeAggregation();

				<R2> Flux<ResultTuple<E, List<R2>>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);

				<R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupAndCount(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);

			}

			public interface FindExecute<E> extends Runner {

				Mono<E> execute();

				Mono<E> executeFirst();

				Mono<Document> preview();

				Mono<Document> explain();

				Mono<Document> explain(
					ExplainVerbosity verbosity
				);

			}

			public interface FindAggregation<E> extends Runner {

				Mono<E> executeAggregation();

				<R2> Mono<ResultTuple<E, R2>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

			public interface CountExecute<E> extends Runner {

				Mono<Long> execute();

				Mono<Document> preview();


			}

			public interface CountAggregation<E> extends Runner {

				Mono<Long> executeAggregation();

				<R2> Mono<ResultTuple<Long, Long>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

			public interface ExistsExecute<E> extends Runner {

				Mono<Boolean> execute();

				Mono<Document> preview();

				Mono<Document> explain();

				Mono<Document> explain(
					ExplainVerbosity verbosity
				);


			}

			public interface ExistsAggregation<E> extends Runner {

				Mono<Boolean> executeAggregation();

				<R2> Mono<ResultTuple<Boolean, Boolean>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

		}

		/**
		 * Builder for composing nested criteria groups using AND, OR, and NOR-based negation.
		 *
		 * @param <S>
		 *            the current entity type
		 */
		public class FieldBuilder<S extends E> {

			private Deque<CriteriaGroup> criteriaStack = new ArrayDeque<>();

			/* public FieldBuilder() {
			 * 
			 * // 기본적으로 AND 그룹으로 시작
			 * criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );
			 * 
			 * } */

			public FieldBuilder() {

				this( LogicalOperator.AND );

			}

			public FieldBuilder(
								LogicalOperator rootOperator
			) {

				LogicalOperator op = (rootOperator == null) ? LogicalOperator.AND : rootOperator;
				// ✅ fields(LogicalOperator.xxx)로 시작할 때 루트 그룹에 반영
				criteriaStack.push( new CriteriaGroup( op ) );

			}

			/**
			 * Adds the given field conditions to the current criteria group.
			 *
			 * @param fieldsPairs
			 *            the field conditions to add
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> fields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				if (fieldsPairs != null && fieldsPairs.length > 0) {

					for (FieldsPair<?, ?> pair : fieldsPairs) {

						if (pair != null) {
							Bson criteria = FieldsPairBsonSupport.createSingleCriteria( pair );

							if (criteria != null) {
								criteriaStack.peek().criteriaList.add( criteria );

							}

						}

					}

				}

				return this;

			}

			/**
			 * Adds a MongoDB driver-native filter to the current logical group.
			 *
			 * @param filter
			 *            the driver-native filter
			 *
			 * @return this builder
			 */
			public FieldBuilder<S> driverFilter(
				Bson filter
			) {

				if (filter != null) {
					criteriaStack.peek().criteriaList.add( filter );

				}

				return this;

			}

			/**
			 * Creates a nested AND group and appends it to the current criteria tree.
			 *
			 * @param block
			 *            the nested criteria block
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> and(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				} // 자동 닫기

				return this;

			}

			/**
			 * Creates a nested OR group and appends it to the current criteria tree.
			 *
			 * @param block
			 *            the nested criteria block
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> or(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.OR ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				} // 자동 닫기

				return this;

			}

			/**
			 * Creates a negated AND group by wrapping the nested conditions in a NOR expression.
			 *
			 * @param block
			 *            the nested criteria block
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> not(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );

				try {
					and( block );

				} finally {
					endOperator();

				}

				return this;

			}

			/**
			 * Creates a negated OR-style group that matches when none of the nested conditions are satisfied.
			 *
			 * @param block
			 *            the nested criteria block
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> notAny(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				}

				return this;

			}

			/**
			 * Alias for {@link #not(Consumer)}.
			 *
			 * @param block
			 *            the nested criteria block
			 * 
			 * @return this builder
			 */
			public FieldBuilder<S> notAll(
				Consumer<FieldBuilder<S>> block
			) {

				return not( block );

			}

			// public FieldBuilder<S> and() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );
			// return this;
			//
			// }
			//
			// public FieldBuilder<S> or() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.OR ) );
			// return this;
			//
			// }
			//
			// public FieldBuilder<S> nor() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );
			// return this;
			//
			// }

			// 현재 그룹 종료 및 상위 그룹에 추가
			private FieldBuilder<S> endOperator() {

				if (criteriaStack.size() <= 1) { return this; }

				CriteriaGroup finishedGroup = criteriaStack.pop();
				List<Bson> validCriteria = finishedGroup.criteriaList
					.stream()
					.filter( Objects::nonNull )
					.collect( Collectors.toList() );

				if (! validCriteria.isEmpty()) {
					Bson groupCriteria;

					switch (finishedGroup.operator) {
						case AND:
							groupCriteria = FieldsPairBsonSupport.combine( validCriteria, "AND" );
							break;
						case OR:
							groupCriteria = FieldsPairBsonSupport.combine( validCriteria, "OR" );
							break;
						case NOR:
							groupCriteria = FieldsPairBsonSupport.combine( validCriteria, "NOR" );
							break;
						default:
							throw new IllegalArgumentException( "Unsupported operator: " + finishedGroup.operator );

					}

					// 상위 그룹에 추가
					criteriaStack.peek().criteriaList.add( groupCriteria );

				}

				return this;

			}

			/**
			 * Finalizes the current criteria tree and returns a factory for terminal query builders.
			 *
			 * @return the terminal query builder factory
			 */
			public AbstractQueryBuilder<E, T>.QueryBuilderFactory end() {

				while (criteriaStack.size() > 1) {
					endOperator();

				}

				return new QueryBuilderFactory();

			}

			private Mono<Optional<Bson>> buildCriteria() {

				Mono<Optional<Bson>> resultMono = Mono.fromCallable( () -> {
					List<Bson> allCriteria = new ArrayList<>();
					Deque<CriteriaGroup> tempStack = new ArrayDeque<>( criteriaStack );

					while (! tempStack.isEmpty()) {
						CriteriaGroup group = tempStack.pop();

						if (! group.criteriaList.isEmpty()) {
							Bson combined = null;

							switch (group.operator) {
								case AND:
									combined = FieldsPairBsonSupport.combine( group.criteriaList, "AND" );
									break;
								case OR:
									combined = FieldsPairBsonSupport.combine( group.criteriaList, "OR" );
									break;
								case NOR:
									combined = FieldsPairBsonSupport.combine( group.criteriaList, "NOR" );
									break;

							}

							if (combined != null) {
								allCriteria.add( combined );

							}

						}

					}

					if (allCriteria.isEmpty()) { return Optional.empty(); }

					if (allCriteria.size() == 1) { return Optional.of( allCriteria.get( 0 ) ); }

					return Optional.of( FieldsPairBsonSupport.combine( allCriteria, "AND" ) );

				} );
				return resultMono;
				// .onErrorMap( e -> new RuntimeException( "Failed to build MongoDB filter: " + e.getMessage(), e )
				// );


			}

		}

		/**
		 * Factory for creating terminal query builders after criteria composition has been completed.
		 */
		public class QueryBuilderFactory {

			/**
			 * Creates a query builder for multi-result reads.
			 *
			 * @return a multi-result query builder
			 */
			public FindAllQueryBuilder<E> findAll() {

				return new FindAllQueryBuilder<E>();

			}

			/**
			 * Creates a query builder for single-result reads.
			 *
			 * @return a single-result query builder
			 */
			public FindQueryBuilder<E> find() {

				return new FindQueryBuilder<E>();

			}

			/**
			 * Creates a query builder for count operations.
			 *
			 * @return a count query builder
			 */
			public CountQueryBuilder count() {

				return new CountQueryBuilder();

			}

			/**
			 * Creates a query builder for distinct values of the given field.
			 *
			 * @param <R>
			 *            the distinct result type
			 * @param field
			 *            the field name or enum-backed field identifier
			 * @param resultClass
			 *            the Driver decode target type
			 *
			 * @return a distinct query builder
			 */
			public <R> DistinctQueryBuilder<R> distinct(
				Object field, Class<R> resultClass
			) {

				return new DistinctQueryBuilder<>( field, resultClass );

			}

			/**
			 * Creates a query builder for criteria-based delete operations.
			 *
			 * @return a delete query builder
			 */
			public DeleteQueryBuilder delete() {

				return new DeleteQueryBuilder();

			}

			/**
			 * Creates a query builder for existence checks.
			 *
			 * @return an exists query builder
			 */
			public ExistsQueryBuilder exists() {

				return new ExistsQueryBuilder();

			}

			/**
			 * Creates a query builder for atomic update operations.
			 *
			 * @return an atomic update query builder
			 */
			public AtomicUpdateQueryBuilder atomicUpdate() {

				return new AtomicUpdateQueryBuilder();

			}


		}


		/**
		 * Starts an Atlas Search query using the default Atlas Search index.
		 * <p>This entry point intentionally bypasses the regular {@link FieldBuilder}
		 * terminal flow because Atlas Search requires {@code $search} or
		 * {@code $searchMeta} to be the first stage in the aggregation pipeline.</p>
		 *
		 * @return an Atlas Search builder
		 */
		public SearchBuilder<E> search() {

			return new SearchBuilder<>( null );

		}

		/**
		 * Starts an Atlas Search query using the specified Atlas Search index.
		 * <p>This entry point intentionally bypasses the regular {@link FieldBuilder}
		 * terminal flow because Atlas Search requires {@code $search} or
		 * {@code $searchMeta} to be the first stage in the aggregation pipeline.</p>
		 *
		 * @param index
		 *            the Atlas Search index name; when blank, Atlas Search falls back to
		 *            its default index selection behavior
		 *
		 * @return an Atlas Search builder
		 */
		public SearchBuilder<E> search(
			String index
		) {

			return new SearchBuilder<>( index );

		}

		/**
		 * Starts a MongoDB {@code $vectorSearch} query using the specified vector index.
		 * <p>This entry point intentionally bypasses the regular {@link FieldBuilder}
		 * terminal flow because {@code $vectorSearch} must be the first stage in the
		 * aggregation pipeline.</p>
		 *
		 * @param index
		 *            the MongoDB Vector Search index name
		 *
		 * @return a vector-search builder
		 */
		public VectorSearchBuilder<E> vectorSearch(
			String index
		) {

			return new VectorSearchBuilder<>( index );

		}

		/**
		 * Starts a driver-native root aggregation pipeline.
		 * <p>This entry point accepts MongoDB Driver {@link Bson} stages directly instead of
		 * re-implementing Driver aggregation operators in the DSL. It is intended for
		 * aggregation stages that need full pipeline-position control, including newly
		 * introduced Driver features.</p>
		 *
		 * @return a root aggregation builder
		 */
		public AggregationBuilder aggregation() {

			return new AggregationBuilder();

		}

		/**
		 * Root aggregation builder backed by MongoDB Driver {@link Bson} stages.
		 * <p>Stages are executed exactly in insertion order. MongoDB stage ordering and
		 * server-version constraints remain the caller's responsibility.</p>
		 */
		public class AggregationBuilder {

			private final List<Bson> stages = new ArrayList<>();

			private ReadPreference readPreference;

			private Boolean isAllowDiskUse;

			private Consumer<AggregatePublisher<Document>> aggregationCustomizer = ignored -> {};

			AggregationBuilder() {}

			public AggregationBuilder readPreference(
				ReadPreference rp
			) {

				this.readPreference = rp;
				return this;

			}

			public AggregationBuilder isAllowDiskUse(
				Boolean allow
			) {

				this.isAllowDiskUse = allow;
				return this;

			}

			/** Applies a MongoDB Driver AggregatePublisher customization directly. */
			public AggregationBuilder customizeAggregation(
				Consumer<AggregatePublisher<Document>> customizer
			) {

				if (customizer != null)
					this.aggregationCustomizer = this.aggregationCustomizer.andThen( customizer );
				return this;

			}

			/** Adds a MongoDB Driver aggregation stage at the end of this pipeline. */
			public AggregationBuilder stage(
				Bson stage
			) {

				this.stages.add( Objects.requireNonNull( stage, "stage" ) );
				return this;

			}

			/** Adds MongoDB Driver aggregation stages in the given order. */
			public AggregationBuilder stages(
				Bson... stages
			) {

				return stages( Arrays.asList( Objects.requireNonNull( stages, "stages" ) ) );

			}

			/** Adds MongoDB Driver aggregation stages in collection iteration order. */
			public AggregationBuilder stages(
				Collection<? extends Bson> stages
			) {

				Objects.requireNonNull( stages, "stages" ).forEach( this::stage );
				return this;

			}

			private AggregationSpec buildAggregation() {

				return new AggregationSpec( this.stages )
					.readPreference( this.readPreference )
					.allowDiskUse( this.isAllowDiskUse )
					.customize( this.aggregationCustomizer );

			}

			/** Executes the pipeline and maps results to the source entity type. */
			public Flux<E> execute() {

				return executeClassMono
					.flatMapMany(
						entityClass -> ReactiveMongoDsl.this
							.aggregate( mongoExecutionContext, entityClass, collectionName, buildAggregation(), entityClass )
					);

			}

			/** Executes the pipeline and maps results to the requested result type. */
			public <R> Flux<R> execute(
				Class<R> resultClass
			) {

				Objects.requireNonNull( resultClass, "resultClass" );
				return executeClassMono
					.flatMapMany(
						entityClass -> ReactiveMongoDsl.this
							.aggregate( mongoExecutionContext, entityClass, collectionName, buildAggregation(), resultClass )
					);

			}

			/** Executes the pipeline and returns raw MongoDB {@link Document} results. */
			public Flux<Document> executeDocument() {

				return executeClassMono
					.flatMapMany(
						entityClass -> ReactiveMongoDsl.this
							.aggregateDocuments( mongoExecutionContext, entityClass, collectionName, buildAggregation() )
					);

			}

			/** Renders the current pipeline without executing it. */
			public Mono<Document> preview() {

				return executeClassMono
					.map( entityClass -> previewAggregation( mongoExecutionContext, entityClass, collectionName, buildAggregation() ) );

			}

			/** Executes MongoDB explain for the current pipeline. */
			public Mono<Document> explain() {

				return executeClassMono
					.flatMap( entityClass -> explainAggregation( mongoExecutionContext, entityClass, collectionName, buildAggregation() ) );

			}

			/** Executes MongoDB explain with the requested verbosity. */
			public Mono<Document> explain(
				ExplainVerbosity verbosity
			) {

				Objects.requireNonNull( verbosity, "verbosity" );
				return executeClassMono
					.flatMap( entityClass -> explainAggregation( mongoExecutionContext, entityClass, collectionName, buildAggregation(), verbosity ) );

			}

		}

		/**
		 * Atlas Search-specific builder that renders strongly typed Atlas Search operators
		 * into a {@code $search} or {@code $searchMeta} aggregation stage.
		 * <p>This builder extends {@link QueryBuilderAccesser} on purpose so it can reuse
		 * the same aggregation-option pipeline used by the rest of the DSL. This keeps
		 * read preference, disk-use, and aggregation customization semantics aligned with
		 * existing builders such as {@code findAll()}, {@code count()}, and
		 * {@code exists()}.</p>
		 * <p>Regular {@link FieldsPair}-based filtering is still supported through
		 * {@link #fields(FieldsPair[])} and {@link #fields(Collection)}, but those
		 * conditions are applied <strong>after</strong> {@code $search} as a normal
		 * aggregation {@code $match}. Search relevance, score, and index-aware filtering
		 * should therefore be expressed through Atlas Search operators such as
		 * {@code compound.filter(...)} instead of post-search {@code fields(...)}.</p>
		 *
		 * @param <S>
		 *            the current mapped entity type
		 */
		public class SearchBuilder<S extends E> extends QueryBuilderAccesser<SearchBuilder<S>, SearchBuilder<S>> {

			private final String index;

			private final FieldBuilder<E> postFilterBuilder = new FieldBuilder<>( LogicalOperator.AND );

			private AtlasSearchOperator rootOperator;

			private SearchCountType searchCountType;

			private boolean scoreDetails;

			private String searchAfterToken;

			private String searchBeforeToken;

			private final List<Document> addFieldsDocs = new ArrayList<>();

			private Double scoreGte;

			private Double scoreLte;

			private final List<Bson> searchSorts = new ArrayList<>();

			private Integer pageNumber;

			private Integer pageSize;

			private String[] excludes;

			private SearchHighlight highlight;

			private final List<Function<SearchOptions, SearchOptions>> driverOptionCustomizers = new ArrayList<>();

			private final List<Bson> stages = new ArrayList<>();

			SearchBuilder(
							String index
			) {

				this.index = index;

			}

			/**
			 * Returns this builder with the given read preference applied to the generated
			 * aggregation query.
			 *
			 * @param rp
			 *            the read preference
			 *
			 * @return this builder
			 */
			@Override
			public SearchBuilder<S> readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			/**
			 * Returns this builder with the given disk-use option applied to the generated
			 * aggregation query.
			 *
			 * @param allow
			 *            whether disk use should be allowed
			 *
			 * @return this builder
			 */
			@Override
			public SearchBuilder<S> isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			/**
			 * Configures the Atlas Search count mode to include inside {@code $search}.
			 * <p>This affects the {@code count} section embedded in the
			 * {@code $search} stage. If you need metadata-only count retrieval, use
			 * {@link SearchCountQueryBuilder#executeSearchMeta()}.</p>
			 *
			 * @param searchCountType
			 *            the count mode
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> countType(
				SearchCountType searchCountType
			) {

				this.searchCountType = searchCountType;
				return this;

			}

			/**
			 * Enables or disables Atlas Search score-details retrieval.
			 * <p>When enabled, callers can expose the returned metadata through
			 * {@link #addFieldsScoreDetails()} or {@link #addFieldsScoreDetails(String)}.</p>
			 *
			 * @param scoreDetails
			 *            whether score details should be returned by Atlas Search
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> scoreDetails(
				boolean scoreDetails
			) {

				this.scoreDetails = scoreDetails;
				return this;

			}

			/**
			 * Adds a search-after token for Atlas Search cursor-style pagination.
			 *
			 * @param searchAfterToken
			 *            the encoded Atlas Search sequence token
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> searchAfter(
				String searchAfterToken
			) {

				this.searchAfterToken = searchAfterToken;
				this.searchBeforeToken = null;
				return this;

			}

			/**
			 * Adds a search-before token for Atlas Search cursor-style reverse pagination.
			 *
			 * @param searchBeforeToken
			 *            the encoded Atlas Search sequence token
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> searchBefore(
				String searchBeforeToken
			) {

				this.searchBeforeToken = searchBeforeToken;
				this.searchAfterToken = null;
				return this;

			}

			/**
			 * Excludes the given fields from the final mapped result projection.
			 *
			 * @param excludes
			 *            the field names to exclude
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes );
				return this;

			}

			/**
			 * Configures zero-based paging for the final post-search result set.
			 * <p>This paging is applied <strong>after</strong> the {@code $search} stage
			 * and after any post-search {@code fields(...)} filter.</p>
			 *
			 * @param pageNumber
			 *            the zero-based page index
			 * @param pageSize
			 *            the page size
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> paging(
				int pageNumber, int pageSize
			) {

				if (pageNumber < 0 || pageSize <= 0)
					throw new IllegalArgumentException( "Invalid pageNumber or pageSize." );
				this.pageNumber = pageNumber;
				this.pageSize = pageSize;
				return this;

			}

			/**
			 * Starts ordered Atlas Search sorting.
			 * <p>Use {@link SortSpec#asc(String, String...)}, {@link SortSpec#desc(String, String...)},
			 * or {@link SortSpec#driver(Bson)} and finish with {@link SortSpec#end()} to continue
			 * with this search builder. Search-score ordering can be placed before or after the
			 * sort block by calling {@link #scoreDesc()} / {@link #scoreAsc()} before or after it.</p>
			 *
			 * @return the ordered sort DSL
			 */
			public SortSpec<SearchBuilder<S>> sorts() {

				return new SortSpec<SearchBuilder<S>>( this ) {

					@Override
					protected void apply() {

						if (! isEmpty()) {
							SearchBuilder.this.searchSorts.add( this );

						}

					}

				};

			}

			/**
			 * Configures ordered Atlas Search sorting in one callback and returns this builder.
			 *
			 * @param spec
			 *            the ordered sort configuration
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> sorts(
				Consumer<SortSpec<SearchBuilder<S>>> spec
			) {

				SortSpec<SearchBuilder<S>> sort = sorts();
				Objects.requireNonNull( spec, "spec" ).accept( sort );
				return sort.end();

			}

			/** Appends a descending Atlas Search score sort at the current sort priority. */
			public SearchBuilder<S> scoreDesc() {

				this.searchSorts.add( new Document( "score", new Document( "$meta", "searchScore" ) ) );
				return this;

			}

			/** Appends an ascending Atlas Search score sort at the current sort priority. */
			public SearchBuilder<S> scoreAsc() {

				this.searchSorts
					.add(
						new Document( "score", new Document( "$meta", "searchScore" ).append( "order", 1 ) )
					);
				return this;

			}

			/**
			 * Adds a post-search field exposing the Atlas Search score using the default
			 * alias {@code score}.
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsScore() {

				return addFieldsScore( "score" );

			}

			/**
			 * Adds a post-search field exposing the Atlas Search score using the given
			 * alias.
			 *
			 * @param alias
			 *            the target field alias
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsScore(
				String alias
			) {

				this.addFieldsDocs.add( MongoBsonSupport.toDocument( Projections.metaSearchScore( alias ) ) );
				return this;

			}

			/**
			 * Filters out Atlas Search results whose {@code searchScore} is lower than
			 * the given value.
			 * <p>This filter is rendered after {@code $addFields: { score: { $meta:
			 * "searchScore" } }} and before paging/count terminal stages, so
			 * {@code executePage()} and {@code count().execute()} use the same threshold.</p>
			 *
			 * @param score
			 *            the inclusive minimum score
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> matchScoreGte(
				double score
			) {

				validateSearchScore( score );

				if (this.scoreLte != null && score > this.scoreLte) {
					throw new IllegalArgumentException( "score must be <= current lte score." );

				}

				this.scoreGte = score;
				return this;

			}

			/**
			 * Filters out Atlas Search results whose {@code searchScore} is greater than
			 * the given value.
			 * <p>This filter is rendered after {@code $addFields: { score: { $meta:
			 * "searchScore" } }} and before paging/count terminal stages, so
			 * {@code executePage()} and {@code count().execute()} use the same threshold.</p>
			 *
			 * @param score
			 *            the inclusive maximum score
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> matchScoreLte(
				double score
			) {

				validateSearchScore( score );

				if (this.scoreGte != null && score < this.scoreGte) {
					throw new IllegalArgumentException( "score must be >= current gte score." );

				}

				this.scoreLte = score;
				return this;

			}

			/**
			 * Filters Atlas Search results to the inclusive {@code searchScore} range.
			 * <p>This filter is rendered after {@code $addFields: { score: { $meta:
			 * "searchScore" } }} and before paging/count terminal stages, so
			 * {@code executePage()} and {@code count().execute()} use the same threshold.</p>
			 *
			 * @param gte
			 *            the inclusive minimum score
			 * @param lte
			 *            the inclusive maximum score
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> matchScoreBetween(
				double gte, double lte
			) {

				validateSearchScore( gte );
				validateSearchScore( lte );

				if (gte > lte) {
					throw new IllegalArgumentException( "gte score must be <= lte score." );

				}

				this.scoreGte = gte;
				this.scoreLte = lte;
				return this;

			}

			/**
			 * Adds a post-search field exposing Atlas Search score details using the
			 * default alias {@code scoreDetails}.
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsScoreDetails() {

				return addFieldsScoreDetails( "scoreDetails" );

			}

			/**
			 * Adds a post-search field exposing Atlas Search score details using the
			 * given alias.
			 * <p>This method also enables {@code scoreDetails(true)} automatically because
			 * Atlas Search only returns score-details metadata when explicitly requested.</p>
			 *
			 * @param alias
			 *            the target field alias
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsScoreDetails(
				String alias
			) {

				this.scoreDetails = true;
				this.addFieldsDocs.add( MongoBsonSupport.toDocument( Projections.meta( alias, "searchScoreDetails" ) ) );
				return this;

			}

			/**
			 * Adds a post-search field exposing the Atlas Search sequence token using the
			 * default alias {@code searchSequenceToken}.
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsSequenceToken() {

				return addFieldsSequenceToken( "searchSequenceToken" );

			}

			/**
			 * Adds a post-search field exposing the Atlas Search sequence token using the
			 * given alias.
			 *
			 * @param alias
			 *            the target field alias
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsSequenceToken(
				String alias
			) {

				this.addFieldsDocs.add( MongoBsonSupport.toDocument( Projections.meta( alias, "searchSequenceToken" ) ) );
				return this;

			}

			/**
			 * Sets the root Atlas Search operator explicitly.
			 *
			 * @param operator
			 *            the root Atlas Search operator
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> operator(
				AtlasSearchOperator operator
			) {

				this.rootOperator = Objects.requireNonNull( operator, "operator" );
				return this;

			}

			/**
			 * Sets a MongoDB driver-native root search operator. This is the advanced escape
			 * hatch for operators the convenience DSL does not expose yet.
			 *
			 * @param operator
			 *            the driver-native search operator
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> operator(
				SearchOperator operator
			) {

				this.rootOperator = AtlasSearchOperator.of( "driver", Objects.requireNonNull( operator, "operator" ) );
				return this;

			}

			/**
			 * Applies an advanced MongoDB driver-native search option customizer after the
			 * convenience DSL options have been assembled.
			 *
			 * @param customizer
			 *            the driver option customizer
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> driverOptions(
				Function<SearchOptions, SearchOptions> customizer
			) {

				this.driverOptionCustomizers.add( Objects.requireNonNull( customizer, "customizer" ) );
				return this;

			}

			/**
			 * Adds a driver-native aggregation stage immediately after {@code $search}.
			 * <p>The stage runs before post-search {@code fields(...)} criteria, metadata
			 * additions, score filtering, paging, and projection. This does not apply to
			 * {@code executeSearchMeta()}, which uses a dedicated {@code $searchMeta}
			 * metadata-count pipeline.</p>
			 *
			 * @param stage
			 *            the driver-native aggregation stage
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> stage(
				Bson stage
			) {

				this.stages.add( Objects.requireNonNull( stage, "stage" ) );
				return this;

			}

			/** Adds driver-native aggregation stages immediately after {@code $search}. */
			public SearchBuilder<S> stages(
				Bson... stages
			) {

				return stages( Arrays.asList( Objects.requireNonNull( stages, "stages" ) ) );

			}

			/** Adds driver-native aggregation stages immediately after {@code $search}. */
			public SearchBuilder<S> stages(
				Collection<? extends Bson> stages
			) {

				Objects.requireNonNull( stages, "stages" ).forEach( this::stage );
				return this;

			}

			/**
			 * Builds the root operator as a {@code text} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> text(
				Consumer<TextClause> spec
			) {

				TextClause op = SearchOperators.text();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as a {@code phrase} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> phrase(
				Consumer<PhraseClause> spec
			) {

				PhraseClause op = SearchOperators.phrase();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as an {@code autocomplete} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> autocomplete(
				Consumer<AutocompleteClause> spec
			) {

				AutocompleteClause op = SearchOperators.autocomplete();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as an {@code equals} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> equals(
				Consumer<EqualsClause> spec
			) {

				EqualsClause op = SearchOperators.equals();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as an {@code exists} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> exists(
				Consumer<ExistsClause> spec
			) {

				ExistsClause op = SearchOperators.exists();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as an {@code in} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> in(
				Consumer<InClause> spec
			) {

				InClause op = SearchOperators.in();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as a {@code range} search operator.
			 *
			 * @param spec
			 *            the operator configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> range(
				Consumer<RangeClause> spec
			) {

				RangeClause op = SearchOperators.range();
				spec.accept( op );
				this.rootOperator = op;
				return this;

			}

			/**
			 * Builds the root operator as a {@code compound} search operator.
			 *
			 * @param spec
			 *            the compound configuration callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> compound(
				Consumer<SearchCompoundBuilder<S>> spec
			) {

				SearchCompoundBuilder<S> compound = new SearchCompoundBuilder<>();
				spec.accept( compound );
				this.rootOperator = compound.build();
				return this;

			}

			/**
			 * Adds post-search criteria expressed with the regular DSL
			 * {@link FieldsPair} model.
			 * <p>These conditions are converted into a normal aggregation
			 * {@code $match} stage that runs <strong>after</strong> the
			 * {@code $search} stage.</p>
			 *
			 * @param fieldsPairs
			 *            the post-search field conditions
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> fields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				this.postFilterBuilder.fields( fieldsPairs );
				return this;

			}

			/**
			 * Adds post-search criteria expressed with the regular DSL
			 * {@link FieldsPair} model.
			 * <p>These conditions are converted into a normal aggregation
			 * {@code $match} stage that runs <strong>after</strong> the
			 * {@code $search} stage.</p>
			 *
			 * @param fieldsPairs
			 *            the post-search field conditions
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> fields(
				Collection<FieldsPair<?, ?>> fieldsPairs
			) {

				if (fieldsPairs == null || fieldsPairs.isEmpty())
					return this;
				this.postFilterBuilder.fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );
				return this;

			}

			/**
			 * Sets Atlas Search stage-level highlight options.
			 *
			 * @param highlightSpec
			 *            the highlight specification
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> highlight(
				SearchHighlightSpec highlightSpec
			) {

				return highlight( Objects.requireNonNull( highlightSpec, "highlightSpec" ).toSearchHighlight() );

			}

			/**
			 * Sets MongoDB driver-native Atlas Search highlight options directly.
			 *
			 * @param highlight
			 *            the driver-native highlight specification
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> highlight(
				SearchHighlight highlight
			) {

				this.highlight = Objects.requireNonNull( highlight, "highlight" );
				return this;

			}

			/**
			 * Builds Atlas Search stage-level highlight options through a fluent callback.
			 *
			 * @param spec
			 *            the highlight builder callback
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> highlight(
				Consumer<SearchHighlightSpec.Builder> spec
			) {

				SearchHighlightSpec.Builder builder = SearchHighlightSpec.builder();
				spec.accept( builder );
				return highlight( builder.build() );

			}

			/**
			 * Extracts Atlas Search highlight metadata into the default alias {@code highlights}.
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsHighlights() {

				return addFieldsHighlights( "highlights" );

			}

			/**
			 * Extracts Atlas Search highlight metadata into the given alias.
			 *
			 * @param alias
			 *            the target alias
			 *
			 * @return this builder
			 */
			public SearchBuilder<S> addFieldsHighlights(
				String alias
			) {

				this.addFieldsDocs.add( MongoBsonSupport.toDocument( Projections.metaSearchHighlights( alias ) ) );
				return this;

			}

			/**
			 * Creates a terminal builder for multi-result Atlas Search reads.
			 *
			 * @return a multi-result Atlas Search terminal builder
			 */
			public SearchFindAllQueryBuilder<S> findAll() {

				return new SearchFindAllQueryBuilder<>();

			}

			/**
			 * Creates a terminal builder for single-result Atlas Search reads.
			 *
			 * @return a single-result Atlas Search terminal builder
			 */
			public SearchFindQueryBuilder<S> find() {

				return new SearchFindQueryBuilder<>();

			}

			/**
			 * Creates a terminal builder for Atlas Search count reads.
			 *
			 * @return a count terminal builder
			 */
			public SearchCountQueryBuilder count() {

				return new SearchCountQueryBuilder();

			}

			/**
			 * Creates a terminal builder for Atlas Search existence checks.
			 *
			 * @return an exists terminal builder
			 */
			public SearchExistsQueryBuilder existsQuery() {

				return new SearchExistsQueryBuilder();

			}

			/**
			 * Verifies that a root Atlas Search operator has been configured.
			 */
			private void validateRootOperator() {

				if (this.rootOperator == null) {
					throw new IllegalStateException( "search operator is required" );

				}

			}

			/**
			 * Builds MongoDB driver's stage-level search options. Typed driver options are used
			 * when available; generic driver options are reserved for stage options that do not
			 * currently have a dedicated builder method.
			 */
			private SearchOptions buildSearchOptions(
				boolean includeCount, boolean includeResultOptions
			) {

				SearchOptions options = SearchOptions.searchOptions();

				if (this.index != null && ! this.index.isBlank()) {
					options = options.index( this.index );

				}

				if (this.highlight != null && includeResultOptions) {
					options = options.highlight( this.highlight );

				}

				if (includeCount && this.searchCountType != null) {
					options = options.count( this.searchCountType.toSearchCount() );

				}

				if (includeResultOptions && this.searchAfterToken != null && ! this.searchAfterToken.isBlank()) {
					options = options.option( "searchAfter", this.searchAfterToken );

				}

				if (includeResultOptions && this.searchBeforeToken != null && ! this.searchBeforeToken.isBlank()) {
					options = options.option( "searchBefore", this.searchBeforeToken );

				}

				if (includeResultOptions && this.scoreDetails) {
					options = options.option( "scoreDetails", true );

				}

				if (includeResultOptions && ! this.searchSorts.isEmpty()) {
					options = options
						.option(
							"sort",
							MongoBsonSupport.toDocument( Sorts.orderBy( this.searchSorts ) )
						);

				}

				for (Function<SearchOptions, SearchOptions> customizer : this.driverOptionCustomizers) {
					options = Objects.requireNonNull( customizer.apply( options ), "driver search option customizer result" );

				}

				return options;

			}

			private Bson buildSearchStage(
				boolean includeCount
			) {

				validateRootOperator();
				return Aggregates
					.search(
						this.rootOperator.toSearchOperator(),
						buildSearchOptions( includeCount, true )
					);

			}

			private boolean hasScoreMatch() {

				return this.scoreGte != null || this.scoreLte != null;

			}

			private Bson buildScoreMatchCriteria() {

				if (this.scoreGte != null && this.scoreLte != null)
					return new Document( "score", new Document( "$gte", this.scoreGte ).append( "$lte", this.scoreLte ) );
				if (this.scoreGte != null)
					return Filters.gte( "score", this.scoreGte );
				return Filters.lte( "score", this.scoreLte );

			}

			private void validateSearchScore(
				double score
			) {

				if (! Double.isFinite( score )) {
					throw new IllegalArgumentException( "score must be finite." );

				}

			}

			private Bson buildSearchMetaStage(
				SearchCountType countType
			) {

				validateRootOperator();
				SearchOptions options = buildSearchOptions( false, false ).count( countType.toSearchCount() );
				return Aggregates.searchMeta( this.rootOperator.toSearchOperator(), options );

			}

			/**
			 * Builds the aggregation operations that should run after the Atlas Search
			 * stage.
			 *
			 * @param postCriteria
			 *            the optional post-search match criteria
			 * @param includePaging
			 *            whether paging stages should be appended
			 * @param includeProjection
			 *            whether exclude-based projection should be appended
			 * @param includeCount
			 *            whether Atlas Search's native count clause should be added to
			 *            {@code $search}
			 * @param includeMetaAdds
			 *            whether metadata-based {@code $addFields} stages should be
			 *            appended
			 *
			 * @return the aggregation operations
			 */
			private List<Bson> buildAggregationOps(
				Optional<Bson> postCriteria, boolean includePaging, boolean includeProjection, boolean includeCount, boolean includeMetaAdds
			) {

				List<Bson> ops = new ArrayList<>();

				ops.add( buildSearchStage( includeCount ) );

				ops.addAll( this.stages );

				postCriteria.ifPresent( c -> ops.add( Aggregates.match( c ) ) );

				if ((includeMetaAdds && ! this.addFieldsDocs.isEmpty()) || hasScoreMatch()) {
					Document addFields = new Document();

					if (includeMetaAdds) {

						for (Document d : this.addFieldsDocs) {

							for (Map.Entry<String, Object> entry : d.entrySet()) {
								addFields.append( entry.getKey(), entry.getValue() );

							}

						}

					}

					if (hasScoreMatch() && ! addFields.containsKey( "score" )) {
						addFields.putAll( MongoBsonSupport.toDocument( Projections.metaSearchScore( "score" ) ) );

					}

					ops
						.add(
							Aggregates
								.addFields(
									addFields
										.entrySet()
										.stream()
										.map( entry -> new com.mongodb.client.model.Field<>( entry.getKey(), entry.getValue() ) )
										.toArray( com.mongodb.client.model.Field<?>[]::new )
								)
						);

				}

				if (hasScoreMatch()) {
					ops.add( Aggregates.match( buildScoreMatchCriteria() ) );

				}

				if (includePaging && this.pageNumber != null && this.pageSize != null) {
					ops.add( Aggregates.skip( Math.toIntExact( (long) this.pageNumber * this.pageSize ) ) );
					ops.add( Aggregates.limit( this.pageSize ) );

				}

				if (includeProjection && this.excludes != null && this.excludes.length > 0) {
					ops.add( Aggregates.project( Projections.exclude( this.excludes ) ) );

				}

				return ops;

			}

			/**
			 * Executes the given aggregation operations and returns raw {@link Document}
			 * results.
			 *
			 * @param entityClass
			 *            the mapped entity type
			 * @param ops
			 *            the aggregation operations
			 *
			 * @return the raw aggregation result stream
			 */
			private Flux<Document> aggregateDocuments(
				Class<?> entityClass, List<Bson> ops
			) {

				return ReactiveMongoDsl.this
					.aggregateDocuments(
						mongoExecutionContext,
						entityClass,
						collectionName,
						applyAggOptions( ops )
					);

			}

			/**
			 * Strongly typed builder for the Atlas Search {@code compound} operator.
			 *
			 * @param <T>
			 *            the current mapped entity type
			 */
			public class SearchCompoundBuilder<T extends E> {

				private final List<AtlasSearchOperator> must = new ArrayList<>();

				private final List<AtlasSearchOperator> mustNot = new ArrayList<>();

				private final List<AtlasSearchOperator> should = new ArrayList<>();

				private final List<AtlasSearchOperator> filter = new ArrayList<>();

				private Integer minimumShouldMatch;

				private SearchScore score;

				/**
				 * Sets the minimum number of {@code should} clauses that must match.
				 *
				 * @param minimumShouldMatch
				 *            the minimum number of {@code should} clauses that must match
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> minimumShouldMatch(
					int minimumShouldMatch
				) {

					if (minimumShouldMatch < 0)
						throw new IllegalArgumentException( "minimumShouldMatch must be >= 0" );
					this.minimumShouldMatch = minimumShouldMatch;
					return this;

				}

				/**
				 * Sets the compound-level score specification.
				 *
				 * @param score
				 *            the score specification
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> score(
					SearchScoreSpec score
				) {

					this.score = score == null ? null : score.toSearchScore();
					return this;

				}

				public SearchCompoundBuilder<T> score(
					SearchScore score
				) {

					this.score = score;
					return this;

				}

				/**
				 * Adds a raw operator to the {@code must} clause.
				 *
				 * @param operator
				 *            the operator
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> must(
					AtlasSearchOperator operator
				) {

					this.must.add( operator );
					return this;

				}

				/**
				 * Adds a raw operator to the {@code mustNot} clause.
				 *
				 * @param operator
				 *            the operator
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> mustNot(
					AtlasSearchOperator operator
				) {

					this.mustNot.add( operator );
					return this;

				}

				/**
				 * Adds a raw operator to the {@code should} clause.
				 *
				 * @param operator
				 *            the operator
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> should(
					AtlasSearchOperator operator
				) {

					this.should.add( operator );
					return this;

				}

				/**
				 * Adds a raw operator to the {@code filter} clause.
				 *
				 * @param operator
				 *            the operator
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> filter(
					AtlasSearchOperator operator
				) {

					this.filter.add( operator );
					return this;

				}

				/**
				 * Adds a convenience {@code text} operator to {@code must}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> mustText(
					Object path, Consumer<TextClause> spec
				) {

					TextClause op = SearchOperators.text().path( path );
					spec.accept( op );
					return must( op );

				}

				/**
				 * Adds a convenience {@code text} operator to {@code should}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> shouldText(
					Object path, Consumer<TextClause> spec
				) {

					TextClause op = SearchOperators.text().path( path );
					spec.accept( op );
					return should( op );

				}

				/**
				 * Adds a convenience {@code text} operator to {@code filter}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> filterText(
					Object path, Consumer<TextClause> spec
				) {

					TextClause op = SearchOperators.text().path( path );
					spec.accept( op );
					return filter( op );

				}

				/**
				 * Adds a convenience {@code phrase} operator to {@code must}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> mustPhrase(
					Object path, Consumer<PhraseClause> spec
				) {

					PhraseClause op = SearchOperators.phrase().path( path );
					spec.accept( op );
					return must( op );

				}

				/**
				 * Adds a convenience {@code autocomplete} operator to {@code should}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> shouldAutocomplete(
					Object path, Consumer<AutocompleteClause> spec
				) {

					AutocompleteClause op = SearchOperators.autocomplete().path( path );
					spec.accept( op );
					return should( op );

				}

				/**
				 * Adds a convenience {@code equals} operator to {@code filter}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> filterEquals(
					Object path, Consumer<EqualsClause> spec
				) {

					EqualsClause op = SearchOperators.equals().path( path );
					spec.accept( op );
					return filter( op );

				}

				/**
				 * Adds a convenience {@code in} operator to {@code filter}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> filterIn(
					Object path, Consumer<InClause> spec
				) {

					InClause op = SearchOperators.in().path( path );
					spec.accept( op );
					return filter( op );

				}

				/**
				 * Adds a convenience {@code range} operator to {@code filter}.
				 *
				 * @param path
				 *            the search path
				 * @param spec
				 *            the operator configuration callback
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> filterRange(
					Object path, Consumer<RangeClause> spec
				) {

					RangeClause op = SearchOperators.range().path( path );
					spec.accept( op );
					return filter( op );

				}

				/**
				 * Adds an {@code exists} operator to {@code mustNot}.
				 *
				 * @param path
				 *            the search path
				 *
				 * @return this builder
				 */
				public SearchCompoundBuilder<T> mustNotExists(
					Object path
				) {

					return mustNot( SearchOperators.exists().path( path ) );

				}

				/**
				 * Builds the final {@code compound} operator.
				 *
				 * @return the rendered root operator
				 */
				AtlasSearchOperator build() {

					CompoundSearchOperator compound = null;

					if (! this.must.isEmpty()) {
						compound = SearchOperator.compound().must( toSearchOperators( this.must ) );

					}

					if (! this.mustNot.isEmpty()) {
						compound = compound == null
							? SearchOperator.compound().mustNot( toSearchOperators( this.mustNot ) )
							: compound.mustNot( toSearchOperators( this.mustNot ) );

					}

					if (! this.filter.isEmpty()) {
						compound = compound == null
							? SearchOperator.compound().filter( toSearchOperators( this.filter ) )
							: compound.filter( toSearchOperators( this.filter ) );

					}

					if (! this.should.isEmpty()) {
						ShouldCompoundSearchOperator shouldCompound = compound == null
							? SearchOperator.compound().should( toSearchOperators( this.should ) )
							: compound.should( toSearchOperators( this.should ) );

						compound = this.minimumShouldMatch == null
							? shouldCompound
							: shouldCompound.minimumShouldMatch( this.minimumShouldMatch );

					} else if (this.minimumShouldMatch != null) {
						throw new IllegalStateException( "minimumShouldMatch requires at least one should clause" );

					}

					if (compound == null) {
						throw new IllegalStateException( "compound requires at least one clause" );

					}

					if (this.score != null) {
						compound = compound.score( this.score );

					}

					return AtlasSearchOperator.of( "compound", compound );

				}

				private List<SearchOperator> toSearchOperators(
					List<AtlasSearchOperator> operators
				) {

					return operators
						.stream()
						.filter( Objects::nonNull )
						.map( AtlasSearchOperator::toSearchOperator )
						.toList();

				}

			}

			/**
			 * Terminal builder for multi-result Atlas Search reads.
			 *
			 * @param <T>
			 *            the current mapped entity type
			 */
			public class SearchFindAllQueryBuilder<T extends E> {

				/**
				 * Executes the Atlas Search query and maps all matching documents to the
				 * current entity type.
				 *
				 * @return a {@link Flux} emitting the mapped search results
				 */
				public Flux<E> execute() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria() )
						.flatMapMany( tuple -> {
							Class<E> entityClass = tuple.getT1();
							Optional<Bson> criteriaOpt = tuple.getT2();
							List<Bson> ops = buildAggregationOps(
								criteriaOpt,
								true,
								true,
								searchCountType != null,
								true
							);
							return aggregateDocuments( entityClass, ops )
								.map( doc -> mongoExecutionContext.read( entityClass, doc ) );

						} );

				}

				public Mono<Document> preview() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria() )
						.map(
							tuple -> previewAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), true, true, searchCountType != null, true ) )
							)
						);

				}

				public Mono<Document> explain() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria() )
						.flatMap(
							tuple -> explainAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), true, true, searchCountType != null, true ) )
							)
						);

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria() )
						.flatMap(
							tuple -> explainAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), true, true, searchCountType != null, true ) ),
								verbosity
							)
						);

				}

				/**
				 * Executes the Atlas Search query and collects both the current page data and
				 * the total pipeline result count.
				 *
				 * @return a {@link Mono} emitting the paged result
				 */
				public Mono<PageResult<E>> executePage() {

					return executePageStream().collectToPageResult();

				}

				/**
				 * Executes the Atlas Search query as a reactive page. The data stream is not
				 * collected unless the caller explicitly invokes {@code collectToPageResult()}.
				 *
				 * @return a reactive page wrapper containing streamed search data and total count
				 */
				public PageStream<E> executePageStream() {

					return new PageStream<>( execute(), count().execute() );

				}

			}

			/**
			 * Terminal builder for single-result Atlas Search reads.
			 *
			 * @param <T>
			 *            the current mapped entity type
			 */
			public class SearchFindQueryBuilder<T extends E> {

				private Mono<AggregationSpec> buildFirstAggregation() {

					return postFilterBuilder.buildCriteria().map( criteria -> {
						Integer oldPageNumber = pageNumber;
						Integer oldPageSize = pageSize;

						try {
							pageNumber = 0;
							pageSize = 1;
							return applyAggOptions( buildAggregationOps( criteria, true, true, searchCountType != null, true ) );

						} finally {
							pageNumber = oldPageNumber;
							pageSize = oldPageSize;

						}

					} );

				}

				/**
				 * Executes the Atlas Search query and returns at most one mapped entity.
				 *
				 * @return a {@link Mono} emitting the mapped search result
				 */
				public Mono<E> execute() {

					return executeFirst();

				}

				/**
				 * Executes the Atlas Search query and returns the first mapped entity.
				 *
				 * @return a {@link Mono} emitting the first mapped search result
				 */
				public Mono<E> executeFirst() {

					Integer oldPageNumber = pageNumber;
					Integer oldPageSize = pageSize;

					pageNumber = 0;
					pageSize = 1;

					return new SearchFindAllQueryBuilder<T>()
						.execute()
						.next()
						.doFinally( st -> {
							pageNumber = oldPageNumber;
							pageSize = oldPageSize;

						} );

				}

				public Mono<Document> preview() {

					return Mono
						.zip( executeClassMono, buildFirstAggregation() )
						.map( tuple -> previewAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ).append( "first", true ) );

				}

				public Mono<Document> explain() {

					return Mono
						.zip( executeClassMono, buildFirstAggregation() )
						.flatMap( tuple -> explainAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return Mono
						.zip( executeClassMono, buildFirstAggregation() )
						.flatMap( tuple -> explainAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), verbosity ) );

				}

			}

			/**
			 * Terminal builder for Atlas Search count reads.
			 */
			public class SearchCountQueryBuilder {

				/**
				 * Counts the final pipeline results produced by {@code $search} followed by
				 * post-search filtering.
				 * <p>This is intentionally different from
				 * {@link #executeSearchMeta()}, which asks Atlas Search itself to return the
				 * search metadata count.</p>
				 *
				 * @return a {@link Mono} emitting the final pipeline result count
				 */
				public Mono<Long> execute() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria() )
						.flatMap( tuple -> {
							Class<E> entityClass = tuple.getT1();
							Optional<Bson> criteriaOpt = tuple.getT2();

							List<Bson> ops = buildAggregationOps(
								criteriaOpt,
								false,
								false,
								searchCountType != null,
								false
							);

							ops.add( Aggregates.count( "count" ) );

							return aggregateDocuments( entityClass, ops )
								.next()
								.map( d -> Optional.ofNullable( d.get( "count", Number.class ) ).map( Number::longValue ).orElse( 0L ) )
								.defaultIfEmpty( 0L );

						} );

				}

				private AggregationSpec buildCountAggregation(
					Optional<Bson> criteria
				) {

					List<Bson> ops = buildAggregationOps( criteria, false, false, searchCountType != null, false );
					ops.add( Aggregates.count( "count" ) );
					return applyAggOptions( ops );

				}

				public Mono<Document> preview() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria().map( this::buildCountAggregation ) )
						.map( tuple -> previewAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

				}

				public Mono<Document> explain() {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria().map( this::buildCountAggregation ) )
						.flatMap( tuple -> explainAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return Mono
						.zip( executeClassMono, postFilterBuilder.buildCriteria().map( this::buildCountAggregation ) )
						.flatMap( tuple -> explainAggregation( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), verbosity ) );

				}

				/**
				 * Requests the Atlas Search metadata count using {@code $searchMeta}.
				 * <p>This count is produced by Atlas Search itself and therefore does not run
				 * post-search {@code fields(...)} as a normal aggregation {@code $match}.</p>
				 *
				 * @return a {@link Mono} emitting the Atlas Search metadata count
				 */
				public Mono<Long> executeSearchMeta() {

					validateRootOperator();

					SearchCountType countType = (searchCountType == null)
						? SearchCountType.TOTAL
						: searchCountType;

					return executeClassMono.flatMap( entityClass -> {
						Bson searchMetaStage = buildSearchMetaStage( countType );
						AggregationSpec aggregation = applyAggOptions( List.of( searchMetaStage ) );

						Flux<Document> docs = ReactiveMongoDsl.this.aggregateDocuments( mongoExecutionContext, entityClass, collectionName, aggregation );

						return docs
							.next()
							.map( d -> {
								Document count = d.get( "count", Document.class );

								if (count == null)
									return 0L;

								Number total = count.get( "total", Number.class );

								if (total != null)
									return total.longValue();

								Number lowerBound = count.get( "lowerBound", Number.class );
								return lowerBound == null ? 0L : lowerBound.longValue();

							} )
							.defaultIfEmpty( 0L );

					} );

				}

			}

			/**
			 * Terminal builder for Atlas Search existence checks.
			 */
			public class SearchExistsQueryBuilder {

				/**
				 * Returns whether the Atlas Search query yields at least one final pipeline
				 * result.
				 *
				 * @return a {@link Mono} emitting {@code true} when at least one result exists
				 */
				public Mono<Boolean> execute() {

					return count().execute().map( count -> count > 0L );

				}

				public Mono<Document> preview() {

					return count().preview();

				}

				public Mono<Document> explain() {

					return count().explain();

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return count().explain( verbosity );

				}

			}

		}


		/**
		 * MongoDB {@code $vectorSearch}-specific builder.
		 * <p>This builder is intentionally separated from {@link SearchBuilder}
		 * because {@code $vectorSearch} is a different aggregation stage with its own
		 * syntax, pagination constraints, and pre-filter semantics.</p>
		 * <p>Stage-level pre-filtering is expressed with {@link #filterFields(FieldsPair[])}
		 * and rendered into the {@code filter} field inside {@code $vectorSearch}.
		 * Regular {@link FieldsPair}-based {@link #fields(FieldsPair[])} conditions are
		 * rendered after the stage as a normal aggregation {@code $match}.</p>
		 *
		 * @param <S>
		 *            the current mapped entity type
		 */
		public class VectorSearchBuilder<S extends E> extends QueryBuilderAccesser<VectorSearchBuilder<S>, VectorSearchBuilder<S>> {

			private final String index;

			private final FieldBuilder<E> preFilterBuilder = new FieldBuilder<>( LogicalOperator.AND );

			private final FieldBuilder<E> parentFilterBuilder = new FieldBuilder<>( LogicalOperator.AND );

			private final FieldBuilder<E> postFilterBuilder = new FieldBuilder<>( LogicalOperator.AND );

			private FieldSearchPath path;


			private List<Double> queryVector;

			private BinaryVector driverBinaryVector;

			private String queryText;

			private String model;



			private Long limit;

			private Long numCandidates;

			private Boolean exact;

			private VectorSearchQuery driverQuery;

			private VectorSearchScoreMode nestedScoreMode;

			private final List<Function<VectorSearchOptions, VectorSearchOptions>> driverOptionCustomizers = new ArrayList<>();

			private final List<Bson> stages = new ArrayList<>();

			private final List<Document> addFieldsDocs = new ArrayList<>();

			private String[] excludes;

			VectorSearchBuilder(
								String index
			) {

				this.index = index;

			}

			/**
			 * Returns this builder with the given read preference applied to the generated
			 * aggregation query.
			 *
			 * @param rp
			 *            the read preference
			 *
			 * @return this builder
			 */
			@Override
			public VectorSearchBuilder<S> readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			/**
			 * Returns this builder with the given disk-use option applied to the generated
			 * aggregation query.
			 *
			 * @param allow
			 *            whether disk use should be allowed
			 *
			 * @return this builder
			 */
			@Override
			public VectorSearchBuilder<S> isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			/**
			 * Sets the field path used by {@code $vectorSearch}.
			 * <p>For manual vector indexes this is the embedding vector field. For
			 * Automated Embedding indexes this is the indexed text field.</p>
			 */
			public VectorSearchBuilder<S> path(
				String path
			) {

				this.path = SearchPathResolver.resolveFieldPath( path );
				return this;

			}

			/** Enum paths use {@link Enum#toString()}, allowing explicit physical field values. */
			public VectorSearchBuilder<S> path(
				Enum<?> path
			) {

				this.path = SearchPathResolver.resolveFieldPath( path );
				return this;

			}

			/** Uses a MongoDB driver-native field path directly. */
			public VectorSearchBuilder<S> path(
				FieldSearchPath path
			) {

				this.path = SearchPathResolver.resolveFieldPath( path );
				return this;

			}

			/** Fallback for custom path wrappers. */
			public VectorSearchBuilder<S> path(
				Object path
			) {

				this.path = SearchPathResolver.resolveFieldPath( path );
				return this;

			}

			/**
			 * Sets the query vector from a float array.
			 *
			 * @param values
			 *            the float values
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> queryVector(
				float[] values
			) {

				Objects.requireNonNull( values, "values" );

				if (values.length == 0) { throw new IllegalArgumentException( "values must not be empty" ); }

				this.queryVector = java.util.stream.IntStream
					.range( 0, values.length )
					.mapToObj( index -> (double) values[index] )
					.toList();
				this.driverBinaryVector = null;
				this.driverQuery = null;
				this.queryText = null;
				this.model = null;
				return this;

			}

			/**
			 * Sets the query vector from a double array.
			 *
			 * @param values
			 *            the double values
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> queryVector(
				double[] values
			) {

				Objects.requireNonNull( values, "values" );

				if (values.length == 0) { throw new IllegalArgumentException( "values must not be empty" ); }

				this.queryVector = Arrays.stream( values ).boxed().toList();
				this.driverBinaryVector = null;
				this.driverQuery = null;
				this.queryText = null;
				this.model = null;
				return this;

			}

			/**
			 * Sets the query vector from a collection of doubles.
			 *
			 * @param values
			 *            the vector values
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> queryVector(
				Collection<Double> values
			) {

				Objects.requireNonNull( values, "values" );

				if (values.isEmpty()) { throw new IllegalArgumentException( "values must not be empty" ); }

				if (values.stream().anyMatch( Objects::isNull )) { throw new IllegalArgumentException( "values must not contain null" ); }

				this.queryVector = List.copyOf( values );
				this.driverBinaryVector = null;
				this.driverQuery = null;
				this.queryText = null;
				this.model = null;
				return this;

			}

			/**
			 * Sets a MongoDB driver-native binary query vector. This advanced path keeps
			 * the driver's compact vector representation available without changing the
			 * beginner-friendly array/list overloads.
			 *
			 * @param queryVector
			 *            the driver-native binary vector
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> queryVector(
				BinaryVector queryVector
			) {

				this.driverBinaryVector = Objects.requireNonNull( queryVector, "queryVector" );
				this.queryVector = null;
				this.driverQuery = null;
				this.queryText = null;
				this.model = null;
				return this;

			}

			/**
			 * Sets the text query used for a MongoDB Automated Embedding vector index.
			 * <p>This renders the official {@code $vectorSearch.query} field. Use
			 * {@link #queryVector(Collection)} for application-provided vectors.</p>
			 *
			 * @param query
			 *            the source text that should be embedded by MongoDB
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> query(
				String query
			) {

				if (query == null || query.isBlank()) {
					throw new IllegalArgumentException( "query must not be blank" );

				}

				this.queryText = query;
				this.driverQuery = null;
				this.driverBinaryVector = null;
				this.queryVector = null;
				return this;

			}

			/**
			 * Sets a MongoDB driver-native vector search query. This keeps the application-level
			 * DSL usable when the driver adds a new query type before this library adds a
			 * convenience overload.
			 *
			 * @param query
			 *            the driver-native vector search query
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> query(
				VectorSearchQuery query
			) {

				this.driverQuery = Objects.requireNonNull( query, "query" );
				this.queryText = null;
				this.driverBinaryVector = null;
				this.queryVector = null;
				this.model = null;
				return this;

			}

			/**
			 * Sets the optional embedding model override for MongoDB Automated Embedding
			 * text queries. This option is valid only with {@link #query(String)}.
			 *
			 * @param model
			 *            the automated embedding model name
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> model(
				String model
			) {

				if (model == null || model.isBlank()) {
					throw new IllegalArgumentException( "model must not be blank" );

				}

				this.model = model;
				return this;

			}

			/**
			 * Applies an advanced MongoDB driver-native vector search option customizer after
			 * the convenience options have been assembled.
			 *
			 * @param customizer
			 *            the driver option customizer
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> driverOptions(
				Function<VectorSearchOptions, VectorSearchOptions> customizer
			) {

				this.driverOptionCustomizers.add( Objects.requireNonNull( customizer, "customizer" ) );
				return this;

			}

			/**
			 * Adds a driver-native aggregation stage immediately after {@code $vectorSearch}.
			 * <p>The stage runs before post-vector {@code fields(...)} criteria, metadata
			 * additions, and projection.</p>
			 *
			 * @param stage
			 *            the driver-native aggregation stage
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> stage(
				Bson stage
			) {

				this.stages.add( Objects.requireNonNull( stage, "stage" ) );
				return this;

			}

			/** Adds driver-native aggregation stages immediately after {@code $vectorSearch}. */
			public VectorSearchBuilder<S> stages(
				Bson... stages
			) {

				return stages( Arrays.asList( Objects.requireNonNull( stages, "stages" ) ) );

			}

			/** Adds driver-native aggregation stages immediately after {@code $vectorSearch}. */
			public VectorSearchBuilder<S> stages(
				Collection<? extends Bson> stages
			) {

				Objects.requireNonNull( stages, "stages" ).forEach( this::stage );
				return this;

			}

			/**
			 * Sets the maximum number of documents to return.
			 *
			 * @param limit
			 *            the result limit
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> limit(
				long limit
			) {

				if (limit <= 0L) {
					throw new IllegalArgumentException( "limit must be > 0" );

				}

				this.limit = limit;
				return this;

			}

			/**
			 * Configures an ANN search by specifying {@code numCandidates}.
			 *
			 * @param numCandidates
			 *            the candidate count used for ANN
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> numCandidates(
				long numCandidates
			) {

				if (numCandidates <= 0L) {
					throw new IllegalArgumentException( "numCandidates must be > 0" );

				}

				this.numCandidates = numCandidates;
				this.exact = false;
				return this;

			}

			/**
			 * Sets whether to run an exact nearest-neighbor (ENN) search.
			 *
			 * @param exact
			 *            whether exact search should be used
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> exact(
				boolean exact
			) {

				this.exact = exact;

				if (exact) {
					this.numCandidates = null;

				}

				return this;

			}

			/**
			 * Convenience method for exact nearest-neighbor (ENN) search.
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> exact() {

				return exact( true );

			}

			/**
			 * Convenience method for ANN search with the given candidate count.
			 *
			 * @param numCandidates
			 *            the candidate count used for ANN
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> approximate(
				long numCandidates
			) {

				return numCandidates( numCandidates );

			}

			/**
			 * Adds MongoDB Query Language pre-filters that are rendered into the
			 * {@code filter} field inside {@code $vectorSearch}.
			 *
			 * @param fieldsPairs
			 *            the pre-filter field conditions
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> filterFields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				this.preFilterBuilder.fields( fieldsPairs );
				return this;

			}

			/**
			 * Adds MongoDB Query Language pre-filters that are rendered into the
			 * {@code filter} field inside {@code $vectorSearch}.
			 *
			 * @param fieldsPairs
			 *            the pre-filter field conditions
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> filterFields(
				Collection<FieldsPair<?, ?>> fieldsPairs
			) {

				if (fieldsPairs == null || fieldsPairs.isEmpty())
					return this;

				this.preFilterBuilder.fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );
				return this;

			}

			/**
			 * Allows callers to compose nested AND/OR/NOR pre-filters using the regular
			 * {@link FieldBuilder}.
			 *
			 * @param block
			 *            the pre-filter composition callback
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> filter(
				Consumer<FieldBuilder<E>> block
			) {

				if (block != null) {
					block.accept( this.preFilterBuilder );

				}

				return this;

			}

			/**
			 * Adds root-document filters for a nested {@code $vectorSearch}.
			 * <p>Unlike {@link #filter(Consumer)}, which maps to the Driver's leaf
			 * {@code filter} option, this maps to the Driver 5.10+
			 * {@code parentFilter} option. MongoDB server support for nested vector
			 * search is required.</p>
			 *
			 * @param fieldsPairs
			 *            the parent-document field conditions
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> parentFilterFields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				this.parentFilterBuilder.fields( fieldsPairs );
				return this;

			}

			/** Adds root-document filters for a nested {@code $vectorSearch}. */
			public VectorSearchBuilder<S> parentFilterFields(
				Collection<FieldsPair<?, ?>> fieldsPairs
			) {

				if (fieldsPairs == null || fieldsPairs.isEmpty())
					return this;

				this.parentFilterBuilder.fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );
				return this;

			}

			/**
			 * Composes root-document filters for a nested {@code $vectorSearch} with
			 * the regular {@link FieldBuilder}.
			 */
			public VectorSearchBuilder<S> parentFilter(
				Consumer<FieldBuilder<E>> block
			) {

				if (block != null) {
					block.accept( this.parentFilterBuilder );

				}

				return this;

			}

			/**
			 * Sets how scores from matching embeddings inside a nested document are
			 * combined. This maps directly to MongoDB Driver 5.10+
			 * {@link VectorSearchNestedOptions#scoreMode(VectorSearchScoreMode)}.
			 *
			 * @param scoreMode
			 *            the nested vector score mode
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> nestedScoreMode(
				VectorSearchScoreMode scoreMode
			) {

				this.nestedScoreMode = Objects.requireNonNull( scoreMode, "scoreMode" );
				return this;

			}

			/**
			 * Adds post-vector-search filters rendered as a normal aggregation
			 * {@code $match}.
			 *
			 * @param fieldsPairs
			 *            the post-stage field conditions
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> fields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				this.postFilterBuilder.fields( fieldsPairs );
				return this;

			}

			/**
			 * Adds post-vector-search filters rendered as a normal aggregation
			 * {@code $match}.
			 *
			 * @param fieldsPairs
			 *            the post-stage field conditions
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> fields(
				Collection<FieldsPair<?, ?>> fieldsPairs
			) {

				if (fieldsPairs == null || fieldsPairs.isEmpty())
					return this;

				this.postFilterBuilder.fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );
				return this;

			}

			/**
			 * Adds a post-stage field exposing the vector-search similarity score using the
			 * default alias {@code vectorSearchScore}.
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> addFieldsVectorSearchScore() {

				return addFieldsVectorSearchScore( "vectorSearchScore" );

			}

			/**
			 * Adds a post-stage field exposing the vector-search similarity score using the
			 * given alias.
			 *
			 * @param alias
			 *            the target field alias
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> addFieldsVectorSearchScore(
				String alias
			) {

				this.addFieldsDocs.add( MongoBsonSupport.toDocument( Projections.metaVectorSearchScore( alias ) ) );
				return this;

			}

			/**
			 * Excludes the given fields from the final mapped result projection.
			 *
			 * @param excludes
			 *            the field names to exclude
			 *
			 * @return this builder
			 */
			public VectorSearchBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes );
				return this;

			}

			/**
			 * Creates a terminal builder for multi-result vector-search reads.
			 *
			 * @return a multi-result vector-search terminal builder
			 */
			public VectorFindAllQueryBuilder<S> findAll() {

				return new VectorFindAllQueryBuilder<>();

			}

			/**
			 * Creates a terminal builder for single-result vector-search reads.
			 *
			 * @return a single-result vector-search terminal builder
			 */
			public VectorFindQueryBuilder<S> find() {

				return new VectorFindQueryBuilder<>();

			}

			/**
			 * Creates a terminal builder for vector-search count reads.
			 * <p>This count reflects the pipeline output after {@code $vectorSearch} and
			 * therefore counts only the documents returned by the configured stage limit and
			 * post-stage filters. It is <strong>not</strong> a corpus-wide metadata count.</p>
			 *
			 * @return a vector-search count terminal builder
			 */
			public VectorCountQueryBuilder count() {

				return new VectorCountQueryBuilder();

			}

			/**
			 * Creates a terminal builder for vector-search existence checks.
			 *
			 * @return a vector-search exists terminal builder
			 */
			public VectorExistsQueryBuilder existsQuery() {

				return new VectorExistsQueryBuilder();

			}

			private void validateVectorSearchBody() {

				if (this.index == null || this.index.isBlank()) {
					throw new IllegalStateException( "vectorSearch.index is required" );

				}

				if (this.path == null) {
					throw new IllegalStateException( "vectorSearch.path is required" );

				}

				if (this.limit == null || this.limit <= 0L) {
					throw new IllegalStateException( "vectorSearch.limit is required" );

				}

				int queryModes = (this.queryVector == null ? 0 : 1) + (this.driverBinaryVector == null ? 0 : 1) + (this.queryText == null || this.queryText.isBlank() ? 0
					: 1) + (this.driverQuery == null ? 0 : 1);

				if (queryModes == 0) {
					throw new IllegalStateException( "vectorSearch.queryVector or vectorSearch.query is required" );

				}

				if (queryModes > 1) {
					throw new IllegalStateException( "Only one vector search query mode can be configured" );

				}

				if ((this.queryVector != null || this.driverBinaryVector != null || this.driverQuery != null) && this.model != null && ! this.model.isBlank()) {
					throw new IllegalStateException( "vectorSearch.model can be used only with query(String)" );

				}

				if (Boolean.TRUE.equals( this.exact ) && this.numCandidates != null) {
					throw new IllegalStateException( "vectorSearch.exact=true and numCandidates cannot be used together" );

				}

				if (! Boolean.TRUE.equals( this.exact ) && this.numCandidates == null) {
					throw new IllegalStateException( "vectorSearch.numCandidates is required for ANN search" );

				}

			}

			private VectorSearchOptions buildVectorSearchOptions(
				Optional<Bson> preFilterCriteria, Optional<Bson> parentFilterCriteria
			) {

				VectorSearchOptions options = Boolean.TRUE.equals( this.exact )
					? VectorSearchOptions.exactVectorSearchOptions()
					: VectorSearchOptions.approximateVectorSearchOptions( this.numCandidates );

				if (preFilterCriteria.isPresent()) {
					options = options.filter( preFilterCriteria.get() );

				}

				if (parentFilterCriteria.isPresent()) {
					options = options.parentFilter( parentFilterCriteria.get() );

				}

				if (this.nestedScoreMode != null) {
					options = options
						.nestedOptions(
							VectorSearchNestedOptions
								.vectorSearchNestedOptions()
								.scoreMode( this.nestedScoreMode )
						);

				}

				for (Function<VectorSearchOptions, VectorSearchOptions> customizer : this.driverOptionCustomizers) {
					options = Objects.requireNonNull( customizer.apply( options ), "driver vector option customizer result" );

				}

				return options;

			}

			private Bson buildVectorSearchStage(
				Optional<Bson> preFilterCriteria, Optional<Bson> parentFilterCriteria
			) {

				validateVectorSearchBody();
				VectorSearchOptions options = buildVectorSearchOptions( preFilterCriteria, parentFilterCriteria );

				if (this.queryVector != null) {
					return Aggregates
						.vectorSearch(
							this.path,
							this.queryVector,
							this.index,
							this.limit,
							options
						);

				}

				if (this.driverBinaryVector != null) {
					return Aggregates.vectorSearch( this.path, this.driverBinaryVector, this.index, this.limit, options );

				}

				if (this.driverQuery != null) {
					return Aggregates.vectorSearch( this.path, this.driverQuery, this.index, this.limit, options );

				}

				TextVectorSearchQuery query = VectorSearchQuery.textQuery( this.queryText );

				if (this.model != null && ! this.model.isBlank()) {
					query = query.model( this.model );

				}

				return Aggregates.vectorSearch( this.path, query, this.index, this.limit, options );

			}

			private List<Bson> buildAggregationOps(
				Optional<Bson> preFilterCriteria, Optional<Bson> parentFilterCriteria, Optional<Bson> postFilterCriteria, boolean includeProjection, boolean includeMetaAdds
			) {

				List<Bson> ops = new ArrayList<>();

				ops.add( buildVectorSearchStage( preFilterCriteria, parentFilterCriteria ) );

				ops.addAll( this.stages );

				postFilterCriteria.ifPresent( criteria -> ops.add( Aggregates.match( criteria ) ) );

				if (includeMetaAdds && ! this.addFieldsDocs.isEmpty()) {
					Document addFields = new Document();

					for (Document d : this.addFieldsDocs) {

						for (Map.Entry<String, Object> entry : d.entrySet()) {
							addFields.append( entry.getKey(), entry.getValue() );

						}

					}

					ops
						.add(
							Aggregates
								.addFields(
									addFields
										.entrySet()
										.stream()
										.map( entry -> new com.mongodb.client.model.Field<>( entry.getKey(), entry.getValue() ) )
										.toArray( com.mongodb.client.model.Field<?>[]::new )
								)
						);

				}

				if (includeProjection && this.excludes != null && this.excludes.length > 0) {
					ops.add( Aggregates.project( Projections.exclude( this.excludes ) ) );

				}

				return ops;

			}

			private Flux<Document> aggregateDocuments(
				Class<?> entityClass, List<Bson> ops
			) {

				return ReactiveMongoDsl.this
					.aggregateDocuments(
						mongoExecutionContext,
						entityClass,
						collectionName,
						applyAggOptions( ops )
					);

			}

			/**
			 * Terminal builder for multi-result vector-search reads.
			 *
			 * @param <T>
			 *            the current mapped entity type
			 */
			public class VectorFindAllQueryBuilder<T extends E> {

				/**
				 * Executes the vector-search query and maps all returned documents to the
				 * current entity type.
				 *
				 * @return a {@link Flux} emitting the mapped vector-search results
				 */
				public Flux<E> execute() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMapMany( tuple -> {
							Class<E> entityClass = tuple.getT1();
							Optional<Bson> preCriteria = tuple.getT2();
							Optional<Bson> parentCriteria = tuple.getT3();
							Optional<Bson> postCriteria = tuple.getT4();
							List<Bson> ops = buildAggregationOps( preCriteria, parentCriteria, postCriteria, true, true );
							return aggregateDocuments( entityClass, ops )
								.map( doc -> mongoExecutionContext.read( entityClass, doc ) );

						} );

				}

				public Mono<Document> preview() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.map(
							tuple -> previewAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), tuple.getT3(), tuple.getT4(), true, true ) )
							)
						);

				}

				public Mono<Document> explain() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMap(
							tuple -> explainAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), tuple.getT3(), tuple.getT4(), true, true ) )
							)
						);

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMap(
							tuple -> explainAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								applyAggOptions( buildAggregationOps( tuple.getT2(), tuple.getT3(), tuple.getT4(), true, true ) ),
								verbosity
							)
						);

				}

			}

			/**
			 * Terminal builder for single-result vector-search reads.
			 *
			 * @param <T>
			 *            the current mapped entity type
			 */
			public class VectorFindQueryBuilder<T extends E> {

				/**
				 * Executes the vector-search query and returns at most one mapped entity.
				 *
				 * @return a {@link Mono} emitting the mapped vector-search result
				 */
				public Mono<E> execute() {

					return executeFirst();

				}

				/**
				 * Executes the vector-search query and returns the first mapped entity.
				 *
				 * @return a {@link Mono} emitting the first mapped vector-search result
				 */
				public Mono<E> executeFirst() {

					return new VectorFindAllQueryBuilder<T>().execute().next();

				}

				public Mono<Document> preview() {

					return new VectorFindAllQueryBuilder<T>().preview().map( preview -> preview.append( "first", true ) );

				}

				public Mono<Document> explain() {

					return new VectorFindAllQueryBuilder<T>().explain();

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return new VectorFindAllQueryBuilder<T>().explain( verbosity );

				}

			}

			/**
			 * Terminal builder for vector-search count reads.
			 */
			public class VectorCountQueryBuilder {

				/**
				 * Counts the documents returned by the current vector-search pipeline.
				 *
				 * @return a {@link Mono} emitting the limited pipeline result count
				 */
				public Mono<Long> execute() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMap( tuple -> {
							Class<E> entityClass = tuple.getT1();
							Optional<Bson> preCriteria = tuple.getT2();
							Optional<Bson> parentCriteria = tuple.getT3();
							Optional<Bson> postCriteria = tuple.getT4();

							List<Bson> ops = buildAggregationOps( preCriteria, parentCriteria, postCriteria, false, false );
							ops.add( Aggregates.count( "count" ) );

							return aggregateDocuments( entityClass, ops )
								.next()
								.map( d -> Optional.ofNullable( d.get( "count", Number.class ) ).map( Number::longValue ).orElse( 0L ) )
								.defaultIfEmpty( 0L );

						} );

				}

				private AggregationSpec buildCountAggregation(
					Optional<Bson> preCriteria, Optional<Bson> parentCriteria, Optional<Bson> postCriteria
				) {

					List<Bson> ops = buildAggregationOps( preCriteria, parentCriteria, postCriteria, false, false );
					ops.add( Aggregates.count( "count" ) );
					return applyAggOptions( ops );

				}

				public Mono<Document> preview() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.map( tuple -> previewAggregation( mongoExecutionContext, tuple.getT1(), collectionName, buildCountAggregation( tuple.getT2(), tuple.getT3(), tuple.getT4() ) ) );

				}

				public Mono<Document> explain() {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMap( tuple -> explainAggregation( mongoExecutionContext, tuple.getT1(), collectionName, buildCountAggregation( tuple.getT2(), tuple.getT3(), tuple.getT4() ) ) );

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return Mono
						.zip( executeClassMono, preFilterBuilder.buildCriteria(), parentFilterBuilder.buildCriteria(), postFilterBuilder.buildCriteria() )
						.flatMap(
							tuple -> explainAggregation(
								mongoExecutionContext,
								tuple.getT1(),
								collectionName,
								buildCountAggregation( tuple.getT2(), tuple.getT3(), tuple.getT4() ),
								verbosity
							)
						);

				}

			}

			/**
			 * Terminal builder for vector-search existence checks.
			 */
			public class VectorExistsQueryBuilder {

				/**
				 * Returns whether the vector-search query yields at least one pipeline result.
				 *
				 * @return a {@link Mono} emitting {@code true} when at least one result exists
				 */
				public Mono<Boolean> execute() {

					return count().execute().map( count -> count > 0L );

				}

				public Mono<Document> preview() {

					return count().preview();

				}

				public Mono<Document> explain() {

					return count().explain();

				}

				public Mono<Document> explain(
					ExplainVerbosity verbosity
				) {

					return count().explain( verbosity );

				}

			}

		}


		/**
		 * Builder for multi-result queries with optional sorting, paging, field exclusion,
		 * lookup joins, and aggregation-based page counting.
		 *
		 * @param <S>
		 *            the current entity type
		 */
		public class FindAllQueryBuilder<S extends E> extends QueryBuilderAccesser<FindAllExecute<E>, FindAllAggregation<E>> implements FindAllExecute<E>, FindAllAggregation<E> {


			private Paging paging;

			private Bson sort;

			private String[] excludes = null;


			@Override
			public FindAllQueryBuilder<S> readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			@Override
			public FindAllQueryBuilder<S> isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			/**
			 * Starts paging configuration for this query.
			 * <p>The existing {@code pageNumber(...).pageSize(...).and()} flow remains the
			 * ordinary page-number paging API. Cursor strategies are selected explicitly
			 * through {@code pageNumberCursor(...)} or {@code cursor(...)} on the returned builder.</p>
			 *
			 * @return a paging helper and strategy selector
			 */
			public PageBuilder paging() {

				return new PageBuilder();

			}

			/**
			 * Configures zero-based paging for this query.
			 *
			 * @param pageNumber
			 *            the zero-based page index
			 * @param pageSize
			 *            the page size
			 * 
			 * @return this builder
			 */
			public FindAllQueryBuilder<S> paging(
				Integer pageNumber, Integer pageSize
			) {

				return new PageBuilder().and( pageNumber, pageSize );

			}


			/**
			 * Starts ordered sorting for this query.
			 * <p>Driver-native sort definitions belong inside {@link SortSpec#driver(Bson)} so
			 * every sort path uses the same ordered fluent DSL.</p>
			 *
			 * @return the ordered sort DSL
			 */
			public SortSpec<FindAllQueryBuilder<S>> sorts() {

				return new SortSpec<FindAllQueryBuilder<S>>( this ) {

					@Override
					protected void apply() {

						FindAllQueryBuilder.this.sort = isEmpty() ? null : this;

					}

				};

			}

			/**
			 * Configures ordered sorting in one callback and returns this query builder.
			 *
			 * @param spec
			 *            the ordered sort configuration
			 *
			 * @return this builder
			 */
			public FindAllQueryBuilder<S> sorts(
				Consumer<SortSpec<FindAllQueryBuilder<S>>> spec
			) {

				SortSpec<FindAllQueryBuilder<S>> sort = sorts();
				Objects.requireNonNull( spec, "spec" ).accept( sort );
				return sort.end();

			}

			public FindAllQueryBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes );
				return this;

			}

			/**
			 * Excludes the given fields from the result projection.
			 *
			 * @param excludes
			 *            the field names to exclude
			 * 
			 * @return this builder
			 */
			public FindAllQueryBuilder<S> excludes(
				Collection<String> excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes.toArray( String[]::new ) );
				return this;

			}

			/**
			 * Reserves shared change-stream invalidation for this query. The returned builder emits an
			 * initial snapshot and re-runs the finite query whenever a dependency namespace changes.
			 */
			public FindAllChangeStreamReservation reservationChangeStream() {

				return new FindAllChangeStreamReservation();

			}

			public final class FindAllChangeStreamReservation {

				private final List<ReservationDependency> dependencies = new ArrayList<>();

				private Duration coalesce = Duration.ofMillis( 50 );

				public FindAllChangeStreamReservation watch(
					Class<?> entityClass
				) {

					dependencies.add( new ReservationDependency( mongoExecutionContext, mongoExecutionContext.getCollectionName( entityClass ) ) );
					return this;

				}

				public FindAllChangeStreamReservation watch(
					K key, Class<?> entityClass
				) {

					MongoExecutionContext context = getMongoTemplate( key );
					dependencies.add( new ReservationDependency( context, context.getCollectionName( entityClass ) ) );
					return this;

				}

				public FindAllChangeStreamReservation watch(
					K key, String collectionName
				) {

					dependencies.add( new ReservationDependency( getMongoTemplate( key ), Objects.requireNonNull( collectionName, "collectionName must not be null" ) ) );
					return this;

				}

				public FindAllChangeStreamReservation coalesce(
					Duration duration
				) {

					if (duration == null || duration.isNegative())
						throw new IllegalArgumentException( "coalesce duration must be >= 0" );
					this.coalesce = duration;
					return this;

				}

				public Flux<ChangeStreamDocument<Document>> changes() {

					return prepareChanges( List.of() ).thenMany( coalesce( rawChanges( List.of() ) ) );

				}

				public Flux<ChangeStreamDocument<Document>> invalidations() {

					return changes();

				}

				public Flux<List<E>> execute() {

					return refreshSnapshots(
						prepareChanges( List.of() ),
						coalesce( rawChanges( List.of() ) ),
						FindAllQueryBuilder.this.execute().collectList()
					);

				}

				public <R2> Flux<List<ResultTuple<E, List<R2>>>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				) {

					return refreshSnapshots(
						prepareLookupChanges( rightBuilder ),
						coalesce( Flux.merge( rawChanges( List.of() ), lookupReservationChanges( rightBuilder, spec ) ) ),
						FindAllQueryBuilder.this.executeLookup( rightBuilder, spec ).collectList()
					);

				}

				private <V> Flux<List<V>> refreshSnapshots(
					Mono<Void> preparation, Flux<ChangeStreamDocument<Document>> changes, Mono<List<V>> query
				) {

					return preparation.thenMany(
						Flux
							.concat( Mono.just( 0L ), changes.map( ignored -> 1L ) )
							.switchMap( ignored -> Mono.defer( () -> query ) )
					);

				}

				private Mono<Void> prepareChanges(
					Collection<ReservationDependency> additional
				) {

					List<MongoExecutionContext> contexts = new ArrayList<>();
					contexts.add( mongoExecutionContext );
					for (ReservationDependency dependency : dependencies)
						contexts.add( dependency.context() );
					for (ReservationDependency dependency : additional)
						contexts.add( dependency.context() );
					return Flux
						.fromIterable( contexts )
						.distinct( MongoExecutionContext::getSessionScope )
						.concatMap( changeStreamHub::prepare )
						.then();

				}

				private <R2> Mono<Void> prepareLookupChanges(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder
				) {

					return prepareChanges( List.of( new ReservationDependency( rightBuilder.getMongoExecutionContext(), "" ) ) );

				}

				private Flux<ChangeStreamDocument<Document>> rawChanges(
					Collection<ReservationDependency> additional
				) {

					Flux<ChangeStreamDocument<Document>> own = executeClassMono.flatMapMany( entityClass -> {
						String ownCollection = ReactiveMongoDsl.this.resolveCollectionName( mongoExecutionContext, entityClass, collectionName );
						return changeStreamHub.watchCollection( mongoExecutionContext, ownCollection );

					} );
					List<Flux<ChangeStreamDocument<Document>>> streams = new ArrayList<>();
					streams.add( own );
					for (ReservationDependency dependency : dependencies)
						streams.add( changeStreamHub.watchCollection( dependency.context(), dependency.collectionName() ) );
					for (ReservationDependency dependency : additional)
						streams.add( changeStreamHub.watchCollection( dependency.context(), dependency.collectionName() ) );
					return Flux.merge( streams );

				}

				private <R2> Flux<ChangeStreamDocument<Document>> lookupReservationChanges(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				) {

					return rightBuilder.getExecuteClassMono().flatMapMany( rightClass -> {
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						return Flux
							.merge(
								lookupDependencyCollections( rightCollection, spec )
									.stream()
									.map( collection -> changeStreamHub.watchCollection( mongoExecutionContext, collection ) )
									.toList()
							);

					} );

				}

				private Flux<ChangeStreamDocument<Document>> coalesce(
					Flux<ChangeStreamDocument<Document>> source
				) {

					if (coalesce.isZero())
						return source;
					return source
						.bufferTimeout( 1024, coalesce )
						.filter( values -> ! values.isEmpty() )
						.map( values -> values.get( values.size() - 1 ) );

				}

				private record ReservationDependency(MongoExecutionContext context, String collectionName) {}

			}

			/**
			 * Paging helper that preserves the existing page-number/page-size API and also
			 * exposes explicit cursor strategy entry points.
			 */
			public class PageBuilder {

				private Integer pageNumber;

				private Integer pageSize;

				/**
				 * Sets the zero-based page number.
				 *
				 * @param pageNumber
				 *            the zero-based page index
				 * 
				 * @return this builder
				 */
				public PageBuilder pageNumber(
					int pageNumber
				) {

					this.pageNumber = pageNumber;
					return this;

				}

				/**
				 * Sets the page size.
				 *
				 * @param pageSize
				 *            the page size
				 * 
				 * @return this builder
				 */
				public PageBuilder pageSize(
					int pageSize
				) {

					this.pageSize = pageSize;
					return this;

				}

				/**
				 * Selects page-number cursor paging while keeping page-number navigation semantics.
				 *
				 * @return a page-number cursor paging builder
				 */
				public PageNumberCursorPagingBuilder pageNumberCursor() {

					return new PageNumberCursorPagingBuilder();

				}

				/** Selects page-number cursor paging with the given page number and size. */
				public PageNumberCursorPagingBuilder pageNumberCursor(
					int pageNumber, int pageSize
				) {

					return pageNumberCursor().pageNumber( pageNumber ).pageSize( pageSize );

				}

				/**
				 * Selects page-number-free opaque cursor paging.
				 *
				 * @return an opaque cursor paging builder
				 */
				public CursorPagingBuilder cursor() {

					return new CursorPagingBuilder();

				}

				/** Selects page-number-free opaque cursor paging with the given page size. */
				public CursorPagingBuilder cursor(
					int pageSize
				) {

					return cursor().pageSize( pageSize );

				}

				/**
				 * Finalizes paging configuration using the given values and returns the parent query builder.
				 *
				 * @param pageNumber
				 *            the zero-based page index
				 * @param pageSize
				 *            the page size
				 * 
				 * @return the parent query builder
				 */
				public FindAllQueryBuilder<S> and(
					Integer pageNumber, Integer pageSize
				) {

					if (pageNumber == null || pageSize == null) { throw new IllegalArgumentException( "Both pageNumber and pageSize must be specified." ); }

					if (pageNumber < 0 || pageSize <= 0) { throw new IllegalArgumentException( "Invalid pageNumber or pageSize." ); }

					paging = new Paging( pageNumber, pageSize );
					return FindAllQueryBuilder.this;

				}

				/**
				 * Finalizes paging configuration using the values previously set on this builder.
				 *
				 * @return the parent query builder
				 */
				public FindAllQueryBuilder<S> and() {

					if (pageNumber == null || pageSize == null) { throw new IllegalArgumentException( "Both pageNumber and pageSize must be specified." ); }

					if (pageNumber < 0 || pageSize <= 0) { throw new IllegalArgumentException( "Invalid pageNumber or pageSize." ); }

					paging = new Paging( pageNumber, pageSize );
					return FindAllQueryBuilder.this;

				}

			}


			/**
			 * Typed paging strategy that preserves page-number navigation while using
			 * store-backed cursor anchors to bound repeated deep-page skips.
			 */
			public final class PageNumberCursorPagingBuilder {

				private Integer pageNumber;

				private Integer pageSize;

				private long maxRelativeSkip = cursorAnchorStore.cursorCacheOptions().maxRelativeSkip();

				private CursorSkipExceededAction skipExceededAction = cursorAnchorStore.cursorCacheOptions().skipExceededAction();

				public PageNumberCursorPagingBuilder pageNumber(
					int pageNumber
				) {

					if (pageNumber < 0)
						throw new IllegalArgumentException( "pageNumber must be >= 0" );
					this.pageNumber = pageNumber;
					return this;

				}

				public PageNumberCursorPagingBuilder pageSize(
					int pageSize
				) {

					validateCursorPageSize( pageSize );
					this.pageSize = pageSize;
					return this;

				}

				/** Starts the relative-skip safety policy for this page-number cursor query. */
				public SkipPolicyBuilder skipPolicy() {

					return new SkipPolicyBuilder();

				}

				/** Configures the relative-skip safety policy in one callback. */
				public PageNumberCursorPagingBuilder skipPolicy(
					Consumer<SkipPolicyBuilder> policy
				) {

					SkipPolicyBuilder builder = skipPolicy();
					Objects.requireNonNull( policy, "policy must not be null" ).accept( builder );
					return builder.end();

				}

				public Flux<E> execute() {

					return executePageNumberCursor( requirePaging(), maxRelativeSkip, skipExceededAction );

				}

				public PageStream<E> executePageStream() {

					return executePageNumberCursorStream( requirePaging(), maxRelativeSkip, skipExceededAction );

				}

				public <R2> Flux<ResultTuple<E, List<R2>>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				) {

					return executeLookupPageNumberCursor( rightBuilder, spec, requirePaging(), maxRelativeSkip, skipExceededAction );

				}

				public <R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupAndCount(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				) {

					return executeLookupPageNumberCursorAndCount( rightBuilder, spec, requirePaging(), maxRelativeSkip, skipExceededAction );

				}


				/** Reserves shared Change Stream invalidation for this page-number cursor query. */
				public PageNumberCursorChangeStreamReservation reservationChangeStream() {

					return new PageNumberCursorChangeStreamReservation();

				}

				public final class PageNumberCursorChangeStreamReservation {

					private final FindAllChangeStreamReservation delegate = new FindAllChangeStreamReservation();

					public PageNumberCursorChangeStreamReservation watch(
						Class<?> entityClass
					) {

						delegate.watch( entityClass );
						return this;

					}

					public PageNumberCursorChangeStreamReservation watch(
						K key, Class<?> entityClass
					) {

						delegate.watch( key, entityClass );
						return this;

					}

					public PageNumberCursorChangeStreamReservation watch(
						K key, String collectionName
					) {

						delegate.watch( key, collectionName );
						return this;

					}

					public PageNumberCursorChangeStreamReservation coalesce(
						Duration duration
					) {

						delegate.coalesce( duration );
						return this;

					}

					public Flux<ChangeStreamDocument<Document>> changes() {

						return delegate.changes();

					}

					public Flux<ChangeStreamDocument<Document>> invalidations() {

						return changes();

					}

					public Flux<List<E>> execute() {

						return delegate.refreshSnapshots(
							delegate.prepareChanges( List.of() ),
							delegate.coalesce( delegate.rawChanges( List.of() ) ),
							PageNumberCursorPagingBuilder.this.execute().collectList()
						);

					}

					public <R2> Flux<List<ResultTuple<E, List<R2>>>> executeLookup(
						ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
					) {

						return delegate.refreshSnapshots(
							delegate.prepareLookupChanges( rightBuilder ),
							delegate.coalesce( Flux.merge( delegate.rawChanges( List.of() ), delegate.lookupReservationChanges( rightBuilder, spec ) ) ),
							PageNumberCursorPagingBuilder.this.executeLookup( rightBuilder, spec ).collectList()
						);

					}

				}

				private Paging requirePaging() {

					if (pageNumber == null || pageSize == null)
						throw new IllegalStateException( "pageNumberCursor requires both pageNumber and pageSize" );
					return new Paging( pageNumber, pageSize );

				}

				public final class SkipPolicyBuilder {

					private long configuredMaxRelativeSkip = maxRelativeSkip;

					private CursorSkipExceededAction configuredAction = skipExceededAction;

					/** Sets the maximum number of rows MongoDB may skip from the nearest stored anchor. */
					public SkipPolicyBuilder maxRelativeSkip(
						long maxRelativeSkip
					) {

						if (maxRelativeSkip < 0L)
							throw new IllegalArgumentException( "maxRelativeSkip must be >= 0" );
						this.configuredMaxRelativeSkip = maxRelativeSkip;
						return this;

					}

					/** Selects what happens when the configured relative-skip limit is exceeded. */
					public SkipPolicyBuilder onExceeded(
						CursorSkipExceededAction action
					) {

						this.configuredAction = Objects.requireNonNull( action, "action must not be null" );
						return this;

					}

					/** Applies the policy and returns the page-number cursor builder. */
					public PageNumberCursorPagingBuilder end() {

						maxRelativeSkip = configuredMaxRelativeSkip;
						skipExceededAction = configuredAction;
						return PageNumberCursorPagingBuilder.this;

					}

				}

			}

			/**
			 * Typed page-number-free cursor strategy. The client only receives an opaque
			 * store-backed token; no offset skip or page-number skip policy is exposed here.
			 */
			public final class CursorPagingBuilder {

				private Integer pageSize;

				private String cursor;

				public CursorPagingBuilder pageSize(
					int pageSize
				) {

					validateCursorPageSize( pageSize );
					this.pageSize = pageSize;
					return this;

				}

				/** Continues from an opaque cursor returned by the previous page. */
				public CursorPagingBuilder after(
					String cursor
				) {

					this.cursor = cursor;
					return this;

				}

				public Mono<CursorPage<E>> execute() {

					return executeTokenCursorPage( requirePageSize(), cursor );

				}

				public <R2> Mono<CursorPage<ResultTuple<E, List<R2>>>> executeLookup(
					ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				) {

					return executeLookupTokenCursorPage( rightBuilder, spec, requirePageSize(), cursor );

				}

				private int requirePageSize() {

					if (pageSize == null)
						throw new IllegalStateException( "cursor paging requires pageSize" );
					return pageSize;

				}

			}

			private class Paging {

				private final int pageNumber;

				private final int pageSize;

				public Paging(
								int pageNumber,
								int pageSize
				) {

					this.pageNumber = pageNumber;
					this.pageSize = pageSize;

				}

			}

			/**
			 * Executes the current criteria as a regular find query and returns all matching entities.
			 *
			 * @return a {@link Flux} emitting all matching entities
			 */
			private FindSpec buildFindSpec(
				Optional<Bson> criteriaOptional
			) {

				FindSpec query = new FindSpec().filter( criteriaOptional.orElseGet( Document::new ) );

				if (paging != null)
					query.skip( (long) paging.pageNumber * paging.pageSize ).limit( paging.pageSize );
				if (sort != null)
					query.sort( sort );
				if (excludes != null && excludes.length > 0)
					query.projection( Projections.exclude( excludes ) );

				return applyQueryOptions( query );

			}

			@Override
			public Flux<E> execute() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildFindSpec ) )
					.flatMapMany(
						tuple -> find( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() )
					);

			}


			private Flux<E> executePageNumberCursor(
				Paging cursorPaging, long maxRelativeSkip, CursorSkipExceededAction skipExceededAction
			) {
				validateCursorPageSize( cursorPaging.pageSize );
				if (queryCustomized)
					return Flux.error( new IllegalStateException( "pageNumberCursor paging does not support customizeQuery because cursor sort/filter semantics would be opaque." ) );

				Optional<Document> normalizedSort = CursorPaginationSupport.normalizeSort( sort );
				if (normalizedSort.isEmpty())
					return Flux.error( new IllegalStateException( "pageNumberCursor paging requires numeric ascending/descending sort fields." ) );

				Document cursorSort = normalizedSort.get();
				return Mono.zip( executeClassMono, fieldBuilder.buildCriteria() ).flatMapMany( tuple -> {
					Class<E> entityClass = tuple.getT1();
					Bson baseCriteria = tuple.getT2().orElseGet( Document::new );
					String resolvedCollection = ReactiveMongoDsl.this.resolveCollectionName( mongoExecutionContext, entityClass, collectionName );
					String projectionFingerprint = excludes == null ? "" : Arrays.toString( excludes );

					return cursorQueryKey(
						mongoExecutionContext,
						resolvedCollection,
						"find",
						baseCriteria,
						cursorSort,
						cursorPaging.pageSize,
						projectionFingerprint,
						"",
						List.of()
					)
						.flatMapMany( queryKey -> {
							long estimatedSkip = Math.multiplyExact( (long) cursorPaging.pageNumber, (long) cursorPaging.pageSize );
							return cursorAnchorStore
								.floor( queryKey, cursorPaging.pageNumber, estimatedSkip )
								.flatMapMany( anchorOptional -> {
									int anchorPageNumber = 0;
									Bson cursorCriteria = baseCriteria;

									if (anchorOptional.isPresent()) {
										CursorAnchor anchor = anchorOptional.get();
										anchorPageNumber = anchor.pageNumber();
										cursorCriteria = CursorPaginationSupport
											.combine(
												baseCriteria,
												CursorPaginationSupport.atOrAfterAnchor( cursorSort, anchor.sortValues() )
											);

									}
									CursorSkipResolution skipResolution = resolveCursorRelativeSkip(
										cursorPaging.pageNumber,
										anchorPageNumber,
										cursorPaging.pageSize,
										maxRelativeSkip,
										skipExceededAction
									);
									if (skipResolution.returnEmpty())
										return Flux.<E>empty();

									FindSpec query = new FindSpec()
										.filter( cursorCriteria )
										.sort( cursorSort )
										.skip( skipResolution.relativeSkip() )
										.limit( Math.addExact( cursorPaging.pageSize, 1 ) );
									if (excludes != null && excludes.length > 0)
										query.projection( Projections.exclude( excludes ) );
									applyQueryOptions( query );

									return findDocuments( mongoExecutionContext, entityClass, collectionName, query )
										.collectList()
										.flatMapMany(
											rows -> storeCursorAnchors( queryKey, cursorPaging.pageNumber, cursorPaging.pageSize, rows, cursorSort )
												.thenMany(
													Flux
														.fromIterable( rows.stream().limit( cursorPaging.pageSize ).toList() )
														.map( document -> mongoExecutionContext.read( entityClass, document ) )
												)
										);

								} );

						} );

				} );

			}



			private Mono<CursorPage<E>> executeTokenCursorPage(
				int pageSize, String cursor
			) {

				try {
					validateCursorPageSize( pageSize );

				} catch (RuntimeException error) {
					return Mono.error( error );

				}
				if (queryCustomized)
					return Mono.error( new IllegalStateException( "cursor paging does not support customizeQuery because cursor sort/filter semantics would be opaque." ) );

				Optional<Document> normalizedSort = CursorPaginationSupport.normalizeSort( sort );
				if (normalizedSort.isEmpty())
					return Mono.error( new IllegalStateException( "cursor paging requires numeric ascending/descending sort fields." ) );

				Document cursorSort = normalizedSort.get();
				return Mono.zip( executeClassMono, fieldBuilder.buildCriteria() ).flatMap( tuple -> {
					Class<E> entityClass = tuple.getT1();
					Bson baseCriteria = tuple.getT2().orElseGet( Document::new );
					String resolvedCollection = ReactiveMongoDsl.this.resolveCollectionName( mongoExecutionContext, entityClass, collectionName );
					String projectionFingerprint = excludes == null ? "" : Arrays.toString( excludes );

					return cursorTokenQueryKey(
						mongoExecutionContext,
						resolvedCollection,
						"find-token",
						baseCriteria,
						cursorSort,
						pageSize,
						projectionFingerprint,
						"",
						List.of()
					).flatMap( queryKey -> resolveCursorToken( queryKey, pageSize, cursor ).flatMap( tokenState -> {
						Bson cursorCriteria = baseCriteria;
						if (tokenState.isPresent())
							cursorCriteria = CursorPaginationSupport.combine(
								baseCriteria,
								CursorPaginationSupport.atOrAfterAnchor( cursorSort, tokenState.orElseThrow().sortValues() )
							);

						FindSpec query = new FindSpec()
							.filter( cursorCriteria )
							.sort( cursorSort )
							.limit( Math.addExact( pageSize, 1 ) );
						if (excludes != null && excludes.length > 0)
							query.projection( Projections.exclude( excludes ) );
						applyQueryOptions( query );

						return findDocuments( mongoExecutionContext, entityClass, collectionName, query )
							.collectList()
							.flatMap( rows -> {
								List<E> data = rows
									.stream()
									.limit( pageSize )
									.map( document -> mongoExecutionContext.read( entityClass, document ) )
									.toList();
								if (rows.size() <= pageSize)
									return Mono.just( new CursorPage<>( data, null ) );

								Document nextRow = rows.get( pageSize );
								Document nextSortValues = CursorPaginationSupport
									.anchorValues( nextRow, cursorSort )
									.orElseThrow( () -> new IllegalStateException( "cursor sort fields must be present in the projected result" ) );
								return issueCursorToken( queryKey, pageSize, nextSortValues )
									.map( nextCursor -> new CursorPage<>( data, nextCursor ) );

							} );

					} ) );

				} );

			}

			private Mono<Void> storeCursorAnchors(
				String queryKey, int pageNumber, int pageSize, List<Document> rows, Document cursorSort
			) {

				if (rows.isEmpty())
					return Mono.empty();
				List<Mono<Void>> stores = new ArrayList<>();
				CursorPaginationSupport
					.anchorValues( rows.get( 0 ), cursorSort )
					.ifPresent( values -> stores.add( cursorAnchorStore.put( queryKey, new CursorAnchor( pageNumber, values ) ) ) );
				if (rows.size() > pageSize)
					CursorPaginationSupport
						.anchorValues( rows.get( pageSize ), cursorSort )
						.ifPresent( values -> stores.add( cursorAnchorStore.put( queryKey, new CursorAnchor( pageNumber + 1, values ) ) ) );
				return stores.isEmpty() ? Mono.empty() : Mono.when( stores );

			}

			@Override
			public Mono<Document> preview() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildFindSpec ) )
					.map( tuple -> previewFind( mongoExecutionContext, tuple.getT1(), collectionName, "find", tuple.getT2() ) );

			}

			@Override
			public Mono<Document> explain() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildFindSpec ) )
					.flatMap( tuple -> explainFind( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> explain(
				ExplainVerbosity verbosity
			) {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildFindSpec ) )
					.flatMap( tuple -> explainFind( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), verbosity ) );

			}


			/**
			 * Executes the current query as a reactive page. Unlike {@link PageResult},
			 * the data side is kept as a {@link Flux}, so batch callers can keep
			 * streaming with {@code executePageStream().data().flatMap(...)}.
			 *
			 * @return a reactive page wrapper containing streamed data and total count
			 */
			@Override
			public PageStream<E> executePageStream() {

				return new PageStream<>( execute(), new CountQueryBuilder().execute() );

			}


			private PageStream<E> executePageNumberCursorStream(
				Paging cursorPaging, long maxRelativeSkip, CursorSkipExceededAction skipExceededAction
			) {

				return new PageStream<>(
					executePageNumberCursor( cursorPaging, maxRelativeSkip, skipExceededAction ),
					new CountQueryBuilder().execute()
				);

			}

			/**
			 * Builds the aggregation pipeline used by streaming aggregation reads.
			 * This deliberately avoids {@code $facet} so documents can be emitted as
			 * the cursor produces them.
			 */
			private List<Bson> buildFindAllAggregationOps(
				Optional<Bson> criteriaOptional, boolean includePaging, boolean includeProjection
			) {

				List<Bson> operations = new ArrayList<>();

				criteriaOptional.ifPresent( criteria -> operations.add( Aggregates.match( criteria ) ) );

				operations.add( Aggregates.sort( this.sort != null ? this.sort : Sorts.descending( "_id" ) ) );

				if (includePaging && paging != null) {
					operations.add( Aggregates.skip( Math.toIntExact( (long) paging.pageNumber * paging.pageSize ) ) );
					operations.add( Aggregates.limit( paging.pageSize ) );

				}

				if (includeProjection && excludes != null && excludes.length != 0) {
					operations.add( Aggregates.project( Projections.exclude( excludes ) ) );

				}

				return operations;

			}

			/**
			 * Executes the current query as an aggregation pipeline and streams mapped
			 * entities directly. This is the aggregation counterpart of {@link #execute()}
			 * for batch jobs.
			 *
			 * @return a {@link Flux} emitting aggregation results one by one
			 */
			@Override
			public Flux<E> executeAggregationStream() {

				Mono<AggregationSpec> aggregationMono = fieldBuilder
					.buildCriteria()
					.map( criteriaOptional -> applyAggOptions( buildFindAllAggregationOps( criteriaOptional, true, true ) ) );

				return Mono
					.zip( executeClassMono, aggregationMono )
					.flatMapMany(
						tuple -> aggregate( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), tuple.getT1() )
					);

			}

			/**
			 * Executes the current aggregation as a reactive page. Data and count are
			 * independent publishers, so callers can consume {@code data()} as a stream
			 * instead of waiting for a {@link PageResult}.
			 *
			 * @return a reactive page wrapper for aggregation results
			 */
			@Override
			public PageStream<E> executeAggregationPageStream() {

				return new PageStream<>( executeAggregationStream(), new CountQueryBuilder().executeAggregation() );

			}

			/**
			 * Executes the current query as an aggregation pipeline and collects the
			 * streamed page data into the legacy {@link PageResult} shape. Prefer
			 * {@link #executeAggregationStream()} or {@link #executeAggregationPageStream()}
			 * for batch jobs.
			 *
			 * @return a {@link Mono} emitting the collected aggregation result
			 */
			@Override
			public Mono<PageResult<E>> executeAggregation() {

				return executeAggregationPageStream().collectToPageResult();

			}

			/**
			 * Executes the current query with a {@code $lookup} join.
			 *
			 * @param rightBuilder
			 *            the right-side query builder used as the join target
			 * @param spec
			 *            the lookup specification
			 * @param <R2>
			 *            the right-side mapped type
			 * 
			 * @return a {@link Flux} emitting lookup tuples for each matching left-side document
			 */
			@Override
			public <R2> Flux<ResultTuple<E, List<R2>>> executeLookup(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();

				Mono<AggregationSpec> aggregationMono = Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), leftClassMono, rightClassMono )
					.map( tuple -> {
						Optional<Bson> leftCriteria = tuple.getT1();
						Optional<Bson> rightCriteria = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );

						List<Bson> operations = new ArrayList<>();
						leftCriteria.ifPresent( criteria -> operations.add( Aggregates.match( criteria ) ) );
						appendLookupStages( operations, rightCollection, rightAs, rightCriteria, spec );
						operations.add( Aggregates.sort( sort != null ? sort : Sorts.descending( "_id" ) ) );

						if (paging != null) {
							operations.add( Aggregates.skip( Math.toIntExact( (long) paging.pageNumber * paging.pageSize ) ) );
							operations.add( Aggregates.limit( paging.pageSize ) );

						}

						operations
							.add(
								Aggregates
									.project(
										new Document( LOOKUP_LEFT_RESULT_FIELD, "$$ROOT" ).append( LOOKUP_RIGHT_RESULT_FIELD, "$" + rightAs )
									)
							);

						return applyAggOptions( operations );

					} );

				return Mono.zip( leftClassMono, rightClassMono, aggregationMono ).flatMapMany( tuple -> {
					Class<E> leftClass = tuple.getT1();
					Class<R2> rightClass = tuple.getT2();
					String leftKey = simpleName( leftClass );
					String rightKey = simpleName( rightClass );

					return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, tuple.getT3() )
						.map( document -> {
							E leftValue = mongoExecutionContext.read( leftClass, document.get( LOOKUP_LEFT_RESULT_FIELD, Document.class ) );
							List<R2> rightValues = readLookupValues(
								rightBuilder.getMongoExecutionContext(),
								rightClass,
								document.get( LOOKUP_RIGHT_RESULT_FIELD )
							);
							return new ResultTuple<>( leftKey, leftValue, rightKey, rightValues );

						} );

				} );

			}

			/**
			 * Executes the current query with a {@code $lookup} join and returns paged results
			 * together with the total number of matching left-side documents.
			 *
			 * @param rightBuilder
			 *            the right-side query builder used as the join target
			 * @param spec
			 *            the lookup specification
			 * @param <R2>
			 *            the right-side mapped type
			 * 
			 * @return a {@link Mono} emitting a paged lookup result
			 */
			private <R2> Flux<ResultTuple<E, List<R2>>> executeLookupPageNumberCursor(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec,
				Paging cursorPaging, long maxRelativeSkip, CursorSkipExceededAction skipExceededAction
			) {

				Objects.requireNonNull( rightBuilder, "rightBuilder must not be null" );
				Objects.requireNonNull( spec, "spec must not be null" );
				validateCursorPageSize( cursorPaging.pageSize );
				if (aggregationCustomized)
					return Flux.error( new IllegalStateException( "pageNumberCursor lookup does not support customizeAggregation because cursor pipeline semantics would be opaque." ) );

				Optional<Document> normalizedSort = CursorPaginationSupport.normalizeSort( sort );
				if (normalizedSort.isEmpty())
					return Flux.error( new IllegalStateException( "pageNumberCursor lookup requires numeric ascending/descending sort fields." ) );
				Document cursorSort = normalizedSort.get();

				return Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), executeClassMono, rightBuilder.getExecuteClassMono() )
					.flatMapMany( tuple -> {
						Bson leftCriteria = tuple.getT1().orElseGet( Document::new );
						Bson rightCriteria = tuple.getT2().orElseGet( Document::new );
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String leftCollection = ReactiveMongoDsl.this.resolveCollectionName( mongoExecutionContext, leftClass, collectionName );
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );
						Set<String> dependencies = lookupDependencyCollections( rightCollection, spec );
						String extraFingerprint = lookupFingerprint( spec, rightCriteria, rightCollection );

						return cursorQueryKey(
							mongoExecutionContext,
							leftCollection,
							"lookup",
							leftCriteria,
							cursorSort,
							cursorPaging.pageSize,
							"",
							extraFingerprint,
							dependencies
						)
							.flatMapMany( queryKey -> {
								long estimatedSkip = Math.multiplyExact( (long) cursorPaging.pageNumber, (long) cursorPaging.pageSize );
								return cursorAnchorStore
									.floor( queryKey, cursorPaging.pageNumber, estimatedSkip )
									.flatMapMany( anchorOptional -> {
										List<Bson> operations = new ArrayList<>();
										if (! MongoBsonSupport.toDocument( leftCriteria ).isEmpty())
											operations.add( Aggregates.match( leftCriteria ) );
										appendLookupStages( operations, rightCollection, rightAs, tuple.getT2(), spec );

										int anchorPageNumber = 0;

										if (anchorOptional.isPresent()) {
											CursorAnchor anchor = anchorOptional.get();
											anchorPageNumber = anchor.pageNumber();
											operations.add( Aggregates.match( CursorPaginationSupport.atOrAfterAnchor( cursorSort, anchor.sortValues() ) ) );

										}
										CursorSkipResolution skipResolution = resolveCursorRelativeSkip(
											cursorPaging.pageNumber,
											anchorPageNumber,
											cursorPaging.pageSize,
											maxRelativeSkip,
											skipExceededAction
										);
										if (skipResolution.returnEmpty())
											return Flux.<ResultTuple<E, List<R2>>>empty();

										operations.add( Aggregates.sort( cursorSort ) );
										if (skipResolution.relativeSkip() > 0L)
											operations.add( Aggregates.skip( Math.toIntExact( skipResolution.relativeSkip() ) ) );
										operations.add( Aggregates.limit( Math.addExact( cursorPaging.pageSize, 1 ) ) );
										operations.add( Aggregates.project( new Document( LOOKUP_LEFT_RESULT_FIELD, "$$ROOT" ).append( LOOKUP_RIGHT_RESULT_FIELD, "$" + rightAs ) ) );

										return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, applyAggOptions( operations ) )
											.collectList()
											.flatMapMany(
												rows -> storeLookupCursorAnchors( queryKey, cursorPaging.pageNumber, cursorPaging.pageSize, rows, cursorSort )
													.thenMany(
														Flux
															.fromIterable( rows.stream().limit( cursorPaging.pageSize ).toList() )
															.map( document -> {
																E leftValue = mongoExecutionContext.read( leftClass, document.get( LOOKUP_LEFT_RESULT_FIELD, Document.class ) );
																List<R2> rightValues = readLookupValues( rightBuilder.getMongoExecutionContext(), rightClass, document.get( LOOKUP_RIGHT_RESULT_FIELD ) );
																return new ResultTuple<>( leftKey, leftValue, rightKey, rightValues );

															} )
													)
											);

									} );

							} );

					} );

			}



			private <R2> Mono<CursorPage<ResultTuple<E, List<R2>>>> executeLookupTokenCursorPage(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec, int pageSize, String cursor
			) {

				Objects.requireNonNull( rightBuilder, "rightBuilder must not be null" );
				Objects.requireNonNull( spec, "spec must not be null" );
				try {
					validateCursorPageSize( pageSize );

				} catch (RuntimeException error) {
					return Mono.error( error );

				}
				if (aggregationCustomized)
					return Mono.error( new IllegalStateException( "cursor lookup paging does not support customizeAggregation because cursor pipeline semantics would be opaque." ) );

				Optional<Document> normalizedSort = CursorPaginationSupport.normalizeSort( sort );
				if (normalizedSort.isEmpty())
					return Mono.error( new IllegalStateException( "cursor lookup paging requires numeric ascending/descending sort fields." ) );
				Document cursorSort = normalizedSort.get();

				return Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), executeClassMono, rightBuilder.getExecuteClassMono() )
					.flatMap( tuple -> {
						Bson leftCriteria = tuple.getT1().orElseGet( Document::new );
						Bson rightCriteria = tuple.getT2().orElseGet( Document::new );
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String leftCollection = ReactiveMongoDsl.this.resolveCollectionName( mongoExecutionContext, leftClass, collectionName );
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );
						Set<String> dependencies = lookupDependencyCollections( rightCollection, spec );
						String extraFingerprint = lookupFingerprint( spec, rightCriteria, rightCollection );

						return cursorTokenQueryKey(
							mongoExecutionContext,
							leftCollection,
							"lookup-token",
							leftCriteria,
							cursorSort,
							pageSize,
							"",
							extraFingerprint,
							dependencies
						).flatMap( queryKey -> resolveCursorToken( queryKey, pageSize, cursor ).flatMap( tokenState -> {
							List<Bson> operations = new ArrayList<>();
							if (! MongoBsonSupport.toDocument( leftCriteria ).isEmpty())
								operations.add( Aggregates.match( leftCriteria ) );
							appendLookupStages( operations, rightCollection, rightAs, tuple.getT2(), spec );
							if (tokenState.isPresent())
								operations.add( Aggregates.match( CursorPaginationSupport.atOrAfterAnchor( cursorSort, tokenState.orElseThrow().sortValues() ) ) );
							operations.add( Aggregates.sort( cursorSort ) );
							operations.add( Aggregates.limit( Math.addExact( pageSize, 1 ) ) );
							operations.add( Aggregates.project( new Document( LOOKUP_LEFT_RESULT_FIELD, "$$ROOT" ).append( LOOKUP_RIGHT_RESULT_FIELD, "$" + rightAs ) ) );

							return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, applyAggOptions( operations ) )
								.collectList()
								.flatMap( rows -> {
									List<ResultTuple<E, List<R2>>> data = rows
										.stream()
										.limit( pageSize )
										.map( document -> {
											E leftValue = mongoExecutionContext.read( leftClass, document.get( LOOKUP_LEFT_RESULT_FIELD, Document.class ) );
											List<R2> rightValues = readLookupValues( rightBuilder.getMongoExecutionContext(), rightClass, document.get( LOOKUP_RIGHT_RESULT_FIELD ) );
											return new ResultTuple<>( leftKey, leftValue, rightKey, rightValues );

										} )
										.toList();
									if (rows.size() <= pageSize)
										return Mono.just( new CursorPage<>( data, null ) );

									Document nextLeft = rows.get( pageSize ).get( LOOKUP_LEFT_RESULT_FIELD, Document.class );
									if (nextLeft == null)
										return Mono.error( new IllegalStateException( "lookup cursor result does not contain the left document" ) );
									Document nextSortValues = CursorPaginationSupport
										.anchorValues( nextLeft, cursorSort )
										.orElseThrow( () -> new IllegalStateException( "lookup cursor sort fields must be present in the left result" ) );
									return issueCursorToken( queryKey, pageSize, nextSortValues )
										.map( nextCursor -> new CursorPage<>( data, nextCursor ) );

								} );

						} ) );

					} );

			}

			private Mono<Void> storeLookupCursorAnchors(
				String queryKey, int pageNumber, int pageSize, List<Document> rows, Document cursorSort
			) {

				if (rows.isEmpty())
					return Mono.empty();
				List<Mono<Void>> stores = new ArrayList<>();
				Document first = rows.get( 0 ).get( LOOKUP_LEFT_RESULT_FIELD, Document.class );
				if (first != null)
					CursorPaginationSupport
						.anchorValues( first, cursorSort )
						.ifPresent( values -> stores.add( cursorAnchorStore.put( queryKey, new CursorAnchor( pageNumber, values ) ) ) );

				if (rows.size() > pageSize) {
					Document next = rows.get( pageSize ).get( LOOKUP_LEFT_RESULT_FIELD, Document.class );
					if (next != null)
						CursorPaginationSupport
							.anchorValues( next, cursorSort )
							.ifPresent( values -> stores.add( cursorAnchorStore.put( queryKey, new CursorAnchor( pageNumber + 1, values ) ) ) );

				}

				return stores.isEmpty() ? Mono.empty() : Mono.when( stores );

			}

			private <R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupPageNumberCursorAndCount(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec,
				Paging cursorPaging, long maxRelativeSkip, CursorSkipExceededAction skipExceededAction
			) {

				Mono<Long> countMono = Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), executeClassMono, rightBuilder.getExecuteClassMono() )
					.flatMap( tuple -> {
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						List<Bson> operations = new ArrayList<>();
						tuple.getT1().ifPresent( criteria -> operations.add( Aggregates.match( criteria ) ) );
						appendLookupStages( operations, rightCollection, rightAs, tuple.getT2(), spec );
						operations.add( Aggregates.count( "totalCount" ) );
						return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, applyAggOptions( operations ) )
							.next()
							.map( document -> Optional.ofNullable( document.get( "totalCount", Number.class ) ).map( Number::longValue ).orElse( 0L ) )
							.defaultIfEmpty( 0L );

					} );

				return Mono
					.zip( executeLookupPageNumberCursor( rightBuilder, spec, cursorPaging, maxRelativeSkip, skipExceededAction ).collectList(), countMono )
					.map( tuple -> new PageResult<>( tuple.getT1(), tuple.getT2() ) );

			}

			@Override
			public <R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupAndCount(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();

				return Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), leftClassMono, rightClassMono )
					.flatMap( tuple -> {
						Optional<Bson> leftCriteria = tuple.getT1();
						Optional<Bson> rightCriteria = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );

						List<Bson> common = new ArrayList<>();
						leftCriteria.ifPresent( criteria -> common.add( Aggregates.match( criteria ) ) );
						appendLookupStages( common, rightCollection, rightAs, rightCriteria, spec );

						List<Bson> data = new ArrayList<>( common );
						data.add( Aggregates.sort( sort != null ? sort : Sorts.descending( "_id" ) ) );

						if (paging != null) {
							data.add( Aggregates.skip( Math.toIntExact( (long) paging.pageNumber * paging.pageSize ) ) );
							data.add( Aggregates.limit( paging.pageSize ) );

						}

						data.add( Aggregates.project( new Document( LOOKUP_LEFT_RESULT_FIELD, "$$ROOT" ).append( LOOKUP_RIGHT_RESULT_FIELD, "$" + rightAs ) ) );

						List<Bson> countPipeline = new ArrayList<>( common );
						countPipeline.add( Aggregates.count( "totalCount" ) );

						Bson facet = Aggregates
							.facet(
								new Facet( "data", data ),
								new Facet( "count", countPipeline )
							);

						return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, applyAggOptions( List.of( facet ) ) )
							.next()
							.map( facetDocument -> {
								@SuppressWarnings("unchecked")
								List<Document> rows = (List<Document>) facetDocument.getOrDefault( "data", List.of() );
								List<ResultTuple<E, List<R2>>> result = rows.stream().map( row -> {
									E leftValue = mongoExecutionContext.read( leftClass, row.get( LOOKUP_LEFT_RESULT_FIELD, Document.class ) );
									List<R2> rightValues = readLookupValues(
										rightBuilder.getMongoExecutionContext(),
										rightClass,
										row.get( LOOKUP_RIGHT_RESULT_FIELD )
									);
									return new ResultTuple<>( leftKey, leftValue, rightKey, rightValues );

								} ).toList();

								@SuppressWarnings("unchecked")
								List<Document> countRows = (List<Document>) facetDocument.getOrDefault( "count", List.of() );
								long totalCount = countRows.isEmpty()
									? 0L
									: Optional.ofNullable( countRows.get( 0 ).get( "totalCount", Number.class ) ).map( Number::longValue ).orElse( 0L );
								return new PageResult<>( result, totalCount );

							} )
							.defaultIfEmpty( new PageResult<>( List.of(), 0L ) );

					} );

			}

		}

		/**
		 * Builder for single-result queries with optional sorting, field exclusion,
		 * and lookup-based aggregation support.
		 *
		 * @param <S>
		 *            the current entity type
		 */
		public class FindQueryBuilder<S extends E> extends QueryBuilderAccesser<FindExecute<E>, FindAggregation<E>> implements FindExecute<E>, FindAggregation<E> {

			private Bson sort;

			private String[] excludes;

			@Override
			public FindQueryBuilder<S> readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			@Override
			public FindQueryBuilder<S> isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			/**
			 * Starts ordered sorting for this query.
			 * <p>Driver-native sort definitions belong inside {@link SortSpec#driver(Bson)} so
			 * every sort path uses the same ordered fluent DSL.</p>
			 *
			 * @return the ordered sort DSL
			 */
			public SortSpec<FindQueryBuilder<S>> sorts() {

				return new SortSpec<FindQueryBuilder<S>>( this ) {

					@Override
					protected void apply() {

						FindQueryBuilder.this.sort = isEmpty() ? null : this;

					}

				};

			}

			/**
			 * Configures ordered sorting in one callback and returns this query builder.
			 *
			 * @param spec
			 *            the ordered sort configuration
			 *
			 * @return this builder
			 */
			public FindQueryBuilder<S> sorts(
				Consumer<SortSpec<FindQueryBuilder<S>>> spec
			) {

				SortSpec<FindQueryBuilder<S>> sort = sorts();
				Objects.requireNonNull( spec, "spec" ).accept( sort );
				return sort.end();

			}

			public FindQueryBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes );
				return this;

			}

			public FindQueryBuilder<S> excludes(
				Collection<String> excludes
			) {

				this.excludes = MongoFieldNameSupport.toMongoFields( excludes.toArray( String[]::new ) );
				return this;

			}

			private FindSpec buildFindSpec(
				Optional<Bson> criteria, boolean firstOnly
			) {

				FindSpec query = new FindSpec().filter( criteria.orElseGet( Document::new ) );
				if (sort != null)
					query.sort( sort );
				if (firstOnly)
					query.limit( 1 );
				if (excludes != null && excludes.length > 0)
					query.projection( Projections.exclude( excludes ) );
				return applyQueryOptions( query );

			}

			@Override
			public Mono<E> execute() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( criteria -> buildFindSpec( criteria, false ) ) )
					.flatMap( tuple -> findOne( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<E> executeFirst() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( criteria -> buildFindSpec( criteria, true ) ) )
					.flatMap( tuple -> findOne( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> preview() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( criteria -> buildFindSpec( criteria, false ) ) )
					.map( tuple -> previewFind( mongoExecutionContext, tuple.getT1(), collectionName, "find", tuple.getT2() ).append( "first", true ) );

			}

			@Override
			public Mono<Document> explain() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( criteria -> buildFindSpec( criteria, false ) ) )
					.flatMap( tuple -> explainFindFirst( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> explain(
				ExplainVerbosity verbosity
			) {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( criteria -> buildFindSpec( criteria, false ) ) )
					.flatMap( tuple -> explainFindFirst( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), verbosity ) );

			}

			@Override
			public Mono<E> executeAggregation() {

				Mono<AggregationSpec> aggregationMono = fieldBuilder.buildCriteria().map( criteria -> {
					List<Bson> operations = new ArrayList<>();
					criteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
					operations.add( Aggregates.sort( sort != null ? sort : Sorts.descending( "_id" ) ) );
					operations.add( Aggregates.limit( 1 ) );
					if (excludes != null && excludes.length > 0)
						operations.add( Aggregates.project( Projections.exclude( excludes ) ) );
					return applyAggOptions( operations );

				} );

				return Mono
					.zip( executeClassMono, aggregationMono )
					.flatMap(
						tuple -> aggregateDocuments( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() )
							.next()
							.map( document -> mongoExecutionContext.read( tuple.getT1(), document ) )
					);

			}

			@Override
			public <R2> Mono<ResultTuple<E, R2>> executeLookup(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();
				Mono<AggregationSpec> aggregationMono = Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), leftClassMono, rightClassMono )
					.map( tuple -> {
						Optional<Bson> leftCriteria = tuple.getT1();
						Optional<Bson> rightCriteria = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );
						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );

						List<Bson> operations = new ArrayList<>();
						leftCriteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
						appendLookupStages( operations, rightCollection, rightAs, rightCriteria, spec );
						operations.add( Aggregates.sort( sort != null ? sort : Sorts.descending( "_id" ) ) );
						operations.add( Aggregates.limit( 1 ) );
						operations.add( Aggregates.project( new Document( LOOKUP_LEFT_RESULT_FIELD, "$$ROOT" ).append( LOOKUP_RIGHT_RESULT_FIELD, "$" + rightAs ) ) );
						return applyAggOptions( operations );

					} );

				return Mono.zip( leftClassMono, rightClassMono, aggregationMono ).flatMap( tuple -> {
					Class<E> leftClass = tuple.getT1();
					Class<R2> rightClass = tuple.getT2();
					String leftKey = simpleName( leftClass );
					String rightKey = simpleName( rightClass );

					return aggregateDocuments( mongoExecutionContext, leftClass, collectionName, tuple.getT3() )
						.next()
						.map( document -> {
							E leftValue = mongoExecutionContext.read( leftClass, document.get( LOOKUP_LEFT_RESULT_FIELD, Document.class ) );
							List<R2> rightValues = readLookupValues(
								rightBuilder.getMongoExecutionContext(),
								rightClass,
								document.get( LOOKUP_RIGHT_RESULT_FIELD )
							);
							return new ResultTuple<>( leftKey, leftValue, rightKey, rightValues.isEmpty() ? null : rightValues.get( 0 ) );

						} );

				} );

			}

		}


		/**
		 * Builder for count queries with optional aggregation and lookup support.
		 */
		public class CountQueryBuilder extends QueryBuilderAccesser<CountExecute<E>, CountAggregation<E>> implements CountExecute<E>, CountAggregation<E> {

			@Override
			public CountQueryBuilder readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			@Override
			public CountQueryBuilder isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			@Override
			public Mono<Long> execute() {

				Mono<FindSpec> queryMono = fieldBuilder
					.buildCriteria()
					.map( criteria -> applyQueryOptions( new FindSpec().filter( criteria.orElseGet( Document::new ) ) ) );
				return Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> count( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> preview() {

				return Mono
					.zip(
						executeClassMono,
						fieldBuilder.buildCriteria().map( criteria -> applyQueryOptions( new FindSpec().filter( criteria.orElseGet( Document::new ) ) ) )
					)
					.map( tuple -> previewCount( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Long> executeAggregation() {

				Mono<AggregationSpec> aggregationMono = fieldBuilder.buildCriteria().map( criteria -> {
					List<Bson> operations = new ArrayList<>();
					criteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
					operations.add( Aggregates.count( "count" ) );
					return applyAggOptions( operations );

				} );

				return Mono
					.zip( executeClassMono, aggregationMono )
					.flatMap(
						tuple -> aggregateDocuments( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() )
							.singleOrEmpty()
							.map( document -> Optional.ofNullable( document.get( "count", Number.class ) ).map( Number::longValue ).orElse( 0L ) )
							.defaultIfEmpty( 0L )
					);

			}

			@Override
			public <R2> Mono<ResultTuple<Long, Long>> executeLookup(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();
				Mono<AggregationSpec> aggregationMono = Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), leftClassMono, rightClassMono )
					.map( tuple -> {
						Optional<Bson> leftCriteria = tuple.getT1();
						Optional<Bson> rightCriteria = tuple.getT2();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );

						List<Bson> operations = new ArrayList<>();
						leftCriteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
						appendLookupStages( operations, rightCollection, rightAs, rightCriteria, spec );

						if (spec.isUnwind()) {
							Document rightExists = new Document( "$ne", List.of( new Document( "$type", "$" + rightAs ), "missing" ) );
							operations
								.add(
									Aggregates
										.group(
											null,
											List
												.of(
													Accumulators.sum( "leftCount", 1 ),
													Accumulators.sum( "rightCount", new Document( "$cond", List.of( rightExists, 1, 0 ) ) )
												)
										)
								);

						} else {
							operations
								.add(
									Aggregates
										.set(
											new com.mongodb.client.model.Field<>(
												"_rightSize",
												new Document( "$size", new Document( "$ifNull", List.of( "$" + rightAs, List.of() ) ) )
											)
										)
								);
							operations
								.add(
									Aggregates
										.group(
											null,
											List
												.of(
													Accumulators.sum( "leftCount", 1 ),
													Accumulators.sum( "rightCount", "$_rightSize" )
												)
										)
								);

						}

						return applyAggOptions( operations );

					} );

				return Mono.zip( leftClassMono, rightClassMono, aggregationMono ).flatMap( tuple -> {
					String leftName = simpleName( tuple.getT1() );
					String rightName = simpleName( tuple.getT2() );
					return aggregateDocuments( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT3() )
						.singleOrEmpty()
						.map(
							document -> new ResultTuple<>(
								leftName,
								Optional.ofNullable( document.get( "leftCount", Number.class ) ).map( Number::longValue ).orElse( 0L ),
								rightName,
								Optional.ofNullable( document.get( "rightCount", Number.class ) ).map( Number::longValue ).orElse( 0L )
							)
						)
						.defaultIfEmpty( new ResultTuple<>( leftName, 0L, rightName, 0L ) );

				} );

			}

		}


		/**
		 * Builder for MongoDB Driver-native distinct queries on top of the current criteria.
		 *
		 * @param <R>
		 *            the decoded distinct value type
		 */
		public class DistinctQueryBuilder<R> {

			private final String field;

			private final Class<R> resultClass;

			private DistinctQueryBuilder(
											Object field,
											Class<R> resultClass
			) {

				this.field = MongoFieldNameSupport.toMongoField( Objects.requireNonNull( field, "field" ) );
				this.resultClass = Objects.requireNonNull( resultClass, "resultClass" );

			}

			/**
			 * Executes the Driver distinct query using the current criteria as its filter.
			 *
			 * @return a {@link Flux} emitting distinct values
			 */
			public Flux<R> execute() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria() )
					.flatMapMany(
						tuple -> distinct(
							mongoExecutionContext,
							tuple.getT1(),
							collectionName,
							field,
							tuple.getT2().orElseGet( Document::new ),
							resultClass
						)
					);

			}

			/**
			 * Returns a diagnostic snapshot of the distinct operation without resolving a MongoDB database.
			 *
			 * @return a local preview of the distinct field and filter
			 */
			public Mono<Document> preview() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria() )
					.map(
						tuple -> new Document( "operation", "distinct" )
							.append( "collection", resolveCollectionName( mongoExecutionContext, tuple.getT1(), collectionName ) )
							.append( "field", field )
							.append( "filter", MongoBsonSupport.toDocument( tuple.getT2().orElseGet( Document::new ) ) )
							.append( "resultClass", resultClass.getName() )
					);

			}

		}

		/**
		 * Builder for criteria-based delete operations.
		 */
		public class DeleteQueryBuilder {

			/**
			 * Deletes all documents matching the current criteria.
			 *
			 * @return a {@link Mono} emitting the delete result
			 */
			public Mono<DeleteResult> execute() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria() )
					.flatMap(
						tuple -> deleteByFilter(
							mongoExecutionContext,
							tuple.getT1(),
							collectionName,
							tuple.getT2().orElseGet( Document::new ),
							true
						)
					);

			}

		}

		/**
		 * Builder for existence checks with optional aggregation and lookup support.
		 */
		public class ExistsQueryBuilder extends QueryBuilderAccesser<ExistsExecute<E>, ExistsAggregation<E>> implements ExistsExecute<E>, ExistsAggregation<E> {

			@Override
			public ExistsQueryBuilder readPreference(
				ReadPreference rp
			) {

				super.readPreference( rp );
				return this;

			}

			@Override
			public ExistsQueryBuilder isAllowDiskUse(
				Boolean allow
			) {

				super.isAllowDiskUse( allow );
				return this;

			}

			private FindSpec buildExistsFindSpec(
				Optional<Bson> criteria
			) {

				return applyQueryOptions( new FindSpec().filter( criteria.orElseGet( Document::new ) ).limit( 1 ) );

			}

			@Override
			public Mono<Boolean> execute() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildExistsFindSpec ) )
					.flatMap( tuple -> exists( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> preview() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildExistsFindSpec ) )
					.map( tuple -> previewFind( mongoExecutionContext, tuple.getT1(), collectionName, "exists", tuple.getT2() ).append( "first", true ) );

			}

			@Override
			public Mono<Document> explain() {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildExistsFindSpec ) )
					.flatMap( tuple -> explainFindFirst( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ) );

			}

			@Override
			public Mono<Document> explain(
				ExplainVerbosity verbosity
			) {

				return Mono
					.zip( executeClassMono, fieldBuilder.buildCriteria().map( this::buildExistsFindSpec ) )
					.flatMap( tuple -> explainFindFirst( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2(), verbosity ) );

			}

			@Override
			public Mono<Boolean> executeAggregation() {

				Mono<AggregationSpec> aggregationMono = fieldBuilder.buildCriteria().map( criteria -> {
					List<Bson> operations = new ArrayList<>();
					criteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
					operations.add( Aggregates.limit( 1 ) );
					return applyAggOptions( operations );

				} );

				return Mono
					.zip( executeClassMono, aggregationMono )
					.flatMap( tuple -> aggregateDocuments( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT2() ).hasElements() );

			}

			@Override
			public <R2> Mono<ResultTuple<Boolean, Boolean>> executeLookup(
				ReactiveMongoDsl<?>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();
				Mono<AggregationSpec> aggregationMono = Mono
					.zip( fieldBuilder.buildCriteria(), rightBuilder.getFieldBuilderCriteria(), leftClassMono, rightClassMono )
					.map( tuple -> {
						Optional<Bson> leftCriteria = tuple.getT1();
						Optional<Bson> rightCriteria = tuple.getT2();
						Class<R2> rightClass = tuple.getT4();
						String rightCollection = rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank()
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );
						String rightAs = spec.getAs() != null && ! spec.getAs().isBlank() ? spec.getAs() : simpleName( rightClass );

						List<Bson> operations = new ArrayList<>();
						leftCriteria.ifPresent( filter -> operations.add( Aggregates.match( filter ) ) );
						appendLookupStages( operations, rightCollection, rightAs, rightCriteria, spec );

						Bson rightExistsExpression = spec.isUnwind()
							? new Document( "$ne", List.of( new Document( "$type", "$" + rightAs ), "missing" ) )
							: new Document(
								"$gt",
								List.of( new Document( "$size", new Document( "$ifNull", List.of( "$" + rightAs, List.of() ) ) ), 0 )
							);
						operations.add( Aggregates.project( Projections.computed( "_rightExists", rightExistsExpression ) ) );
						operations.add( Aggregates.limit( 1 ) );
						return applyAggOptions( operations ).allowDiskUse( false );

					} );

				return Mono.zip( leftClassMono, rightClassMono, aggregationMono ).flatMap( tuple -> {
					String leftName = simpleName( tuple.getT1() );
					String rightName = simpleName( tuple.getT2() );
					return aggregateDocuments( mongoExecutionContext, tuple.getT1(), collectionName, tuple.getT3() )
						.next()
						.map(
							document -> new ResultTuple<>(
								leftName,
								true,
								rightName,
								Optional.ofNullable( document.get( "_rightExists", Boolean.class ) ).orElse( false )
							)
						)
						.defaultIfEmpty( new ResultTuple<>( leftName, false, rightName, false ) );

				} );

			}

		}


		/**
		 * Builder for atomic update operations using MongoDB driver update definitions or update pipelines.
		 * <p>Auditing annotations such as {@code @CreatedDate} and {@code @LastModifiedDate}
		 * are not applied automatically during atomic update operations. Set auditing fields
		 * explicitly when needed.</p>
		 */
		public class AtomicUpdateQueryBuilder {

			private enum AtomicUpdateMode {
				FIRST, MULTI, UPSERT_ONE
			}

			public AtomicUpdateTypedBuilder first() {

				return new AtomicUpdateTypedBuilder( AtomicUpdateMode.FIRST );

			}

			public AtomicUpdateTypedBuilder multi() {

				return new AtomicUpdateTypedBuilder( AtomicUpdateMode.MULTI );

			}

			public AtomicUpsertTypedBuilder upsertOne() {

				return new AtomicUpsertTypedBuilder();

			}

			public class AtomicUpdateTypedBuilder {

				private final AtomicUpdateMode mode;

				protected AtomicUpdateTypedBuilder(
													AtomicUpdateMode mode
				) {

					this.mode = Objects.requireNonNull( mode, "mode must not be null" );

				}

				public AtomicDocumentUpdateBuilder document() {

					return new AtomicDocumentUpdateBuilder( mode );

				}

				public AtomicPipelineUpdateBuilder pipeline() {

					return new AtomicPipelineUpdateBuilder( mode );

				}

			}

			public class AtomicUpsertTypedBuilder extends AtomicUpdateTypedBuilder {

				private AtomicUpsertTypedBuilder() {

					super( AtomicUpdateMode.UPSERT_ONE );

				}

				@Override
				public AtomicUpsertDocumentBuilder document() {

					return new AtomicUpsertDocumentBuilder();

				}

			}

			public class AtomicDocumentUpdateBuilder {

				protected final AtomicUpdateMode mode;

				protected final DocumentSpec doc = new DocumentSpec();

				protected AtomicDocumentUpdateBuilder(
														AtomicUpdateMode mode
				) {

					this.mode = Objects.requireNonNull( mode, "mode must not be null" );

				}

				public AtomicDocumentUpdateBuilder inc(
					String field, Number delta
				) {

					doc.add( Updates.inc( requireField( field ), delta ) );
					return this;

				}

				public AtomicDocumentUpdateBuilder set(
					String field, Object value
				) {

					doc.add( Updates.set( requireField( field ), value ) );
					return this;

				}

				public AtomicDocumentUpdateBuilder unset(
					String field
				) {

					doc.add( Updates.unset( requireField( field ) ) );
					return this;

				}

				public AtomicDocumentUpdateBuilder push(
					String field, Object value
				) {

					doc.add( Updates.push( requireField( field ), value ) );
					return this;

				}

				public AtomicDocumentUpdateBuilder addToSet(
					String field, Object value
				) {

					doc.add( Updates.addToSet( requireField( field ), value ) );
					return this;

				}

				public AtomicDocumentUpdateBuilder pull(
					String field, Object value
				) {

					doc.add( Updates.pull( requireField( field ), value ) );
					return this;

				}

				/**
				 * Adds a MongoDB driver-native update definition. This keeps newer or advanced
				 * {@link Updates} operations usable without adding a matching DSL method first.
				 *
				 * @param update
				 *            the driver-native update definition
				 *
				 * @return this builder
				 */
				public AtomicDocumentUpdateBuilder driverUpdate(
					Bson update
				) {

					doc.add( Objects.requireNonNull( update, "update" ) );
					return this;

				}

				public Mono<UpdateResult> execute() {

					if (doc.isEmpty())
						return Mono.error( new IllegalStateException( "No document update specified." ) );
					return doExecute( mode, doc.build() );

				}

			}

			public class AtomicUpsertDocumentBuilder extends AtomicDocumentUpdateBuilder {

				private AtomicUpsertDocumentBuilder() {

					super( AtomicUpdateMode.UPSERT_ONE );

				}

				@Override
				public AtomicUpsertDocumentBuilder inc(
					String field, Number delta
				) {

					super.inc( field, delta );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder set(
					String field, Object value
				) {

					super.set( field, value );
					return this;

				}

				public AtomicUpsertDocumentBuilder setOnInsert(
					String field, Object value
				) {

					doc.add( Updates.setOnInsert( requireField( field ), value ) );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder unset(
					String field
				) {

					super.unset( field );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder push(
					String field, Object value
				) {

					super.push( field, value );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder addToSet(
					String field, Object value
				) {

					super.addToSet( field, value );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder pull(
					String field, Object value
				) {

					super.pull( field, value );
					return this;

				}

				@Override
				public AtomicUpsertDocumentBuilder driverUpdate(
					Bson update
				) {

					super.driverUpdate( update );
					return this;

				}

			}

			public class AtomicPipelineUpdateBuilder {

				private final AtomicUpdateMode mode;

				private final PipelineSpec pipe = new PipelineSpec();

				private AtomicPipelineUpdateBuilder(
													AtomicUpdateMode mode
				) {

					this.mode = Objects.requireNonNull( mode, "mode must not be null" );

				}

				public AtomicPipelineUpdateBuilder set(
					String field, Object valueOrExpression
				) {

					pipe.set( field, valueOrExpression );
					return this;

				}

				public AtomicPipelineUpdateBuilder inc(
					String field, Number delta
				) {

					pipe.inc( field, delta );
					return this;

				}

				public AtomicPipelineUpdateBuilder unset(
					String... fields
				) {

					pipe.unset( fields );
					return this;

				}

				/** Appends a raw MongoDB driver update-pipeline stage. */
				public AtomicPipelineUpdateBuilder stage(
					Bson stage
				) {

					pipe.stage( stage );
					return this;

				}

				public AtomicPipelineUpdateBuilder nextStage() {

					pipe.nextStage();
					return this;

				}

				public Mono<UpdateResult> execute() {

					if (pipe.isEmpty())
						return Mono.error( new IllegalStateException( "No pipeline update specified." ) );
					return doExecute( mode, pipe.build() );

				}

			}

			private Mono<UpdateResult> doExecute(
				AtomicUpdateMode mode, UpdateSpec updateSpec
			) {

				return Mono.zip( executeClassMono, fieldBuilder.buildCriteria() ).flatMap( tuple -> {
					Bson filter = tuple.getT2().orElseGet( Document::new );
					return switch (mode) {
						case UPSERT_ONE -> update( mongoExecutionContext, tuple.getT1(), collectionName, filter, updateSpec, false, true );
						case MULTI -> update( mongoExecutionContext, tuple.getT1(), collectionName, filter, updateSpec, true, false );
						case FIRST -> update( mongoExecutionContext, tuple.getT1(), collectionName, filter, updateSpec, false, false );

					};

				} );

			}

			private class DocumentSpec {

				private final List<Bson> updates = new ArrayList<>();

				void add(
					Bson update
				) {

					updates.add( update );

				}

				UpdateSpec build() {

					return UpdateSpec.document( updates.size() == 1 ? updates.get( 0 ) : Updates.combine( updates ) );

				}

				boolean isEmpty() {

					return updates.isEmpty();

				}

			}

			private class PipelineSpec {

				private final List<Bson> pipeline = new ArrayList<>();

				private Document pendingSet = new Document();

				void set(
					String field, Object valueOrExpression
				) {

					pendingSet.put( requireField( field ), valueOrExpression );

				}

				void inc(
					String field, Number delta
				) {

					String physicalField = requireField( field );
					set(
						physicalField,
						new Document( "$add", List.of( new Document( "$ifNull", List.of( "$" + physicalField, 0 ) ), delta ) )
					);

				}

				void unset(
					String... fields
				) {

					flushSet();
					List<String> physicalFields = Arrays
						.stream( fields )
						.filter( Objects::nonNull )
						.map( String::trim )
						.filter( value -> ! value.isBlank() )
						.map( MongoFieldNameSupport::toMongoField )
						.toList();
					if (! physicalFields.isEmpty())
						pipeline.add( Aggregates.unset( physicalFields ) );

				}

				void stage(
					Bson stage
				) {

					flushSet();
					if (stage != null)
						pipeline.add( stage );

				}

				void nextStage() {

					flushSet();

				}

				UpdateSpec build() {

					flushSet();
					return UpdateSpec.pipeline( pipeline );

				}

				boolean isEmpty() {

					return pipeline.isEmpty() && pendingSet.isEmpty();

				}

				private void flushSet() {

					if (! pendingSet.isEmpty()) {
						pipeline
							.add(
								Aggregates
									.set(
										pendingSet
											.entrySet()
											.stream()
											.map( entry -> new com.mongodb.client.model.Field<>( entry.getKey(), entry.getValue() ) )
											.toArray( com.mongodb.client.model.Field<?>[]::new )
									)
							);
						pendingSet = new Document();

					}

				}

			}

			private String requireField(
				String field
			) {

				if (field == null || field.isBlank())
					throw new IllegalArgumentException( "field must not be null/blank" );
				return MongoFieldNameSupport.toMongoField( field );

			}

		}




	}

	/**
	 * Execution-context builder for mapped Mongo entity classes.
	 *
	 * @param <E>
	 *            the entity type
	 */
	public abstract class ExecuteEntityBuilder<E> extends AbstractQueryBuilder<E, ExecuteEntityBuilder<E>> implements ExecuteBuilder {

		ExecuteEntityBuilder(
								Class<E> executeClass,
								K key
		) {

			this.executeClassMono = Mono.just( executeClass );
			this.mongoExecutionContext = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.executeBuilder = this;

		}

	}

	/**
	 * Execution-context builder for custom mapped result types backed by an explicit collection name.
	 *
	 * @param <E>
	 *            the mapped result type
	 */
	public abstract class ExecuteCustomClassBuilder<E> extends AbstractQueryBuilder<E, ExecuteCustomClassBuilder<E>> implements ExecuteBuilder {

		ExecuteCustomClassBuilder(
									Class<E> executeClass,
									K key,
									String collectionName
		) {

			this.executeClassMono = Mono.just( executeClass );
			this.mongoExecutionContext = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.collectionName = collectionName;
			this.executeBuilder = this;

		}

	}

	/**
	 * Creates an execution context for the given mapped entity class.
	 *
	 * @param executeEntity
	 *            the target entity class
	 * @param key
	 *            the logical Mongo execution-context key
	 * @param <E>
	 *            the entity type
	 * 
	 * @return an execution builder bound to the given entity class
	 */
	public <E> ExecuteEntityBuilder<E> executeEntity(
		Class<E> executeEntity, K key
	) {

		return new ExecuteEntityBuilder<>( executeEntity, key ) {};

	}

	/**
	 * Creates an execution context for mapping results to the given class
	 * while executing against the specified collection.
	 *
	 * @param executeCustomClass
	 *            the mapped result class
	 * @param key
	 *            the logical Mongo execution-context key
	 * @param collectionName
	 *            the target collection name
	 * @param <E>
	 *            the mapped result type
	 * 
	 * @return an execution builder bound to the given class and collection
	 */
	public <E> ExecuteCustomClassBuilder<E> executeCustomClass(
		Class<E> executeCustomClass, K key, String collectionName
	) {

		return new ExecuteCustomClassBuilder<>( executeCustomClass, key, collectionName ) {};

	}

	@Override
	public void close() {

		cursorNamespaceCoordinator.close();
		if (embeddedSyncEngine != null)
			embeddedSyncEngine.close();
		changeStreamHub.close();
		if (embeddedSyncLeaseStore != stateStore)
			embeddedSyncLeaseStore.close();
		stateStore.close();

	}



}
