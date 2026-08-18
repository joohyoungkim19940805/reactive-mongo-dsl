package com.byeolnaerim.mongodsl.search;


import java.util.Objects;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.WildcardSearchPath;


/**
 * Explicit Atlas Search path factories with the DSL's physical field-name normalization.
 * <p>Use {@link #field(String)} for a concrete field and {@link #wildcard(String)} when wildcard
 * path semantics are intended. Plain string DSL path inputs are never auto-promoted to wildcard
 * paths merely because they contain {@code *}.</p>
 */
public final class SearchPaths {

	private SearchPaths() {}

	public static FieldSearchPath field(
		String path
	) {

		return SearchPath
			.fieldPath(
				MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) )
			);

	}

	public static FieldSearchPath field(
		Enum<?> path
	) {

		return SearchPath
			.fieldPath(
				MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) )
			);

	}

	public static WildcardSearchPath wildcard(
		String path
	) {

		return SearchPath
			.wildcardPath(
				MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) )
			);

	}

	public static WildcardSearchPath wildcard(
		Enum<?> path
	) {

		return SearchPath
			.wildcardPath(
				MongoFieldNameSupport.toMongoField( Objects.requireNonNull( path, "path" ) )
			);

	}

}
