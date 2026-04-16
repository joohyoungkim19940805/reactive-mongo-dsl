package com.byeolnaerim.mongodsl.search;

import org.bson.Document;

/**
 * Base class for Atlas Search operators that optionally support a {@code score}
 * clause.
 */
abstract class AbstractSearchOperator implements AtlasSearchOperator {

	/**
	 * Optional score specification applied to the current operator.
	 */
	protected SearchScoreSpec score;

	/**
	 * Appends the configured {@code score} clause when present.
	 *
	 * @param body
	 *            the operator body being rendered
	 */
	protected void applyScore(
		Document body
	) {

		if (this.score != null) {
			body.append( "score", this.score.toDocument() );

		}

	}
}
