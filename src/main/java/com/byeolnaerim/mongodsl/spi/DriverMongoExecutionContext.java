package com.byeolnaerim.mongodsl.spi;

import com.byeolnaerim.mongodsl.internal.MongoIdFieldResolver;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.beans.Introspector;
import java.util.Objects;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import reactor.core.publisher.Mono;

/**
 * Default {@link MongoExecutionContext} backed directly by the MongoDB Reactive Streams Driver.
 * Entity conversion uses the {@link CodecRegistry} configured on the supplied database.
 */
public class DriverMongoExecutionContext implements MongoExecutionContext {

    private final MongoClient mongoClient;
    private final MongoDatabase mongoDatabase;
    private final Function<Class<?>, String> collectionNameResolver;
    private final Function<Object, Object> idResolver;

    public DriverMongoExecutionContext(
        MongoClient mongoClient,
        MongoDatabase mongoDatabase
    ) {
        this(
            mongoClient,
            mongoDatabase,
            type -> Introspector.decapitalize(type.getSimpleName()),
            MongoIdFieldResolver::getIdValue
        );
    }

    public DriverMongoExecutionContext(
        MongoClient mongoClient,
        MongoDatabase mongoDatabase,
        Function<Class<?>, String> collectionNameResolver,
        Function<Object, Object> idResolver
    ) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient must not be null");
        this.mongoDatabase = Objects.requireNonNull(mongoDatabase, "mongoDatabase must not be null");
        this.collectionNameResolver = Objects.requireNonNull(collectionNameResolver, "collectionNameResolver must not be null");
        this.idResolver = Objects.requireNonNull(idResolver, "idResolver must not be null");
    }

    @Override
    public Mono<MongoDatabase> getDatabase() {
        return Mono.just(mongoDatabase);
    }

    @Override
    public Mono<ClientSession> startSession() {
        return Mono.from(mongoClient.startSession());
    }

    @Override
    public String getCollectionName(Class<?> entityClass) {
        return collectionNameResolver.apply(entityClass);
    }

    @Override
    public Document write(Object source) {
        Objects.requireNonNull(source, "source must not be null");
        CodecRegistry codecRegistry = mongoDatabase.getCodecRegistry();
        BsonDocument bson = new BsonDocument();
        @SuppressWarnings("unchecked")
        Codec<Object> codec = (Codec<Object>) codecRegistry.get(source.getClass());
        codec.encode(
            new BsonDocumentWriter(bson),
            source,
            EncoderContext.builder().isEncodingCollectibleDocument(true).build()
        );
        return new DocumentCodec(codecRegistry).decode(
            new BsonDocumentReader(bson),
            DecoderContext.builder().build()
        );
    }

    @Override
    public <T> T read(Class<T> targetType, Document source) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        CodecRegistry codecRegistry = mongoDatabase.getCodecRegistry();
        BsonDocument bson = new BsonDocument();
        new DocumentCodec(codecRegistry).encode(
            new BsonDocumentWriter(bson),
            source,
            EncoderContext.builder().build()
        );
        return codecRegistry.get(targetType).decode(
            new BsonDocumentReader(bson),
            DecoderContext.builder().build()
        );
    }

    @Override
    public Object getId(Object entity) {
        return idResolver.apply(entity);
    }

    @Override
    public void setId(Object entity, Object id) {
        MongoIdFieldResolver.setIdValue(entity, id);
    }

    @Override
    public Object getNative() {
        return mongoDatabase;
    }

    @Override
    public <T> T getNative(Class<T> nativeType) {
        if (nativeType.isInstance(mongoDatabase)) {
            return nativeType.cast(mongoDatabase);
        }
        if (nativeType.isInstance(mongoClient)) {
            return nativeType.cast(mongoClient);
        }
        return MongoExecutionContext.super.getNative(nativeType);
    }
}
