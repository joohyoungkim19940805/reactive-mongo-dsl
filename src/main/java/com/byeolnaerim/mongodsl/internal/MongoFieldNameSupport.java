package com.byeolnaerim.mongodsl.internal;


import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import org.bson.types.ObjectId;


/**
 * Minimal MongoDB identifier-field normalization used by DSL convenience APIs.
 * <p>String field names are used as-is except for MongoDB's identifier alias: a path segment named
 * {@code id} is emitted as {@code _id}. Enum field names use {@link Enum#toString()}, so a plain
 * enum naturally resolves to its constant name while an enum with an overridden {@code toString()}
 * can expose the physical MongoDB field name. Other object types are supported only as a fallback
 * and are resolved through {@link Object#toString()}.</p>
 * <p>For the {@code id} alias only, a valid 24-hex String value is also normalized to
 * {@link ObjectId}. Raw driver {@code Bson} values are never rewritten.</p>
 */
public final class MongoFieldNameSupport {

	private MongoFieldNameSupport() {}

	public static String toMongoField(
		String field
	) {

		if (field == null || field.isBlank()) { return field; }

		String[] segments = field.split( "\\.", -1 );

		for (int i = 0; i < segments.length; i++) {

			if ("id".equals( segments[i] )) {
				segments[i] = "_id";

			}

		}

		return String.join( ".", segments );

	}

	public static String toMongoField(
		Enum<?> field
	) {

		return toMongoField( Objects.requireNonNull( field, "field" ).toString() );

	}

	public static String toMongoField(
		Object field
	) {

		if (field == null) { return null; }

		if (field instanceof String stringField) { return toMongoField( stringField ); }

		if (field instanceof Enum<?> enumField) { return toMongoField( enumField ); }

		return toMongoField( field.toString() );

	}

	public static String[] toMongoFields(
		String... fields
	) {

		if (fields == null) { return null; }

		return Arrays.stream( fields ).map( MongoFieldNameSupport::toMongoField ).toArray( String[]::new );

	}

	public static String[] toMongoFields(
		Enum<?>... fields
	) {

		if (fields == null) { return null; }

		return Arrays.stream( fields ).map( MongoFieldNameSupport::toMongoField ).toArray( String[]::new );

	}

	public static Object toMongoFieldValue(
		Object field, Object value
	) {

		String sourceField = field instanceof Enum<?> enumField
			? enumField.toString()
			: Objects.toString( field, null );

		if (! usesIdAlias( sourceField )) { return value; }

		if (value instanceof String stringValue && ObjectId.isValid( stringValue )) { return new ObjectId( stringValue ); }

		if (value instanceof Collection<?> values) { return values.stream().map( item -> toMongoFieldValue( sourceField, item ) ).toList(); }

		return value;

	}

	private static boolean usesIdAlias(
		String field
	) {

		return field != null && Arrays.stream( field.split( "\\.", -1 ) ).anyMatch( "id"::equals );

	}

}
