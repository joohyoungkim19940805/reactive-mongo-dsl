package com.byeolnaerim.mongodsl.sync;


import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;


record EmbeddedFieldMetadata(String mongoPath, EmbeddedSyncCardinality cardinality) {

	static EmbeddedFieldMetadata resolve(
		Class<?> targetClass, Class<?> sourceClass, String explicitPath
	) {

		if (explicitPath != null && ! explicitPath.isBlank())
			return resolveExplicit( targetClass, sourceClass, explicitPath.trim() );

		List<Field> matches = allFields( targetClass ).stream().filter( field -> cardinality( targetClass, field, sourceClass ) != null ).toList();
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
		return new EmbeddedFieldMetadata( MongoFieldNameSupport.toMongoField( field.getName() ), cardinality( targetClass, field, sourceClass ) );

	}

	private static EmbeddedFieldMetadata resolveExplicit(
		Class<?> targetClass, Class<?> sourceClass, String path
	) {

		String[] segments = path.split( "\\." );
		Class<?> currentType = targetClass;
		Class<?> fieldOwnerType = targetClass;
		Field field = null;

		for (int i = 0; i < segments.length; i++) {
			fieldOwnerType = currentType;
			field = findField( currentType, segments[i] );
			if (field == null)
				throw new IllegalArgumentException( "Embedded field path not found: " + targetClass.getName() + "." + path );
			if (i < segments.length - 1) {
				if (Collection.class.isAssignableFrom( field.getType() ) || Map.class.isAssignableFrom( field.getType() ))
					throw new IllegalArgumentException( "Collection/map intermediate embedded paths are not supported: " + path );
				currentType = field.getType();

			}

		}

		EmbeddedSyncCardinality cardinality = cardinality( fieldOwnerType, field, sourceClass );
		if (cardinality == null)
			throw new IllegalArgumentException(
				"Embedded field " + targetClass.getName() + "." + path + " does not contain " + sourceClass.getName()
			);
		return new EmbeddedFieldMetadata( MongoFieldNameSupport.toMongoField( path ), cardinality );

	}

	private static EmbeddedSyncCardinality cardinality(
		Class<?> targetClass, Field field, Class<?> sourceClass
	) {

		if (Modifier.isStatic( field.getModifiers() ) || field.isSynthetic())
			return null;
		if (field.getType().isAssignableFrom( sourceClass ))
			return EmbeddedSyncCardinality.SINGLE;
		Map<TypeVariable<?>, Type> typeVariables = resolveTypeVariables( targetClass, field.getDeclaringClass() );
		if (Collection.class.isAssignableFrom( field.getType() ))
			return genericContains( field.getGenericType(), sourceClass, 0, typeVariables ) ? EmbeddedSyncCardinality.COLLECTION : null;
		if (Map.class.isAssignableFrom( field.getType() ))
			return genericContains( field.getGenericType(), sourceClass, 1, typeVariables ) ? EmbeddedSyncCardinality.MAP : null;
		return null;

	}

	private static boolean genericContains(
		Type genericType, Class<?> sourceClass, int argumentIndex, Map<TypeVariable<?>, Type> typeVariables
	) {

		if (! (genericType instanceof ParameterizedType parameterized))
			return false;
		Type[] arguments = parameterized.getActualTypeArguments();
		if (arguments.length <= argumentIndex)
			return false;
		return typeContains( arguments[argumentIndex], sourceClass, typeVariables );

	}

	private static boolean typeContains(
		Type type, Class<?> sourceClass, Map<TypeVariable<?>, Type> typeVariables
	) {

		Type resolved = resolveType( type, typeVariables );
		if (resolved instanceof Class<?> clazz)
			return clazz.isAssignableFrom( sourceClass );
		if (resolved instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz)
			return clazz.isAssignableFrom( sourceClass );
		if (resolved instanceof WildcardType wildcard) {
			for (Type upperBound : wildcard.getUpperBounds())
				if (typeContains( upperBound, sourceClass, typeVariables ))
					return true;
			for (Type lowerBound : wildcard.getLowerBounds())
				if (typeContains( lowerBound, sourceClass, typeVariables ))
					return true;
		}
		if (resolved instanceof TypeVariable<?>)
			return false;
		return false;

	}

	private static Type resolveType(
		Type type, Map<TypeVariable<?>, Type> typeVariables
	) {

		Type current = type;
		while (current instanceof TypeVariable<?> variable) {
			Type resolved = typeVariables.get( variable );
			if (resolved == null || resolved.equals( current ))
				return current;
			current = resolved;
		}
		return current;

	}

	private static Map<TypeVariable<?>, Type> resolveTypeVariables(
		Class<?> targetClass, Class<?> declaringClass
	) {

		Map<TypeVariable<?>, Type> resolved = new HashMap<>();
		Class<?> current = targetClass;
		while (current != null && current != Object.class && current != declaringClass) {
			Type genericSuperclass = current.getGenericSuperclass();
			if (genericSuperclass instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawClass) {
				TypeVariable<?>[] parameters = rawClass.getTypeParameters();
				Type[] arguments = parameterized.getActualTypeArguments();
				for (int i = 0; i < parameters.length; i++)
					resolved.put( parameters[i], resolveType( arguments[i], resolved ) );
				current = rawClass;
				continue;
			}
			if (genericSuperclass instanceof Class<?> rawClass) {
				current = rawClass;
				continue;
			}
			break;
		}
		return resolved;

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
