package com.byeolnaerim.mongodsl.spi;

import com.byeolnaerim.mongodsl.internal.MongoIdFieldResolver;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.beans.Introspector;
import java.lang.reflect.Field;
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
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
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
        Document document = new DocumentCodec(codecRegistry).decode(
            new BsonDocumentReader(bson),
            DecoderContext.builder().build()
        );
        normalizeIdentifierForWrite(source.getClass(), document);
        return document;
    }

    @Override
    public <T> T read(Class<T> targetType, Document source) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        CodecRegistry codecRegistry = mongoDatabase.getCodecRegistry();
        Document mappedSource = normalizeIdentifierForRead(targetType, source);
        BsonDocument bson = new BsonDocument();
        new DocumentCodec(codecRegistry).encode(
            new BsonDocumentWriter(bson),
            mappedSource,
            EncoderContext.builder().build()
        );
        return codecRegistry.get(targetType).decode(
            new BsonDocumentReader(bson),
            DecoderContext.builder().build()
        );
    }

    @Override
    public Object getId(Object entity) {
        Object id = idResolver.apply(entity);
        if (!(id instanceof String stringId) || !ObjectId.isValid(stringId)) {
            return id;
        }
        Field idField = findIdFieldOrNull(entity.getClass());
        return idField != null && !idField.isAnnotationPresent(BsonId.class)
            ? new ObjectId(stringId)
            : id;
    }

    @Override
    public Document mapQuery(Class<?> entityClass, Document query) {
        Document mapped = MongoExecutionContext.super.mapQuery(entityClass, query);
        Field idField = findIdFieldOrNull(entityClass);
        return idField != null && idField.getType() == String.class && !idField.isAnnotationPresent(BsonId.class)
            ? mapObjectIdQueryValues(mapped)
            : mapped;
    }

    @Override
    public String getMappedFieldName(Class<?> entityClass, String fieldName) {
        if (fieldName == null || fieldName.isBlank() || fieldName.startsWith("$")) {
            return fieldName;
        }

        int dot = fieldName.indexOf('.');
        String head = dot < 0 ? fieldName : fieldName.substring(0, dot);
        String tail = dot < 0 ? "" : fieldName.substring(dot);
        Field field = findField(entityClass, head);
        if (field == null) {
            return fieldName;
        }
        if (field.isAnnotationPresent(BsonId.class) || isResolvedIdField(entityClass, field)) {
            return "_id" + tail;
        }
        BsonProperty bsonProperty = field.getAnnotation(BsonProperty.class);
        if (bsonProperty != null && !bsonProperty.value().isBlank()) {
            return bsonProperty.value() + tail;
        }
        return fieldName;
    }

    @Override
    public void setId(Object entity, Object id) {
        MongoIdFieldResolver.setIdValue(entity, id);
    }

    private static void normalizeIdentifierForWrite(Class<?> entityClass, Document document) {
        Field idField = findIdFieldOrNull(entityClass);
        if (idField == null) {
            return;
        }

        Object id = document.get("_id");
        if (id == null && !"_id".equals(idField.getName()) && document.containsKey(idField.getName())) {
            id = document.remove(idField.getName());
        }
        if (id instanceof String stringId && idField.getType() == String.class && ObjectId.isValid(stringId)) {
            id = new ObjectId(stringId);
        }
        if (id == null) {
            document.remove("_id");
        } else {
            document.put("_id", id);
        }
    }

    private static Document normalizeIdentifierForRead(Class<?> entityClass, Document source) {
        Document document = new Document(source);
        Field idField = findIdFieldOrNull(entityClass);
        if (idField == null || !document.containsKey("_id")) {
            return document;
        }
        Object id = document.get("_id");
        if (id instanceof ObjectId objectId && idField.getType() == String.class) {
            document.put("_id", objectId.toHexString());
        }
        return document;
    }


    private static Document mapObjectIdQueryValues(Document source) {
        Document mapped = new Document();
        source.forEach((key, value) -> {
            if ("_id".equals(key)) {
                mapped.put(key, mapObjectIdConditionValue(value));
            } else if (("$and".equals(key) || "$or".equals(key) || "$nor".equals(key)) && value instanceof java.util.List<?> list) {
                mapped.put(
                    key,
                    list.stream()
                        .map(item -> item instanceof Document document ? mapObjectIdQueryValues(document) : item)
                        .toList()
                );
            } else {
                mapped.put(key, value);
            }
        });
        return mapped;
    }

    private static Object mapObjectIdConditionValue(Object value) {
        if (value instanceof String stringValue) {
            return ObjectId.isValid(stringValue) ? new ObjectId(stringValue) : stringValue;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(DriverMongoExecutionContext::mapObjectIdConditionValue).toList();
        }
        if (value instanceof Document document) {
            Document mapped = new Document();
            document.forEach((operator, nestedValue) -> {
                if ("$regex".equals(operator) || "$options".equals(operator)) {
                    mapped.put(operator, nestedValue);
                } else {
                    mapped.put(operator, mapObjectIdConditionValue(nestedValue));
                }
            });
            return mapped;
        }
        return value;
    }

    private static boolean isResolvedIdField(Class<?> entityClass, Field field) {
        Field idField = findIdFieldOrNull(entityClass);
        return idField != null && idField.getDeclaringClass() == field.getDeclaringClass() && idField.getName().equals(field.getName());
    }

    private static Field findIdFieldOrNull(Class<?> entityClass) {
        try {
            return MongoIdFieldResolver.findIdField(entityClass);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> entityClass, String name) {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
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
