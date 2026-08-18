package com.byeolnaerim.mongodsl.search;


import java.util.Objects;
import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchScore;
import com.mongodb.client.model.search.SearchScoreExpression;


/**
 * Small Atlas Search score conveniences backed directly by MongoDB driver's
 * {@link SearchScore} API.
 * <p>This class intentionally covers only common application-level conveniences. Advanced
 * score expressions stay driver-native so this library does not maintain a parallel scoring
 * expression language.</p>
 */
public final class SearchScoreSpec {

	private final SearchScore score;

	private SearchScoreSpec(
							SearchScore score
	) {

		this.score = score;

	}

	/**
	 * Creates a constant boost score.
	 *
	 * @param value
	 *            the boost value
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec boost(
		double value
	) {

		if (value <= 0d) { throw new IllegalArgumentException( "boost value must be > 0" ); }

		return new SearchScoreSpec( SearchScore.boost( (float) value ) );

	}

	/** Creates a boost score whose source is another field path. */
	public static SearchScoreSpec boostByPath(
		String path
	) {

		return new SearchScoreSpec( SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ) );

	}

	/** Creates a boost score whose source is an enum field path. Enum.toString() is used. */
	public static SearchScoreSpec boostByPath(
		Enum<?> path
	) {

		return new SearchScoreSpec( SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ) );

	}

	/** Uses a MongoDB driver-native field path directly. */
	public static SearchScoreSpec boostByPath(
		FieldSearchPath path
	) {

		return new SearchScoreSpec( SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ) );

	}

	/** Fallback for custom field-path wrappers. */
	public static SearchScoreSpec boostByPath(
		Object path
	) {

		return new SearchScoreSpec( SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ) );

	}

	public static SearchScoreSpec boostByPath(
		String path, double undefined
	) {

		return new SearchScoreSpec(
			SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ).undefined( (float) undefined )
		);

	}

	public static SearchScoreSpec boostByPath(
		Enum<?> path, double undefined
	) {

		return new SearchScoreSpec(
			SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ).undefined( (float) undefined )
		);

	}

	public static SearchScoreSpec boostByPath(
		FieldSearchPath path, double undefined
	) {

		return new SearchScoreSpec(
			SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ).undefined( (float) undefined )
		);

	}

	/** Fallback for custom field-path wrappers. */
	public static SearchScoreSpec boostByPath(
		Object path, double undefined
	) {

		return new SearchScoreSpec(
			SearchScore.boost( SearchPathResolver.resolveFieldPath( path ) ).undefined( (float) undefined )
		);

	}

	/**
	 * Creates a constant score.
	 *
	 * @param value
	 *            the constant score value
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec constant(
		double value
	) {

		return new SearchScoreSpec( SearchScore.constant( (float) value ) );

	}

	/**
	 * Creates a function score from a MongoDB driver-native score expression.
	 * <p>Expression construction intentionally remains in MongoDB Driver. This keeps advanced
	 * scoring available without recreating the driver's expression DSL here.</p>
	 *
	 * @param expression
	 *            the driver-native score expression
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec function(
		SearchScoreExpression expression
	) {

		return new SearchScoreSpec( SearchScore.function( Objects.requireNonNull( expression, "expression" ) ) );

	}

	public SearchScore toSearchScore() {

		return this.score;

	}

	public Document toDocument() {

		return MongoBsonSupport.toDocument( this.score );

	}

}
