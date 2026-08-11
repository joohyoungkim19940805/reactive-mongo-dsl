package com.byeolnaerim.mongodsl.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.bson.codecs.pojo.annotations.BsonId;

/** Utility class for resolving the identifier field of a Mongo entity. */
public final class MongoIdFieldResolver {

    private MongoIdFieldResolver() {}

    /** Resolves a native {@link BsonId}-annotated field, then falls back to {@code id} or {@code _id}. */
    public static Field findIdField(Class<?> entityClass) {
        Field namedField = null;
        Class<?> currentClass = entityClass;

        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(BsonId.class)) {
                    field.setAccessible(true);
                    return field;
                }
                if (namedField == null && ("id".equals(field.getName()) || "_id".equals(field.getName()))) {
                    namedField = field;
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        if (namedField != null) {
            namedField.setAccessible(true);
            return namedField;
        }

        throw new IllegalArgumentException(
            "No @BsonId, 'id', or '_id' field found in class hierarchy for " + entityClass.getName()
        );
    }

    public static Object getIdValue(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return findIdField(entity.getClass()).get(entity);
        } catch (IllegalArgumentException ignored) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read Mongo identifier", e);
        }
    }

    public static void setIdValue(Object entity, Object id) {
        if (entity == null) {
            return;
        }
        try {
            Field idField = findIdField(entity.getClass());
            if (!Modifier.isFinal(idField.getModifiers())) {
                idField.set(entity, id);
            }
        } catch (IllegalArgumentException ignored) {
            // Immutable/no-id types may intentionally not expose a writable identifier field.
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to set Mongo identifier", e);
        }
    }
}
