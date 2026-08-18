package com.byeolnaerim.mongodsl.internal;

import com.mongodb.MongoClientSettings;
import java.util.Collection;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

/** Internal BSON materialization helpers. No entity/property mapping is performed here. */
public final class MongoBsonSupport {

    private MongoBsonSupport() {}

    public static Document toDocument(Bson bson) {
        return toDocument(bson, MongoClientSettings.getDefaultCodecRegistry());
    }

    public static Document toDocument(Bson bson, CodecRegistry codecRegistry) {
        if (bson == null) {
            return new Document();
        }
        if (bson instanceof Document document) {
            return new Document(document);
        }
        BsonDocument bsonDocument = bson.toBsonDocument(Document.class, codecRegistry);
        return new DocumentCodec(codecRegistry).decode(
            new BsonDocumentReader(bsonDocument),
            DecoderContext.builder().build()
        );
    }

    public static List<Document> toDocuments(Collection<? extends Bson> values) {
        return values == null ? List.of() : values.stream().map(MongoBsonSupport::toDocument).toList();
    }
}
