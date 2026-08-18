package com.byeolnaerim.mongodsl.vector;

import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import java.util.Objects;

/**
 * Resolves DSL path inputs used by the vector-search package.
 *
 * <p>This mirrors the existing DSL philosophy used by {@code FieldsPair} and
 * the Atlas Search package: callers may pass enums or strings while the public
 * API stays strongly typed.</p>
 */
public final class VectorPathResolver {

	private VectorPathResolver() {}

	/**
	 * Resolves a single logical path into a MongoDB field path.
	 *
	 * @param path
	 *            the logical path input, usually an enum or string
	 *
	 * @return the resolved field path
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

}
