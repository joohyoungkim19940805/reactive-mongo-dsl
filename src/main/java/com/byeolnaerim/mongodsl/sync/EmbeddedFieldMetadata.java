package com.byeolnaerim.mongodsl.sync;


import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;


record EmbeddedFieldMetadata(String mongoPath, EmbeddedSyncCardinality cardinality) {

	static EmbeddedFieldMetadata resolve(
		Class<?> targetClass, Class<?> sourceClass, String explicitPath
	) {

		if (explicitPath != null && ! explicitPath.isBlank())
			return resolveExplicit( targetClass, sourceClass, explicitPath.trim() );

		List<Field> matches = allFields( targetClass ).stream().filter( field -> cardinality( field, sourceClass ) != null ).toList();
		if (matches.isEmpty())
			throw new IllegalArgumentException(
				"No embedded " + sourceClass.getName() + " field found in " + targetClass.getName()
			);
		if (matches.size() > 1)
			throw new IllegalArgumentException(
				"Multiple embedded " + sourceClass.getName() + " fields found in " + targetClass.getName() + ": "
					+ matches.stream().map( Field::getName ).toList() + ". Specify into(..., fieldName)."
			);

		Field field = matches.get( 0 );
		return new EmbeddedFieldMetadata( MongoFieldNameSupport.toMongoField( field.getName() ), cardinality( field, sourceClass ) );

	}

	private static EmbeddedFieldMetadata resolveExplicit(
		Class<?> targetClass, Class<?> sourceClass, String path
	) {

		String[] segments = path.split( "\\." );
		Class<?> currentType = targetClass;
		Field field = null;

		for (int i = 0; i < segments.length; i++) {
			field = findField( currentType, segments[i] );
			if (field == null)
				throw new IllegalArgumentException( "Embedded field path not found: " + targetClass.getName() + "." + path );
			if (i < segments.length - 1) {
				if (Collection.class.isAssignableFrom( field.getType() ) || Map.class.isAssignableFrom( field.getType() ))
					throw new IllegalArgumentException( "Collection/map intermediate embedded paths are not supported: " + path );
				currentType = field.getType();

			}

		}

		EmbeddedSyncCardinality cardinality = cardinality( field, sourceClass );
		if (cardinality == null)
			throw new IllegalArgumentException(
				"Embedded field " + targetClass.getName() + "." + path + " does not contain " + sourceClass.getName()
			);
		return new EmbeddedFieldMetadata( MongoFieldNameSupport.toMongoField( path ), cardinality );

	}

	private static EmbeddedSyncCardinality cardinality(
		Field field, Class<?> sourceClass
	) {

		if (Modifier.isStatic( field.getModifiers() ) || field.isSynthetic())
			return null;
		if (field.getType().isAssignableFrom( sourceClass ))
			return EmbeddedSyncCardinality.SINGLE;
		if (Collection.class.isAssignableFrom( field.getType() ))
			return genericContains( field.getGenericType(), sourceClass, 0 ) ? EmbeddedSyncCardinality.COLLECTION : null;
		if (Map.class.isAssignableFrom( field.getType() ))
			return genericContains( field.getGenericType(), sourceClass, 1 ) ? EmbeddedSyncCardinality.MAP : null;
		return null;

	}

	private static boolean genericContains(
		Type genericType, Class<?> sourceClass, int argumentIndex
	) {

		if (! (genericType instanceof ParameterizedType parameterized))
			return false;
		Type[] arguments = parameterized.getActualTypeArguments();
		if (arguments.length <= argumentIndex)
			return false;
		Type type = arguments[argumentIndex];
		if (type instanceof Class<?> clazz)
			return clazz.isAssignableFrom( sourceClass );
		if (type instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> clazz)
			return clazz.isAssignableFrom( sourceClass );
		return false;

	}

	private static List<Field> allFields(
		Class<?> type
	) {

		List<Field> fields = new ArrayList<>();
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
			fields.addAll( List.of( current.getDeclaredFields() ) );
		return fields;

	}

	private static Field findField(
		Class<?> type, String name
	) {

		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			try {
				return current.getDeclaredField( name );
			} catch (NoSuchFieldException ignored) {}

		}
		return null;

	}

}
