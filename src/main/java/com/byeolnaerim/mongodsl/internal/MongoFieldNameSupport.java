package com.byeolnaerim.mongodsl.internal;

import java.util.Arrays;
import java.util.Collection;
import org.bson.types.ObjectId;

/**
 * Minimal MongoDB identifier-field normalization used by string-based DSL convenience APIs.
 *
 * <p>The DSL does not inspect entity metadata or framework annotations. The only convenience
 * mapping is MongoDB's identifier convention: a path segment named {@code id} is emitted as
 * {@code _id}. For that {@code id} alias only, a valid 24-hex String value is also normalized to
 * {@link ObjectId}. Raw driver {@code Bson} values are never rewritten.</p>
 */
public final class MongoFieldNameSupport {

    private MongoFieldNameSupport() {}

    public static String toMongoField(String field) {
        if (field == null || field.isBlank()) {
            return field;
        }

        String[] segments = field.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            if ("id".equals(segments[i])) {
                segments[i] = "_id";
            }
        }
        return String.join(".", segments);
    }

    public static String[] toMongoFields(String... fields) {
        if (fields == null) {
            return null;
        }
        return Arrays.stream(fields).map(MongoFieldNameSupport::toMongoField).toArray(String[]::new);
    }

    public static Object toMongoFieldValue(String field, Object value) {
        if (!usesIdAlias(field)) {
            return value;
        }
        if (value instanceof String stringValue && ObjectId.isValid(stringValue)) {
            return new ObjectId(stringValue);
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(item -> toMongoFieldValue(field, item)).toList();
        }
        return value;
    }

    private static boolean usesIdAlias(String field) {
        return field != null && Arrays.stream(field.split("\\.", -1)).anyMatch("id"::equals);
    }
}
