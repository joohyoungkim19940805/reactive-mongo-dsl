package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.byeolnaerim.mongodsl.change.ChangeStreamHub;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.support.ReservationTestContext;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncEngine;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;


class EmbeddedSyncEngineTest {

	// Excluding a routing field changes the stored snapshot, not the map key or target selection.
	@ParameterizedTest
	@CsvSource({
		"code, code, true",
		"code, code, false",
		"metadata.code, metadata, true",
		"metadata.code, metadata.code, true"
	})
	void excludedMapKeysStillRouteSnapshotsWithoutMutatingTheSource(
		String mapKey, String excludedField, boolean linked
	) throws InterruptedException {

		var driver = new ReservationTestContext();
		Document source = new Document( "_id", "child-1" )
			.append( "parentId", "parent-1" )
			.append( "code", "one" )
			.append( "metadata", new Document( "code", "one" ).append( "label", "kept" ) );
		Document original = Document.parse( source.toJson() );
		driver.queryOverride = () -> Flux.just( source );
		BlockingQueue<Write> writes = new LinkedBlockingQueue<>();
		MongoExecutionContext context = context( driver, writes );

		var config = new EmbeddedSyncConfig<String>();
		var relation = config.forKeys( "db" ).from( Child.class ).into( Parent.class, "children" )
			.mapKey( mapKey ).excludeSourceFields( excludedField, "parentId" );
		if (linked)
			relation.linkBy().fromField( "parentId" ).intoField( "id" ).end();
		relation.build();

		try (var hub = new ChangeStreamHub(); var engine = new EmbeddedSyncEngine( hub )) {
			engine.register( context, config.registrations().getFirst().definition() ).block( Duration.ofSeconds( 3 ) );
			driver.events.emitNext(
				ReservationTestContext.event( "insert", "items", "child-1", new BsonTimestamp( 11, 0 ), source, null ),
				Sinks.EmitFailureHandler.FAIL_FAST
			);

			Write write = writes.poll( 3, TimeUnit.SECONDS );
			assertNotNull( write, "The map snapshot must be written even when its mapKey is excluded" );
			if (linked)
				assertEquals( new Document( "_id", "parent-1" ), write.filter() );
			else
				assertTrue( write.filter().containsKey( "$expr" ) );

			Document value = write.pipeline().getFirst().get( "$set", Document.class ).get( "children", Document.class );
			List<?> concatenated = value.get( "$arrayToObject", Document.class ).getList( "$concatArrays", Object.class );
			assertNotNull( concatenated, "Target cleanup must not replace writing the map snapshot" );
			Document entry = (Document) ((List<?>) concatenated.get( 1 )).getFirst();
			assertEquals( "one", entry.getString( "k" ) );
			Document snapshot = entry.get( "v", Document.class ).get( "$literal", Document.class );
			assertEquals( "child-1", snapshot.getString( "_id" ) );
			assertFalse( snapshot.containsKey( "parentId" ) );
			if (excludedField.equals( "metadata.code" )) {
				assertFalse( snapshot.get( "metadata", Document.class ).containsKey( "code" ) );
				assertEquals( "kept", snapshot.get( "metadata", Document.class ).getString( "label" ) );

			} else {
				assertFalse( snapshot.containsKey( excludedField ) );

			}
			assertEquals( original, source );
		}

	}

	private MongoExecutionContext context(
		ReservationTestContext driver, BlockingQueue<Write> writes
	) {

		MongoCollection<?> target = proxy( MongoCollection.class, (self, method, args) -> {
			if (method.getName().equals( "updateMany" ))
				return Mono.fromSupplier( () -> {
					writes.add( new Write(
						MongoBsonSupport.toDocument( (Bson) args[0] ),
						((List<?>) args[1]).stream().map( Bson.class::cast ).map( MongoBsonSupport::toDocument ).toList()
					) );
					return UpdateResult.acknowledged( 1L, 1L, null );

				} );
			throw new UnsupportedOperationException( "Unexpected target call: " + method.getName() );

		} );
		MongoDatabase database = proxy( MongoDatabase.class, (self, method, args) -> {
			if (method.getName().equals( "getCollection" ) && "parents".equals( args[0] ))
				return target;
			return method.invoke( driver.database, args );

		} );
		return new MongoExecutionContext() {
			@Override
			public Mono<MongoDatabase> getDatabase() { return Mono.just( database ); }

			@Override
			public Mono<ClientSession> startSession() { return driver.startSession(); }

			@Override
			public String getCollectionName(Class<?> type) { return type == Parent.class ? "parents" : "items"; }

			@Override
			public Object getId(Object entity) { return driver.getId( entity ); }

			@Override
			public Object getNative() { return driver; }
		};

	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(
		Class<T> type, InvocationHandler handler
	) {

		return (T) Proxy.newProxyInstance( type.getClassLoader(), new Class<?>[] { type }, handler );

	}

	private record Write(Document filter, List<Document> pipeline) {}

	private static final class Parent {
		private Map<String, Child> children;
	}

	private static final class Child {}

}
