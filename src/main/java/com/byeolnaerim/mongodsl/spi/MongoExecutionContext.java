package com.byeolnaerim.mongodsl.spi;


import java.util.Objects;
import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoEntityCodecSupport;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Mono;


/**
 * Supplies the runtime resources and entity conversion policy required by {@code ReactiveMongoDsl}.
 * <p>The execution context intentionally does <strong>not</strong> map query fields, sort fields,
 * update documents, or aggregation pipelines. String field names supplied to DSL convenience APIs
 * are MongoDB field names; only the conventional {@code id} path segment is normalized to MongoDB
 * {@code _id}. Raw MongoDB driver {@code Bson} values are always passed through unchanged.</p>
 * <p>Entity conversion has a MongoDB Java Driver POJO-codec default. Applications that need a
 * framework-specific mapping model, such as Spring Data MongoDB converters, may override
 * {@link #write(Object)} and {@link #read(Class, Document)}.</p>
 */
public interface MongoExecutionContext {

	/** Returns the MongoDB database handle used by this context. */
	Mono<MongoDatabase> getDatabase();

	/** Starts a client session associated with this context. */
	Mono<ClientSession> startSession();

	/**
	 * Resolves the collection used for the given entity type.
	 * <p>The result is allowed to be dynamic. The DSL intentionally does not cache this method
	 * globally because framework or application adapters may resolve collection names from
	 * tenant, request, environment, or expression state.</p>
	 */
	String getCollectionName(
		Class<?> entityClass
	);

	/** Converts an application entity/value into the BSON document persisted by MongoDB. */
	default Document write(
		Object source
	) {

		return MongoEntityCodecSupport.write( MongoEntityCodecSupport.defaultCodecRegistry(), source );

	}

	/** Converts a BSON document returned by MongoDB into the requested application type. */
	default <T> T read(
		Class<T> targetType, Document source
	) {

		return MongoEntityCodecSupport.read( MongoEntityCodecSupport.defaultCodecRegistry(), targetType, source );

	}

	/** Returns the MongoDB identifier value for the entity, or {@code null} when absent. */
	Object getId(
		Object entity
	);

	/** Applies a generated MongoDB identifier back to the entity when possible. */
	default void setId(
		Object entity, Object id
	) {}

	/**
	 * Returns an environment-specific native object represented by this context.
	 * Framework adapters can expose their own native Mongo object without leaking that framework
	 * dependency into the DSL core.
	 */
	Object getNative();

	/** Returns the native object cast to the requested type. */
	default <T> T getNative(
		Class<T> nativeType
	) {

		Objects.requireNonNull( nativeType, "nativeType must not be null" );
		Object nativeObject = getNative();

		if (! nativeType.isInstance( nativeObject )) {
			throw new IllegalArgumentException(
				"Native Mongo object is " + (nativeObject == null ? "null" : nativeObject.getClass().getName()) + ", not " + nativeType.getName()
			);

		}

		return nativeType.cast( nativeObject );

	}

}
