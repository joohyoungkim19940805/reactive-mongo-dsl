package com.byeolnaerim.mongodsl.search;


import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code exists} operator backed by MongoDB driver's search API.
 */
public final class ExistsClause extends AbstractSearchOperator {

	private FieldSearchPath path;

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public ExistsClause path(
		String path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public ExistsClause path(
		Enum<?> path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public ExistsClause path(
		FieldSearchPath path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Fallback for custom path wrappers. Common callers should prefer String, Enum, or FieldSearchPath.
	 */
	public ExistsClause path(
		Object path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Sets the score specification.
	 *
	 * @param score
	 *            the score specification
	 *
	 * @return this builder
	 */
	public ExistsClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public ExistsClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "exists";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.path == null) { throw new IllegalStateException( "exists.path is required" ); }

		return applyScore( SearchOperator.exists( this.path ) );

	}

}
