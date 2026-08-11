package com.byeolnaerim.mongodsl.spi;

import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.Objects;
import org.bson.Document;
import reactor.core.publisher.Mono;

/**
 * Resolves the native MongoDB execution resources and entity mapping policy used by the DSL.
 * <p>This is intentionally not a MongoDB CRUD/template abstraction. Query, aggregation, update,
 * and bulk execution remain inside {@code ReactiveMongoDsl} and are executed through the MongoDB
 * Reactive Streams Driver. Implementations only supply the database/session handles, entity
 * mapping metadata, and an optional environment-specific native object.</p>
 */
public interface MongoExecutionContext {

    /** Returns the MongoDB database handle used by this context. */
    Mono<MongoDatabase> getDatabase();

    /** Starts a client session associated with this context. */
    Mono<ClientSession> startSession();

    /** Resolves the collection used for the given mapped entity type. */
    String getCollectionName(Class<?> entityClass);

    /** Maps an application value to a BSON document. */
    Document write(Object source);

    /** Maps a BSON document to an application value. */
    <T> T read(Class<T> targetType, Document source);

    /** Returns the logical MongoDB identifier value for the entity, or {@code null} when absent. */
    Object getId(Object entity);

    /**
     * Applies a generated identifier back to an entity after an insert.
     * Implementations that use immutable entities may intentionally leave this as a no-op.
     */
    default void setId(Object entity, Object id) {}

    /**
     * Returns the environment-specific native object represented by this context.
     * Framework integrations can expose their own native Mongo object here without
     * introducing that framework dependency into this library.
     */
    Object getNative();

    /** Returns the native object cast to the requested type. */
    default <T> T getNative(Class<T> nativeType) {
        Objects.requireNonNull(nativeType, "nativeType must not be null");
        Object nativeObject = getNative();
        if (!nativeType.isInstance(nativeObject)) {
            throw new IllegalArgumentException(
                "Native Mongo object is " +
                    (nativeObject == null ? "null" : nativeObject.getClass().getName()) +
                    ", not " + nativeType.getName()
            );
        }
        return nativeType.cast(nativeObject);
    }
}
