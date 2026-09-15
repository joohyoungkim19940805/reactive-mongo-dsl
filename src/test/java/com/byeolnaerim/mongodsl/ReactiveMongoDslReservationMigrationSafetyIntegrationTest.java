package com.byeolnaerim.mongodsl;


import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.byeolnaerim.mongodsl.change.ChangeStreamDocumentMode;
import com.byeolnaerim.mongodsl.change.ReservationMode;
import com.byeolnaerim.mongodsl.internal.MongoDocumentComparator;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.result.ReservationDelta;
import com.byeolnaerim.mongodsl.result.ReservationDeltaType;
import com.byeolnaerim.mongodsl.result.ResultTuple;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


/** Real MongoDB differential and reservation tests. Uses and drops only its own UUID database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactiveMongoDslReservationMigrationSafetyIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds( 20 );

	private final String databaseName = "reservation_test_" + UUID.randomUUID().toString().replace( "-", "" );

	private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	private final AtomicReference<Throwable> streamError = new AtomicReference<>();

	private MongoClient client;

	private MongoDatabase database;

	private DriverMongoExecutionContext context;

	@BeforeAll
	void connectToDedicatedTestServer() {

		String uri = System.getenv( "MONGO_RESERVATION_TEST_URI" );

		if (uri == null || uri.isBlank()) {
			if (Boolean.getBoolean( "mongo.reservation.required" ))
				throw new IllegalStateException( "MONGO_RESERVATION_TEST_URI must point to a dedicated replica-set test server" );
			org.junit.jupiter.api.Assumptions.assumeTrue( false, "MONGO_RESERVATION_TEST_URI is not configured" );

		}

		client = MongoClients
			.create(
				MongoClientSettings
					.builder()
					.applyConnectionString( new ConnectionString( uri ) )
					.addCommandListener( new CommandListener() {

						@Override
						public void commandStarted(
							CommandStartedEvent event
						) {

							if (! databaseName.equals( event.getDatabaseName() ))
								return;
							var collection = event.getCommand().get( event.getCommandName() );
							if (collection != null && collection.isString())
								calls.computeIfAbsent( event.getCommandName() + ":" + collection.asString().getValue(), ignored -> new AtomicInteger() ).incrementAndGet();

						}

					} )
					.build()
			);
		database = client.getDatabase( databaseName ).withReadPreference( ReadPreference.primary() ).withWriteConcern( WriteConcern.MAJORITY );
		Document hello = Mono.from( database.runCommand( new Document( "hello", 1 ) ) ).block( TIMEOUT );
		assertNotNull( hello );
		assertTrue( hello.containsKey( "setName" ) || "isdbgrid".equals( hello.getString( "msg" ) ), "change streams require a replica set or mongos" );
		context = new DriverMongoExecutionContext( client, database, ignored -> "items", source -> ((Document) source).get( "_id" ) );
		System.out.println( "Reservation integration server=" + hello.get( "maxWireVersion" ) + "; isolated database=" + databaseName );

	}

	@AfterAll
	void removeOnlyTheDatabaseCreatedByThisTest() {

		try {
			if (database != null && databaseName.startsWith( "reservation_test_" ))
				Mono.from( database.drop() ).block( TIMEOUT );

		} finally {
			if (client != null)
				client.close();

		}

	}

	@Test
	void matcherDefinitiveResultsAgreeWithMongoAcrossNullArraysUnicodeAndNumericTypes() {

		MongoCollection<Document> collection = create( "matcher_diff" );
		List<Object> values = Arrays
			.asList(
				null,
				0,
				1,
				1L,
				1D,
				2,
				-3,
				9007199254740993L,
				9007199254740992D,
				new Decimal128( new BigDecimal( "0.1" ) ),
				0.1D,
				Double.NaN,
				Double.NEGATIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				"ABC",
				"abc",
				"\uE000",
				"\uD800\uDC00",
				true,
				new java.util.Date( 1 ),
				new ObjectId(),
				List.of( 1, 2 ),
				Arrays.asList( 1, null ),
				List.of( List.of( 1, 2 ) ),
				new Document( "n", 1 ),
				new BsonUndefined()
			);
		List<Document> rows = new ArrayList<>();
		rows.add( new Document( "_id", 0 ) );
		for (int i = 0; i < values.size(); i++)
			rows.add( new Document( "_id", i + 1 ).append( "x", values.get( i ) ) );
		rows.add( new Document( "_id", rows.size() ).append( "a", List.of( new Document( "b", 1 ), new Document( "b", 2 ) ) ) );
		rows.add( new Document( "_id", rows.size() ).append( "a", List.of( new Document( "b", 1 ), new Document() ) ) );
		rows.add( new Document( "_id", rows.size() ).append( "a", List.of( new Document( "b", List.of( 1 ) ), new Document( "b", List.of( 2 ) ) ) ) );
		Mono.from( collection.insertMany( rows ) ).block( TIMEOUT );
		List<Document> filters = new ArrayList<>();
		for (Object value : Arrays.asList( null, 1, 1L, 1D, "ABC", "\uD800\uDC00", List.of( 1, 2 ) ))
			filters.add( new Document( "x", value ) );
		for (String operator : List.of( "$gt", "$gte", "$lt", "$lte" ))
			for (Object value : List.of( 0, 1, "a", "\uD800\uDC00", new Decimal128( new BigDecimal( "0.1" ) ) ))
				filters.add( new Document( "x", new Document( operator, value ) ) );
		filters
			.addAll(
				List
					.of(
						new Document( "x", new Document( "$ne", null ) ),
						new Document( "x", new Document( "$nin", Arrays.asList( null, 1 ) ) ),
						new Document( "x", new Document( "$in", Arrays.asList( null, 1, "ABC" ) ) ),
						new Document( "x", new Document( "$exists", true ) ),
						new Document( "x", new Document( "$exists", false ) ),
						new Document( "x", new Document( "$all", List.of( 1, 2 ) ) ),
						new Document( "x", new Document( "$size", 2 ) ),
						new Document( "x", new Document( "$elemMatch", new Document( "$eq", null ) ) ),
						new Document( "x", new Document( "$elemMatch", new Document( "$eq", 1 ) ) ),
						new Document( "a.b", null ),
						new Document( "a.b", new Document( "$all", List.of( 1, 2 ) ) ),
						new Document( "x", new Document( "$not", new Document( "$gt", 2 ) ) )
					)
			);
		int checked = 0, unknown = 0;

		for (Document filter : filters) {
			Set<Integer> serverIds = new HashSet<>( Flux.from( collection.find( filter ) ).map( row -> row.getInteger( "_id" ) ).collectList().block( TIMEOUT ) );
			var analysis = MongoDocumentMatcher.analyze( filter );

			for (Document row : rows) {
				var local = MongoDocumentMatcher.matches( analysis, row );

				if (local == MongoDocumentMatcher.MatchResult.UNKNOWN) {
					unknown++;
					continue;

				}

				checked++;
				assertEquals(
					serverIds.contains( row.getInteger( "_id" ) ),
					local == MongoDocumentMatcher.MatchResult.MATCH,
					() -> "filter=" + filter + "; row=" + row
				);

			}

		}

		assertTrue( checked > 500, "must actually compare a broad set of definitive matches" );
		assertTrue( unknown > 0, "unsupported edge cases must take the safe fallback" );
		System.out.println( "Matcher differential: definitive=" + checked + ", delegated=" + unknown );

	}

	@Test
	void comparatorMatchesMongoForMixedScalarsBothDirectionsAndUniqueTieBreaker() {

		MongoCollection<Document> collection = create( "sort_diff" );
		List<Object> values = Arrays
			.asList(
				new MinKey(),
				null,
				Double.NaN,
				Double.NEGATIVE_INFINITY,
				-1,
				new Decimal128( new BigDecimal( "0.1" ) ),
				0.1D,
				1,
				1L,
				1D,
				9007199254740993L,
				9007199254740992D,
				Double.POSITIVE_INFINITY,
				Decimal128.parse( "-0E-15" ),
				"a",
				"\uE000",
				"\uD800\uDC00",
				new Binary( new byte[] {
					1
				} ),
				new ObjectId( "000000000000000000000001" ),
				false,
				true,
				new java.util.Date( 0 ),
				new BsonTimestamp( 1, 0 ),
				new MaxKey()
			);
		List<Document> rows = new ArrayList<>();
		for (int i = 0; i < values.size(); i++)
			rows.add( new Document( "_id", i ).append( "x", values.get( i ) ) );
		rows.add( new Document( "_id", values.size() ) ); // Missing and null have the same scalar sort rank.
		Mono.from( collection.insertMany( rows ) ).block( TIMEOUT );

		for (int direction : List.of( 1, -1 )) {
			var sort = new Document( "x", direction ).append( "_id", 1 );
			List<Document> local = new ArrayList<>( rows );
			local.sort( MongoDocumentComparator.from( sort ).orElseThrow() );
			assertEquals(
				Flux.from( collection.find().sort( sort ) ).map( row -> row.getInteger( "_id" ) ).collectList().block( TIMEOUT ),
				local.stream().map( row -> row.getInteger( "_id" ) ).toList()
			);

		}

	}

	@Test
	void liveDeltaCrudMembershipAndOrderingUseOnlyNecessaryFindQueries() throws Exception {

		MongoCollection<Document> collection = create( "live_delta" );
		Mono
			.from(
				collection
					.insertMany(
						List
							.of(
								new Document( "_id", 1 ).append( "active", true ).append( "rank", 10 ),
								new Document( "_id", 2 ).append( "active", false ).append( "rank", 20 )
							)
					)
			)
			.block( TIMEOUT );
		BlockingQueue<ReservationDelta<Document>> received = new LinkedBlockingQueue<>();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context, ChangeStreamDocumentMode.DELTA )) {
			var subscription = dsl
				.executeCustomClass( Document.class, "main", "live_delta" )
				.fields( pair( "active", true ) )
				.end()
				.findAll()
				.sorts( sort -> sort.asc( "rank" ) )
				.reservationChangeStream()
				.coalesce( Duration.ZERO )
				.deltas()
				.subscribe( received::offer, streamError::set );

			try {
				next( received, ReservationDeltaType.INITIAL );
				int initialFinds = count( "find", "live_delta" );
				Mono.from( collection.insertOne( new Document( "_id", 3 ).append( "active", true ).append( "rank", 5 ) ) ).block( TIMEOUT );
				assertEquals( 0, next( received, ReservationDeltaType.INSERTED ).afterIndex() );
				Mono.from( collection.updateOne( Filters.eq( "_id", 3 ), Updates.set( "rank", 30 ) ) ).block( TIMEOUT );
				var changed = next( received, ReservationDeltaType.UPDATED );
				assertEquals( 0, changed.beforeIndex() );
				assertEquals( 1, changed.afterIndex() );
				Mono.from( collection.deleteOne( Filters.eq( "_id", 3 ) ) ).block( TIMEOUT );
				next( received, ReservationDeltaType.REMOVED );
				assertEquals( initialFinds, count( "find", "live_delta" ), "ordinary materialized CRUD must not re-query" );
				Mono.from( collection.updateOne( Filters.eq( "_id", 2 ), Updates.set( "active", true ) ) ).block( TIMEOUT );
				next( received, ReservationDeltaType.INSERTED );
				assertEquals( initialFinds + 1, count( "find", "live_delta" ), "membership entry needs one targeted lookup in DELTA mode" );
				Mono.from( collection.updateOne( Filters.eq( "_id", 2 ), Updates.set( "active", false ) ) ).block( TIMEOUT );
				next( received, ReservationDeltaType.REMOVED );
				assertEquals( initialFinds + 1, count( "find", "live_delta" ) );

			} finally {
				subscription.dispose();

			}

		}

	}

	@Test
	void pageBoundaryDeletionRefreshesAndRefillsTheWindow() throws Exception {

		MongoCollection<Document> collection = create( "live_page" );
		Mono
			.from(
				collection
					.insertMany(
						List
							.of(
								new Document( "_id", 1 ).append( "rank", 1 ),
								new Document( "_id", 2 ).append( "rank", 2 ),
								new Document( "_id", 3 ).append( "rank", 3 )
							)
					)
			)
			.block( TIMEOUT );
		BlockingQueue<ReservationDelta<Document>> received = new LinkedBlockingQueue<>();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context, ChangeStreamDocumentMode.DELTA )) {
			var subscription = dsl
				.executeCustomClass( Document.class, "main", "live_page" )
				.fields()
				.end()
				.findAll()
				.sorts( sort -> sort.asc( "rank" ) )
				.paging( 0, 2 )
				.reservationChangeStream()
				.coalesce( Duration.ZERO )
				.deltas()
				.subscribe( received::offer, streamError::set );

			try {
				next( received, ReservationDeltaType.INITIAL );
				Mono.from( collection.deleteOne( Filters.eq( "_id", 1 ) ) ).block( TIMEOUT );
				var refreshed = next( received, ReservationDeltaType.REFRESHED );
				assertEquals( List.of( 2, 3 ), refreshed.snapshot().stream().map( row -> row.getInteger( "_id" ) ).toList() );

			} finally {
				subscription.dispose();

			}

		}

	}

	@Test
	void simpleLookupIsSharedAndForeignCollectionChangeRefreshesBothSubscribers() throws Exception {

		MongoCollection<Document> leftCollection = create( "lookup_left" );
		MongoCollection<Document> rightCollection = create( "lookup_right" );
		Mono.from( leftCollection.insertOne( new Document( "_id", 1 ).append( "ownerId", 10 ) ) ).block( TIMEOUT );
		Mono.from( rightCollection.insertOne( new Document( "_id", 10 ).append( "name", "before" ) ) ).block( TIMEOUT );
		BlockingQueue<List<ResultTuple<Document, List<Document>>>> a = new LinkedBlockingQueue<>(), b = new LinkedBlockingQueue<>();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context, ChangeStreamDocumentMode.DELTA )) {
			var left = dsl.executeCustomClass( Document.class, "main", "lookup_left" ).fields().end().findAll();
			var right = dsl.executeCustomClass( Document.class, "main", "lookup_right" ).fields().end().findAll();
			var spec = LookupSpec.builder().localField( "ownerId" ).foreignField( "_id" ).build();
			var one = left.reservationChangeStream().coalesce( Duration.ZERO ).executeLookup( right, spec ).subscribe( a::offer, streamError::set );
			var two = left.reservationChangeStream().coalesce( Duration.ZERO ).executeLookup( right, spec ).subscribe( b::offer, streamError::set );

			try {
				assertNotNull( a.poll( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS ) );
				assertNotNull( b.poll( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS ) );
				assertEquals( 1, count( "aggregate", "lookup_left" ) );
				Mono.from( rightCollection.updateOne( Filters.eq( "_id", 10 ), Updates.set( "name", "after" ) ) ).block( TIMEOUT );
				var first = a.poll( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS );
				var second = b.poll( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS );
				assertNotNull( first );
				assertNotNull( second );
				assertEquals( "after", first.getFirst().getRight().getFirst().getString( "name" ) );
				assertEquals( "after", second.getFirst().getRight().getFirst().getString( "name" ) );
				assertEquals( 2, count( "aggregate", "lookup_left" ) );

			} finally {
				one.dispose();
				two.dispose();

			}

		}

	}

	@Test
	void collectionDefaultCollationUsesServerFallbackRatherThanJavaStringEquality() throws Exception {

		Mono
			.from(
				database
					.createCollection(
						"collated",
						new CreateCollectionOptions()
							.collation(
								Collation.builder().locale( "en" ).collationStrength( CollationStrength.SECONDARY ).build()
							)
					)
			)
			.block( TIMEOUT );
		MongoCollection<Document> collection = database.getCollection( "collated" );
		Mono.from( collection.insertOne( new Document( "_id", 1 ).append( "name", "abc" ) ) ).block( TIMEOUT );
		BlockingQueue<ReservationDelta<Document>> received = new LinkedBlockingQueue<>();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl
				.executeCustomClass( Document.class, "main", "collated" )
				.fields( pair( "name", "ABC" ) )
				.end()
				.findAll()
				.reservationChangeStream()
				.coalesce( Duration.ZERO );
			var subscription = reservation.deltas().subscribe( received::offer, streamError::set );

			try {
				assertEquals( 1, next( received, ReservationDeltaType.INITIAL ).snapshot().size() );
				Mono.from( collection.insertOne( new Document( "_id", 2 ).append( "name", "AbC" ) ) ).block( TIMEOUT );
				assertEquals( 2, next( received, ReservationDeltaType.REFRESHED ).snapshot().size() );

			} finally {
				subscription.dispose();

			}

			StepVerifier
				.create( reservation.mode( ReservationMode.INCREMENTAL_ONLY ).execute() )
				.expectErrorMatches( error -> error instanceof IllegalStateException && error.getMessage().contains( "simple-collation" ) )
				.verify( TIMEOUT );

		}

	}

	private MongoCollection<Document> create(
		String collection
	) {

		Mono.from( database.createCollection( collection ) ).block( TIMEOUT );
		return database.getCollection( collection );

	}

	private int count(
		String command, String collection
	) {

		return calls.getOrDefault( command + ":" + collection, new AtomicInteger() ).get();

	}

	private ReservationDelta<Document> next(
		BlockingQueue<ReservationDelta<Document>> received, ReservationDeltaType expected
	)
		throws Exception {

		var value = received.poll( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS );
		assertNull( streamError.get(), "reservation terminated unexpectedly" );
		assertNotNull( value, "missing " + expected + " emission" );
		assertEquals( expected, value.type() );
		return value;

	}

}
