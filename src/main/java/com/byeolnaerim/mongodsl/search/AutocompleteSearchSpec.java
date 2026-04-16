package com.byeolnaerim.mongodsl.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import org.bson.Document;

/**
 * Strongly typed Atlas Search {@code autocomplete} operator.
 *
 * <p>Unlike {@code text} and {@code phrase}, Atlas Search expects a single path
 * for {@code autocomplete}, so this builder intentionally exposes only
 * {@link #path(Object)}.</p>
 *
 * @param <K>
 *            the logical path type
 */
public final class AutocompleteSearchSpec<K> extends AbstractSearchOperator {

	private String path;

	private Object query;

	private SearchTokenOrder tokenOrder;

	private SearchFuzzy fuzzy;

	/**
	 * Sets the autocomplete path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public AutocompleteSearchSpec<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
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
	public AutocompleteSearchSpec<K> query(
		String query
	) {
		this.query = Objects.requireNonNull( query, "query" );
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
	public AutocompleteSearchSpec<K> queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) {
			throw new IllegalArgumentException( "queries must not be empty" );

		}

		this.query = new ArrayList<>( queries );
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
	public AutocompleteSearchSpec<K> tokenOrder(
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
	public AutocompleteSearchSpec<K> fuzzy(
		int maxEdits,
		int prefixLength,
		int maxExpansions
	) {
		this.fuzzy = SearchFuzzy.of( maxEdits, prefixLength, maxExpansions );
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
	public AutocompleteSearchSpec<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "autocomplete";
	}

	@Override
	public Document toDocument() {

		if (this.path == null || this.path.isBlank()) {
			throw new IllegalStateException( "autocomplete.path is required" );

		}

		if (this.query == null) {
			throw new IllegalStateException( "autocomplete.query is required" );

		}

		Document body = new Document()
			.append( "path", this.path )
			.append( "query", this.query );

		if (this.tokenOrder != null) {
			body.append( "tokenOrder", this.tokenOrder.getValue() );

		}

		if (this.fuzzy != null) {
			body.append( "fuzzy", this.fuzzy.toDocument() );

		}

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
