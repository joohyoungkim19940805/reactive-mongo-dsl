package com.byeolnaerim.mongodsl;


import static com.byeolnaerim.mongodsl.support.ReservationTestContext.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.change.ReservationMode;
import com.byeolnaerim.mongodsl.internal.MongoDocumentComparator;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.result.ReservationDelta;
import com.byeolnaerim.mongodsl.result.ReservationDeltaType;
import com.byeolnaerim.mongodsl.support.ReservationTestContext;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


class ReactiveMongoDslReservationRegressionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds( 5 );

	@Test
	void deltaOnlyUpdateMapsTwoRowsNotTheEntireTenThousandRowSnapshot() throws Exception {

		var context = new ReservationTestContext();
		List<Document> rows = new ArrayList<>();
		for (int i = 0; i < 10_000; i++)
			rows.add( new Document( "_id", i ).append( "rank", i ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl
				.executeCustomClass( Document.class, "main", "items" )
				.fields()
				.end()
				.findAll()
				.sorts( sort -> sort.asc( "rank" ) )
				.reservationChangeStream();
			Object state = state( reservation, new Document(), new Document( "rank", 1 ), rows );
			call( state, "initialEmission" );
			assertEquals( 10_000, context.mappings.get() );
			Object first = apply(
				state,
				reservation,
				event(
					"update",
					"items",
					5,
					new BsonTimestamp( 11, 0 ),
					new Document( "_id", 5 ).append( "rank", -1 ),
					null
				),
				false
			);
			assertEquals( 10_002, context.mappings.get(), "ordinary delta must not map the complete list" );
			apply(
				state,
				reservation,
				event(
					"update",
					"items",
					5,
					new BsonTimestamp( 12, 0 ),
					new Document( "_id", 5 ).append( "rank", -2 ),
					null
				),
				false
			);
			List<Document> firstSnapshot = snapshot( first );
			assertEquals( -1, firstSnapshot.get( 0 ).getInteger( "rank" ), "lazy snapshot must capture the emitted version, not future mutable state" );
			int mapped = context.mappings.get();
			assertSame( firstSnapshot, snapshot( first ) );
			assertEquals( mapped, context.mappings.get(), "shared snapshot must be mapped only once" );
			assertEquals( 0, context.queries.get() );

		}

	}

	@Test
	void sequentialDeltaIndexesReproduceTheSortedState() throws Exception {

		var context = new ReservationTestContext();
		var initial = List
			.of(
				new Document( "_id", 1 ).append( "rank", 1 ),
				new Document( "_id", 2 ).append( "rank", 2 ),
				new Document( "_id", 3 ).append( "rank", 3 )
			);

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl
				.executeCustomClass( Document.class, "main", "items" )
				.fields()
				.end()
				.findAll()
				.sorts( sort -> sort.asc( "rank" ) )
				.reservationChangeStream();
			Object state = state( reservation, new Document(), new Document( "rank", 1 ), initial );
			List<Document> client = new ArrayList<>( initial );

			for (var change : List
				.of(
					event( "update", "items", 3, new BsonTimestamp( 11, 0 ), new Document( "_id", 3 ).append( "rank", 0 ), null ),
					event( "insert", "items", 4, new BsonTimestamp( 12, 0 ), new Document( "_id", 4 ).append( "rank", 1.5 ), null ),
					event( "delete", "items", 2, new BsonTimestamp( 13, 0 ), null, null )
				)) {
				Object emission = apply( state, reservation, change, false );

				for (ReservationDelta<Document> delta : deltas( emission )) {

					switch (delta.type()) {
						case UPDATED -> {
							client.remove( delta.beforeIndex().intValue() );
							client.add( delta.afterIndex(), delta.after() );

						}
						case INSERTED -> client.add( delta.afterIndex(), delta.after() );
						case REMOVED -> client.remove( delta.beforeIndex().intValue() );
						default -> fail( "unexpected delta " + delta.type() );

					}

				}

				assertEquals( snapshot( emission ), client );

			}

			assertEquals( List.of( 3, 1, 4 ), client.stream().map( row -> row.getInteger( "_id" ) ).toList() );

		}

	}

	@Test
	void noOpTargetedLookupDoesNotEagerlyRemoveTheExistingRow() throws Exception {

		var context = new ReservationTestContext();
		context.rows.add( new Document( "_id", 1 ).append( "items", List.of( 7 ) ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			Object state = state( reservation, new Document(), null, context.rows );
			Mono<?> lookup = (Mono<?>) call( state, "lookupAndReconcile", new Class<?>[] {
				Object.class
			}, 1 );
			assertEquals( 1, snapshot( call( state, "initialEmission" ) ).size(), "assembling lookup must not mutate state" );
			assertNull( lookup.block( TIMEOUT ), "unchanged lookup has no item delta" );
			assertEquals( 1, snapshot( call( state, "initialEmission" ) ).size(), "Mono.empty from no-op is not a missing DB document" );
			context.rows.clear();
			Mono<?> missing = (Mono<?>) call( state, "lookupAndReconcile", new Class<?>[] {
				Object.class
			}, 1 );
			assertNotNull( missing.block( TIMEOUT ) );
			assertTrue( snapshot( call( state, "initialEmission" ) ).isEmpty() );
			assertEquals( ReadConcern.MAJORITY, context.appliedReadConcern.get() );
			assertEquals( ReadPreference.primary(), context.appliedReadPreference.get() );

		}

	}

	@Test
	void everyNullClusterTimeEventRefreshesEvenAfterPriorRefresh() throws Exception {

		var context = new ReservationTestContext();
		context.rows.add( new Document( "_id", 1 ).append( "value", "a" ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			Object state = state( reservation, new Document(), null, context.rows );
			assertNotNull( apply( state, reservation, event( "update", "items", 1, null, null, null ), false ) );
			context.rows.set( 0, new Document( "_id", 1 ).append( "value", "b" ) );
			Object emission = apply( state, reservation, event( "update", "items", 1, null, null, null ), false );
			assertEquals( 2, context.queries.get() );
			assertEquals( "b", snapshot( emission ).getFirst().getString( "value" ) );
			assertEquals( ReservationDeltaType.REFRESHED, deltas( emission ).getFirst().type() );

		}

	}

	@Test
	void onlyEventsAtOrBeforeSuccessfulCausalFenceAreDiscarded() throws Exception {

		var context = new ReservationTestContext();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			Object state = state( reservation, new Document(), null, List.of() );
			assertNull( apply( state, reservation, event( "insert", "items", 1, new BsonTimestamp( 10, 0 ), new Document( "_id", 1 ), null ), false ) );
			Object later = apply( state, reservation, event( "insert", "items", 2, new BsonTimestamp( 10, 1 ), new Document( "_id", 2 ), null ), false );
			assertEquals( List.of( new Document( "_id", 2 ) ), snapshot( later ) );

		}

	}

	@Test
	void overflowRefreshesAndRechecksMetadataWhileStrictModeFails() throws Exception {

		var context = new ReservationTestContext();
		context.rows.add( new Document( "_id", 1 ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			Object state = state( reservation, new Document(), null, List.of() );
			var change = event( "insert", "items", 1, new BsonTimestamp( 11, 0 ), new Document( "_id", 1 ), null );
			Object refreshed = apply( state, reservation, change, true );
			assertEquals( ReservationDeltaType.REFRESHED, deltas( refreshed ).getFirst().type() );
			assertEquals( 1, context.metadataReads.get() );
			assertEquals( 1, context.queries.get() );
			assertEquals( new BsonTimestamp( 11, 0 ), context.advancedTime.get() );
			reservation.mode( ReservationMode.INCREMENTAL_ONLY );
			assertThrows( IllegalStateException.class, () -> apply( state, reservation, change, true ) );
			assertEquals( 1, context.queries.get(), "strict mode must fail before issuing fallback read" );

		}

	}

	@Test
	void slowDeltaSubscriberReceivesRefreshedSnapshotAfterRevisionGap() {

		var context = new ReservationTestContext();
		context.rows.add( new Document( "_id", 1 ).append( "rank", 1 ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var changes = dsl
				.executeCustomClass( Document.class, "main", "items" )
				.fields()
				.end()
				.findAll()
				.reservationChangeStream()
				.coalesce( Duration.ZERO )
				.deltas()
				.filter( delta -> delta.type() != ReservationDeltaType.UPDATED );
			StepVerifier
				.create( changes, 0 )
				.thenRequest( 1 )
				.assertNext( delta -> assertEquals( ReservationDeltaType.INITIAL, delta.type() ) )
				.then(
					() -> assertEquals(
						reactor.core.publisher.Sinks.EmitResult.OK,
						context.events
							.tryEmitNext(
								event( "update", "items", 1, new BsonTimestamp( 11, 0 ), new Document( "_id", 1 ).append( "rank", 2 ), null )
							)
					)
				)
				.thenAwait( Duration.ofMillis( 100 ) )
				.then(
					() -> assertEquals(
						reactor.core.publisher.Sinks.EmitResult.OK,
						context.events
							.tryEmitNext(
								event( "update", "items", 1, new BsonTimestamp( 12, 0 ), new Document( "_id", 1 ).append( "rank", 3 ), null )
							)
					)
				)
				.thenAwait( Duration.ofMillis( 100 ) )
				.then(
					() -> assertEquals(
						reactor.core.publisher.Sinks.EmitResult.OK,
						context.events
							.tryEmitNext(
								event( "update", "items", 1, new BsonTimestamp( 13, 0 ), new Document( "_id", 1 ).append( "rank", 4 ), null )
							)
					)
				)
				.thenAwait( Duration.ofMillis( 100 ) )
				.then(
					() -> assertEquals(
						reactor.core.publisher.Sinks.EmitResult.OK,
						context.events
							.tryEmitNext(
								event( "update", "items", 1, new BsonTimestamp( 14, 0 ), new Document( "_id", 1 ).append( "rank", 5 ), null )
							)
					)
				)
				.thenAwait( Duration.ofMillis( 100 ) )
				.thenRequest( 1 )
				.assertNext( delta -> {
					assertEquals( ReservationDeltaType.REFRESHED, delta.type() );
					assertEquals( 5, delta.snapshot().getFirst().getInteger( "rank" ) );

				} )
				.thenCancel()
				.verify( TIMEOUT );

		}

	}

	@Test
	void lateDeltaSubscriberGetsCurrentInitialAndSharesTheOriginalRead() {

		var context = new ReservationTestContext();
		context.rows.add( new Document( "_id", 1 ).append( "rank", 1 ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream().coalesce( Duration.ZERO );
			Disposable first = reservation.execute().subscribe();

			try {
				StepVerifier
					.create( reservation.deltas().take( 1 ) )
					.assertNext( delta -> {
						assertEquals( ReservationDeltaType.INITIAL, delta.type() );
						assertEquals( 1, delta.snapshot().size() );

					} )
					.verifyComplete();
				assertEquals( 1, context.queries.get() );
				assertEquals( 1, context.metadataReads.get() );

			} finally {
				first.dispose();

			}

		}

	}

	@Test
	void equivalentLookupReservationsShareOneAggregateButDifferentPipelinesDoNot() {

		var context = new ReservationTestContext();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var left = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll();
			var right = dsl.executeCustomClass( Document.class, "main", "rights" ).fields().end().findAll();
			var spec = LookupSpec.builder().localField( "ownerId" ).foreignField( "_id" ).build();
			var first = left.reservationChangeStream().executeLookup( right, spec ).subscribe();
			var second = left.reservationChangeStream().executeLookup( right, spec ).subscribe();

			try {
				assertEquals( 1, context.aggregates.get() );
				var different = left
					.reservationChangeStream()
					.executeLookup(
						right,
						LookupSpec.builder().localField( "otherId" ).foreignField( "_id" ).build()
					)
					.subscribe();

				try {
					assertEquals( 2, context.aggregates.get() );

				} finally {
					different.dispose();

				}

			} finally {
				first.dispose();
				second.dispose();

			}

		}

	}

	@Test
	void nonSimpleOrUnknownCollationFallsBackAndStrictModeIsExplicit() {

		var context = new ReservationTestContext();
		context.simpleCollation = false;

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			StepVerifier.create( reservation.execute().take( 1 ) ).expectNext( List.of() ).verifyComplete();
			assertEquals( 1, context.queries.get() );
			StepVerifier
				.create( reservation.mode( ReservationMode.INCREMENTAL_ONLY ).execute() )
				.expectErrorMatches( error -> error instanceof IllegalStateException && error.getMessage().contains( "simple-collation" ) )
				.verify( TIMEOUT );

		}

	}

	@Test
	void bootstrapOverflowDoesNotGrowWithoutLimitOrDropTheRefreshSignal() {

		var context = new ReservationTestContext();
		var initialRead = reactor.core.publisher.Sinks.<List<Document>>one();
		context.queryOverride = () -> initialRead.asMono().flatMapMany( reactor.core.publisher.Flux::fromIterable );
		context.rows.add( new Document( "_id", 99 ).append( "rank", 99 ) );

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl
				.executeCustomClass( Document.class, "main", "items" )
				.fields()
				.end()
				.findAll()
				.reservationChangeStream()
				.bufferCapacity( 2 )
				.coalesce( Duration.ZERO );
			StepVerifier
				.create( reservation.deltas() )
				.then( () -> {
					for (int i = 11; i <= 30; i++)
						context.events
							.tryEmitNext(
								event(
									"insert",
									"items",
									i,
									new BsonTimestamp( i, 0 ),
									new Document( "_id", i ),
									null
								)
							);

				} )
				.thenAwait( Duration.ofMillis( 100 ) )
				.then( () -> {
					context.operationTime = new BsonTimestamp( 30, 0 );
					context.queryOverride = null;
					initialRead.tryEmitValue( List.of() );

				} )
				.assertNext( delta -> assertEquals( ReservationDeltaType.INITIAL, delta.type() ) )
				.assertNext( delta -> {
					assertEquals( ReservationDeltaType.REFRESHED, delta.type() );
					assertEquals( 99, delta.snapshot().getFirst().getInteger( "_id" ) );

				} )
				.thenCancel()
				.verify( TIMEOUT );
			assertEquals( 2, context.queries.get() );
			assertEquals( 2, context.metadataReads.get() );

		}

	}

	@Test
	void opaqueAggregationCustomizersAreNeverSharedByFingerprint() {

		var context = new ReservationTestContext();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var leftA = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll();
			var leftB = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll();
			leftA.customizeAggregation( publisher -> publisher.batchSize( 10 ) );
			leftB.customizeAggregation( publisher -> publisher.batchSize( 20 ) );
			var right = dsl.executeCustomClass( Document.class, "main", "rights" ).fields().end().findAll();
			var spec = LookupSpec.builder().localField( "ownerId" ).foreignField( "_id" ).build();
			var one = leftA.reservationChangeStream().executeLookup( right, spec ).subscribe();
			var two = leftB.reservationChangeStream().executeLookup( right, spec ).subscribe();

			try {
				assertEquals( 2, context.aggregates.get() );

			} finally {
				one.dispose();
				two.dispose();

			}

		}

	}

	@Test
	void missingDeleteKeyTriggersRefreshInsteadOfLeavingAStaleRow() throws Exception {

		var context = new ReservationTestContext();

		try (var dsl = new ReactiveMongoDsl<String>( ignored -> context )) {
			var reservation = dsl.executeCustomClass( Document.class, "main", "items" ).fields().end().findAll().reservationChangeStream();
			Object state = state( reservation, new Document(), null, List.of( new Document( "_id", 1 ) ) );
			Object emission = apply( state, reservation, event( "delete", "items", null, new BsonTimestamp( 11, 0 ), null, null ), false );
			assertEquals( ReservationDeltaType.REFRESHED, deltas( emission ).getFirst().type() );
			assertTrue( snapshot( emission ).isEmpty() );
			assertEquals( 1, context.queries.get() );

		}

	}

	private static Object state(
		Object reservation, Bson criteria, Bson sort, List<Document> rows
	)
		throws Exception {

		Class<?> stateType = Arrays
			.stream( reservation.getClass().getDeclaredClasses() )
			.filter( type -> type.getSimpleName().equals( "MaterializedReservationState" ) )
			.findFirst()
			.orElseThrow();
		Constructor<?> constructor = stateType.getDeclaredConstructors()[0];
		constructor.setAccessible( true );
		return constructor
			.newInstance(
				reservation,
				Document.class,
				criteria,
				MongoDocumentMatcher.analyze( criteria ),
				MongoDocumentComparator.from( sort ),
				new BsonTimestamp( 10, 0 ),
				rows
			);

	}

	private static Object apply(
		Object state, Object reservation, ChangeStreamDocument<Document> event, boolean overflow
	)
		throws Exception {

		Class<?> changeType = Arrays
			.stream( reservation.getClass().getDeclaredClasses() )
			.filter( type -> type.getSimpleName().equals( "ReservationChange" ) )
			.findFirst()
			.orElseThrow();
		Constructor<?> constructor = changeType.getDeclaredConstructor( boolean.class, ChangeStreamDocument.class, boolean.class );
		constructor.setAccessible( true );
		Object change = constructor.newInstance( true, event, overflow );
		return ((Mono<?>) call( state, "applyBatch", new Class<?>[] {
			List.class
		}, List.of( change ) )).block( TIMEOUT );

	}

	@SuppressWarnings("unchecked")
	private static List<Document> snapshot(
		Object emission
	)
		throws Exception {

		return (List<Document>) call( emission, "snapshot" );

	}

	@SuppressWarnings("unchecked")
	private static List<ReservationDelta<Document>> deltas(
		Object emission
	)
		throws Exception {

		return (List<ReservationDelta<Document>>) call( emission, "deltas" );

	}

	private static Object call(
		Object target, String method
	)
		throws Exception {

		return call( target, method, new Class<?>[0] );

	}

	private static Object call(
		Object target, String method, Class<?>[] types, Object... arguments
	)
		throws Exception {

		Method operation = target.getClass().getDeclaredMethod( method, types );
		operation.setAccessible( true );
		return operation.invoke( target, arguments );

	}

}
