package com.byeolnaerim.mongodsl.search;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import com.mongodb.client.model.search.AutocompleteSearchOperator;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.FuzzySearchOptions;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code autocomplete} operator backed by MongoDB driver's search API.
 */
public final class AutocompleteClause extends AbstractSearchOperator {

	private FieldSearchPath path;

	private List<String> queries;

	private SearchTokenOrder tokenOrder;

	private FuzzySearchOptions fuzzy;

	/**
	 * Sets the autocomplete path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public AutocompleteClause path(
		String path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public AutocompleteClause path(
		Enum<?> path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public AutocompleteClause path(
		FieldSearchPath path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Fallback for custom path wrappers. Common callers should prefer String, Enum, or FieldSearchPath.
	 */
	public AutocompleteClause path(
		Object path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Sets a single autocomplete query.
	 *
	 * @param query
	 *            the query text
	 *
	 * @return this builder
	 */
	public AutocompleteClause query(
		String query
	) {

		this.queries = List.of( Objects.requireNonNull( query, "query" ) );
		return this;

	}

	/**
	 * Sets multiple autocomplete queries.
	 *
	 * @param queries
	 *            the query texts
	 *
	 * @return this builder
	 */
	public AutocompleteClause queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) { throw new IllegalArgumentException( "queries must not be empty" ); }

		this.queries = new ArrayList<>( queries );
		return this;

	}

	/**
	 * Sets the token-order behavior.
	 *
	 * @param tokenOrder
	 *            the token-order behavior
	 *
	 * @return this builder
	 */
	public AutocompleteClause tokenOrder(
		SearchTokenOrder tokenOrder
	) {

		this.tokenOrder = tokenOrder;
		return this;

	}

	/**
	 * Enables fuzzy matching.
	 *
	 * @param maxEdits
	 *            maximum edit distance
	 * @param prefixLength
	 *            number of exact prefix characters
	 * @param maxExpansions
	 *            maximum number of variations
	 *
	 * @return this builder
	 */
	public AutocompleteClause fuzzy(
		int maxEdits, int prefixLength, int maxExpansions
	) {

		if (maxEdits < 1 || maxEdits > 2) { throw new IllegalArgumentException( "maxEdits must be 1 or 2" ); }

		if (prefixLength < 0) { throw new IllegalArgumentException( "prefixLength must be >= 0" ); }

		if (maxExpansions <= 0) { throw new IllegalArgumentException( "maxExpansions must be > 0" ); }

		this.fuzzy = FuzzySearchOptions
			.fuzzySearchOptions()
			.maxEdits( maxEdits )
			.prefixLength( prefixLength )
			.maxExpansions( maxExpansions );
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
	public AutocompleteClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public AutocompleteClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "autocomplete";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.path == null) { throw new IllegalStateException( "autocomplete.path is required" ); }

		if (this.queries == null || this.queries.isEmpty()) { throw new IllegalStateException( "autocomplete.query is required" ); }

		AutocompleteSearchOperator operator = SearchOperator.autocomplete( this.path, this.queries );

		if (this.tokenOrder == SearchTokenOrder.ANY) {
			operator = operator.anyTokenOrder();

		} else if (this.tokenOrder == SearchTokenOrder.SEQUENTIAL) {
			operator = operator.sequentialTokenOrder();

		}

		if (this.fuzzy != null) {
			operator = operator.fuzzy( this.fuzzy );

		}

		return applyScore( operator );

	}

}
