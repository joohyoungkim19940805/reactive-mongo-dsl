package com.byeolnaerim.mongodsl.internal;


import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;


/** Utility class for resolving the identifier field of a Mongo entity. */
public final class MongoIdFieldResolver {

	private static final ClassValue<Optional<IdFieldMetadata>> ID_FIELDS = new ClassValue<>() {

		@Override
		protected Optional<IdFieldMetadata> computeValue(
			Class<?> entityClass
		) {

			Field namedField = null;
			Class<?> currentClass = entityClass;

			while (currentClass != null && currentClass != Object.class) {

				for (Field field : currentClass.getDeclaredFields()) {

					if (field.isAnnotationPresent( BsonId.class )) {
						field.setAccessible( true );
						return Optional
							.of(
								new IdFieldMetadata(
									field,
									! Modifier.isFinal( field.getModifiers() ),
									field.getType() == String.class,
									field.getType() == ObjectId.class
								)
							);

					}

					if (namedField == null && ("id".equals( field.getName() ) || "_id".equals( field.getName() ))) {
						namedField = field;

					}

				}

				currentClass = currentClass.getSuperclass();

			}

			if (namedField == null) { return Optional.empty(); }

			namedField.setAccessible( true );
			return Optional
				.of(
					new IdFieldMetadata(
						namedField,
						! Modifier.isFinal( namedField.getModifiers() ),
						namedField.getType() == String.class,
						namedField.getType() == ObjectId.class
					)
				);

		}

	};

	private MongoIdFieldResolver() {}

	/**
	 * Resolves a native {@link BsonId}-annotated field, then falls back to {@code id} or {@code _id}.
	 */
	public static Field findIdField(
		Class<?> entityClass
	) {

		Field idField = findIdFieldOrNull( entityClass );

		if (idField != null) { return idField; }

		throw new IllegalArgumentException(
			"No @BsonId, 'id', or '_id' field found in class hierarchy for " + entityClass.getName()
		);

	}

	/** Returns the cached identifier field metadata when the class exposes an identifier field. */
	public static Field findIdFieldOrNull(
		Class<?> entityClass
	) {

		return ID_FIELDS
			.get( Objects.requireNonNull( entityClass, "entityClass must not be null" ) )
			.map( IdFieldMetadata::field )
			.orElse( null );

	}

	public static Object getIdValue(
		Object entity
	) {

		if (entity == null) { return null; }

		IdFieldMetadata metadata = ID_FIELDS.get( entity.getClass() ).orElse( null );

		if (metadata == null) { return null; }

		try {
			return metadata.field().get( entity );

		} catch (IllegalAccessException e) {
			throw new IllegalStateException( "Failed to read Mongo identifier", e );

		}

	}

	public static void setIdValue(
		Object entity, Object id
	) {

		if (entity == null) { return; }

		IdFieldMetadata metadata = ID_FIELDS.get( entity.getClass() ).orElse( null );

		if (metadata == null || ! metadata.writable()) { return; }

		try {
			Object value = id;

			if (id instanceof ObjectId objectId && metadata.stringType()) {
				value = objectId.toHexString();

			} else if (id instanceof String stringId && metadata.objectIdType() && ObjectId.isValid( stringId )) {
				value = new ObjectId( stringId );

			}

			if (value == null || metadata.field().getType().isInstance( value )) {
				metadata.field().set( entity, value );

			} else {
				throw new IllegalArgumentException(
					"Generated Mongo identifier type " + value.getClass().getName() + " cannot be assigned to " + metadata.field().getType().getName()
				);

			}

		} catch (IllegalArgumentException ignored) {

			// Immutable/no-id/incompatible-id types may intentionally not expose an assignable identifier
			// field.
		} catch (IllegalAccessException e) {
			throw new IllegalStateException( "Failed to set Mongo identifier", e );

		}

	}

	private record IdFieldMetadata(
		Field field,
		boolean writable,
		boolean stringType,
		boolean objectIdType
	) {}

}
