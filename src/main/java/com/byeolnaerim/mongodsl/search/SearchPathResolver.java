package com.byeolnaerim.mongodsl.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Resolves DSL path inputs into Atlas Search path strings.
 *
 * <p>This helper mirrors the existing DSL philosophy used by {@code FieldsPair}:
 * callers may pass either enums or strings, while the public API stays strongly
 * typed and expressive.</p>
 */
public final class SearchPathResolver {

	private SearchPathResolver() {}

	/**
	 * Resolves a single path input.
	 *
	 * @param path
	 *            the path input, typically an enum or string
	 *
	 * @return the resolved path string
	 */
	public static String resolve(
		Object path
	) {

		Objects.requireNonNull( path, "path" );

		if (path instanceof Enum<?> enumValue) {
			return enumValue.name();

		}

		return path.toString();

	}

	/**
	 * Resolves multiple path inputs.
	 *
	 * @param paths
	 *            the path inputs
	 *
	 * @return the resolved path strings
	 */
	public static List<String> resolveAll(
		Collection<?> paths
	) {

		Objects.requireNonNull( paths, "paths" );

		List<String> result = new ArrayList<>();

		for (Object path : paths) {
			if (path == null)
				continue;
			result.add( resolve( path ) );

		}

		if (result.isEmpty()) {
			throw new IllegalArgumentException( "paths must not be empty" );

		}

		return result;

	}
}
