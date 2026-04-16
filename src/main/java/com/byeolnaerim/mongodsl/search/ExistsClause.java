package com.byeolnaerim.mongodsl.search;

import org.bson.Document;

/**
 * Strongly typed Atlas Search {@code exists} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class ExistsClause<K> extends AbstractSearchOperator {

	private String path;

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public ExistsClause<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
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
	public ExistsClause<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "exists";
	}

	@Override
	public Document toDocument() {

		if (this.path == null || this.path.isBlank()) {
			throw new IllegalStateException( "exists.path is required" );

		}

		Document body = new Document( "path", this.path );
		applyScore( body );
		return new Document( operatorName(), body );

	}
}
