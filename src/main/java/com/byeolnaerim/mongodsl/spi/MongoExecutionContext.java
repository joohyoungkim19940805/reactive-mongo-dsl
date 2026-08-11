package com.byeolnaerim.mongodsl.spi;

import com.byeolnaerim.mongodsl.internal.MongoDocumentMappingSupport;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.List;
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
     * Maps an application property path to the BSON field path used by the target collection.
     * Implementations backed by a mapping framework can override this to preserve aliases such
     * as an application {@code id} property mapped to MongoDB {@code _id}.
     */
    default String getMappedFieldName(Class<?> entityClass, String fieldName) {
        return fieldName;
    }

    /** Maps an ordinary MongoDB filter before native driver execution. */
    default Document mapQuery(Class<?> entityClass, Document query) {
        return MongoDocumentMappingSupport.mapFilter(query, field -> getMappedFieldName(entityClass, field));
    }

    /** Maps a sort document before native driver execution. */
    default Document mapSort(Class<?> entityClass, Document sort) {
        return MongoDocumentMappingSupport.mapFieldDocument(sort, field -> getMappedFieldName(entityClass, field));
    }

    /** Maps a projection document before native driver execution. */
    default Document mapProjection(Class<?> entityClass, Document projection) {
        return MongoDocumentMappingSupport.mapFieldDocument(projection, field -> getMappedFieldName(entityClass, field));
    }

    /** Maps a classic update document before native driver execution. */
    default Document mapUpdate(Class<?> entityClass, Document update) {
        return MongoDocumentMappingSupport.mapUpdate(update, field -> getMappedFieldName(entityClass, field));
    }

    /** Maps an update aggregation pipeline before native driver execution. */
    default List<Document> mapUpdatePipeline(Class<?> entityClass, List<Document> pipeline) {
        return MongoDocumentMappingSupport.mapPipeline(pipeline, field -> getMappedFieldName(entityClass, field));
    }

    /** Maps an aggregation pipeline before native driver execution. */
    default List<Document> mapAggregationPipeline(Class<?> entityClass, List<Document> pipeline) {
        return MongoDocumentMappingSupport.mapPipeline(
            pipeline,
            filter -> mapQuery(entityClass, filter),
            field -> getMappedFieldName(entityClass, field)
        );
    }

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
