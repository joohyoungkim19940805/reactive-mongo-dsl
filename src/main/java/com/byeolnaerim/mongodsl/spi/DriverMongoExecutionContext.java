package com.byeolnaerim.mongodsl.spi;


import java.beans.Introspector;
import java.util.Objects;
import java.util.function.Function;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.ObjectId;
import com.byeolnaerim.mongodsl.internal.MongoEntityCodecSupport;
import com.byeolnaerim.mongodsl.internal.MongoIdFieldResolver;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Mono;


/**
 * Default {@link MongoExecutionContext} backed directly by the MongoDB Reactive Streams Driver.
 * Entity conversion uses the database codec registry with the driver's automatic POJO codec as a
 * fallback.
 */
public class DriverMongoExecutionContext implements MongoExecutionContext {

	private final MongoClient mongoClient;

	private final MongoDatabase mongoDatabase;

	private final CodecRegistry codecRegistry;

	private final Function<Class<?>, String> collectionNameResolver;

	private final Function<Object, Object> idResolver;

	public DriverMongoExecutionContext(
										MongoClient mongoClient,
										MongoDatabase mongoDatabase
	) {

		this(
			mongoClient,
			mongoDatabase,
			type -> Introspector.decapitalize( type.getSimpleName() ),
			MongoIdFieldResolver::getIdValue
		);

	}

	public DriverMongoExecutionContext(
										MongoClient mongoClient,
										MongoDatabase mongoDatabase,
										Function<Class<?>, String> collectionNameResolver
	) {

		this(
			mongoClient,
			mongoDatabase,
			collectionNameResolver,
			MongoIdFieldResolver::getIdValue
		);

	}

	public DriverMongoExecutionContext(
										MongoClient mongoClient,
										MongoDatabase mongoDatabase,
										Function<Class<?>, String> collectionNameResolver,
										Function<Object, Object> idResolver
	) {

		this.mongoClient = Objects.requireNonNull( mongoClient, "mongoClient must not be null" );
		this.mongoDatabase = Objects.requireNonNull( mongoDatabase, "mongoDatabase must not be null" );
		this.codecRegistry = MongoEntityCodecSupport.withPojoFallback( mongoDatabase.getCodecRegistry() );
		this.collectionNameResolver = Objects.requireNonNull( collectionNameResolver, "collectionNameResolver must not be null" );
		this.idResolver = Objects.requireNonNull( idResolver, "idResolver must not be null" );

	}

	/**
	 * Wraps a collection-name resolver with a per-entity-class cache.
	 * <p>Use this only when the supplied resolver is stable for the lifetime of the application.
	 * Tenant-, request-, time-, or context-dependent resolvers must not be cached this way.</p>
	 *
	 * @param collectionNameResolver
	 *            the stable resolver to cache
	 * 
	 * @return a resolver cached by entity class
	 */
	public static Function<Class<?>, String> cachedCollectionNameResolver(
		Function<Class<?>, String> collectionNameResolver
	) {

		Function<Class<?>, String> resolver = Objects
			.requireNonNull(
				collectionNameResolver,
				"collectionNameResolver must not be null"
			);
		ClassValue<String> cache = new ClassValue<>() {

			@Override
			protected String computeValue(
				Class<?> entityClass
			) {

				return Objects
					.requireNonNull(
						resolver.apply( entityClass ),
						"collectionNameResolver must not return null"
					);

			}

		};

		return entityClass -> cache
			.get(
				Objects.requireNonNull( entityClass, "entityClass must not be null" )
			);

	}

	@Override
	public Mono<MongoDatabase> getDatabase() { return Mono.just( mongoDatabase ); }

	@Override
	public Mono<ClientSession> startSession() {

		return Mono.from( mongoClient.startSession() );

	}

	@Override
	public String getCollectionName(
		Class<?> entityClass
	) {

		return collectionNameResolver.apply( entityClass );

	}

	@Override
	public Document write(
		Object source
	) {

		return MongoEntityCodecSupport.write( codecRegistry, source );

	}

	@Override
	public <T> T read(
		Class<T> targetType, Document source
	) {

		return MongoEntityCodecSupport.read( codecRegistry, targetType, source );

	}

	@Override
	public Object getId(
		Object entity
	) {

		Object id = idResolver.apply( entity );
		return id instanceof String stringId && ObjectId.isValid( stringId )
			? new ObjectId( stringId )
			: id;

	}

	@Override
	public void setId(
		Object entity, Object id
	) {

		MongoIdFieldResolver.setIdValue( entity, id );

	}

	@Override
	public Object getNative() { return mongoDatabase; }

	@Override
	public <T> T getNative(
		Class<T> nativeType
	) {

		if (nativeType.isInstance( mongoDatabase )) { return nativeType.cast( mongoDatabase ); }

		if (nativeType.isInstance( mongoClient )) { return nativeType.cast( mongoClient ); }

		return MongoExecutionContext.super.getNative( nativeType );

	}

}
