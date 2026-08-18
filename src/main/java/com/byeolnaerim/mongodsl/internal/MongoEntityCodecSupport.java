package com.byeolnaerim.mongodsl.internal;

import static com.mongodb.MongoClientSettings.getDefaultCodecRegistry;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import java.lang.reflect.Field;
import java.util.Objects;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;

/** Default POJO/BSON conversion backed by MongoDB Java Driver codecs. */
public final class MongoEntityCodecSupport {

    private static final CodecRegistry DEFAULT_CODEC_REGISTRY = withPojoFallback(getDefaultCodecRegistry());

    private MongoEntityCodecSupport() {}

    public static CodecRegistry defaultCodecRegistry() {
        return DEFAULT_CODEC_REGISTRY;
    }

    public static CodecRegistry withPojoFallback(CodecRegistry codecRegistry) {
        return fromRegistries(
            Objects.requireNonNull(codecRegistry, "codecRegistry must not be null"),
            fromProviders(PojoCodecProvider.builder().automatic(true).build())
        );
    }

    public static Document write(CodecRegistry codecRegistry, Object source) {
        Objects.requireNonNull(codecRegistry, "codecRegistry must not be null");
        Objects.requireNonNull(source, "source must not be null");

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

    public static <T> T read(CodecRegistry codecRegistry, Class<T> targetType, Document source) {
        Objects.requireNonNull(codecRegistry, "codecRegistry must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(source, "source must not be null");

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

    private static Field findIdFieldOrNull(Class<?> entityClass) {
        try {
            return MongoIdFieldResolver.findIdField(entityClass);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
