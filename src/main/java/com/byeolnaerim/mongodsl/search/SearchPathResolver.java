package com.byeolnaerim.mongodsl.search;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchPath;


/**
 * Resolves common DSL path inputs into MongoDB driver's Atlas Search path types.
 * <p>String and enum convenience inputs use the shared MongoDB field-name normalization. Enums use
 * {@link Enum#toString()}, so a normal enum resolves to its constant name while an enum overriding
 * {@code toString()} can expose the physical MongoDB field path. Driver-native {@link SearchPath}
 * and {@link FieldSearchPath} instances are preserved exactly and are never rewritten. Other object
 * types are accepted only through the explicit fallback and resolve via
 * {@link Object#toString()}.</p>
 * <p>Wildcard semantics are always explicit. Use {@link SearchPaths#wildcard(String)} or the
 * driver's {@link SearchPath#wildcardPath(String)} instead of relying on string inspection.</p>
 */
public final class SearchPathResolver {

	private SearchPathResolver() {}

	public static String resolve(
		String path
	) {

		return MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) );

	}

	public static String resolve(
		Enum<?> path
	) {

		return MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) );

	}

	public static String resolve(
		FieldSearchPath path
	) {

		return Objects.requireNonNull( path, "path" ).toValue();

	}

	public static String resolve(
		Object path
	) {

		Objects.requireNonNull( path, "path" );

		if (path instanceof String stringPath) { return resolve( stringPath ); }

		if (path instanceof Enum<?> enumPath) { return resolve( enumPath ); }

		if (path instanceof FieldSearchPath fieldSearchPath) { return resolve( fieldSearchPath ); }

		if (path instanceof SearchPath) { throw new IllegalArgumentException( "A concrete FieldSearchPath is required when a path must resolve to one field name." ); }

		return MongoFieldNameSupport.toMongoField( path.toString() );

	}

	public static SearchPath resolveSearchPath(
		String path
	) {

		return SearchPaths.field( path );

	}

	public static SearchPath resolveSearchPath(
		Enum<?> path
	) {

		return SearchPaths.field( path );

	}

	public static SearchPath resolveSearchPath(
		SearchPath path
	) {

		return Objects.requireNonNull( path, "path" );

	}

	public static SearchPath resolveSearchPath(
		Object path
	) {

		if (path instanceof SearchPath searchPath) { return resolveSearchPath( searchPath ); }

		if (path instanceof String stringPath) { return resolveSearchPath( stringPath ); }

		if (path instanceof Enum<?> enumPath) { return resolveSearchPath( enumPath ); }

		return SearchPaths.field( resolve( path ) );

	}

	public static FieldSearchPath resolveFieldPath(
		String path
	) {

		return SearchPaths.field( path );

	}

	public static FieldSearchPath resolveFieldPath(
		Enum<?> path
	) {

		return SearchPaths.field( path );

	}

	public static FieldSearchPath resolveFieldPath(
		FieldSearchPath path
	) {

		return Objects.requireNonNull( path, "path" );

	}

	public static FieldSearchPath resolveFieldPath(
		Object path
	) {

		if (path instanceof FieldSearchPath fieldSearchPath) { return resolveFieldPath( fieldSearchPath ); }

		if (path instanceof String stringPath) { return resolveFieldPath( stringPath ); }

		if (path instanceof Enum<?> enumPath) { return resolveFieldPath( enumPath ); }

		if (path instanceof SearchPath) { throw new IllegalArgumentException( "A concrete FieldSearchPath is required for this operator." ); }

		return SearchPaths.field( resolve( path ) );

	}

	public static List<SearchPath> resolveSearchPaths(
		Collection<?> paths
	) {

		Objects.requireNonNull( paths, "paths" );
		List<SearchPath> result = new ArrayList<>();

		for (Object path : paths) {

			if (path != null) {
				result.add( resolveSearchPath( path ) );

			}

		}

		if (result.isEmpty()) { throw new IllegalArgumentException( "paths must not be empty" ); }

		return result;

	}

}
