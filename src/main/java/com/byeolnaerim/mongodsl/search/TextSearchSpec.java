package com.byeolnaerim.mongodsl.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import org.bson.Document;

/**
 * Strongly typed Atlas Search {@code text} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class TextSearchSpec<K> extends AbstractSearchOperator {

	private Object path;

	private Object query;

	private SearchFuzzy fuzzy;

	private SearchMatchCriteria matchCriteria;

	private String synonyms;

	/**
	 * Sets a single search path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
		return this;
	}

	/**
	 * Sets multiple search paths.
	 *
	 * @param paths
	 *            the path inputs
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> paths(
		Collection<K> paths
	) {
		this.path = SearchPathResolver.resolveAll( paths );
		return this;
	}

	/**
	 * Sets a single text query.
	 *
	 * @param query
	 *            the query text
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> query(
		String query
	) {
		this.query = Objects.requireNonNull( query, "query" );
		return this;
	}

	/**
	 * Sets multiple text queries.
	 *
	 * @param queries
	 *            the query texts
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) {
			throw new IllegalArgumentException( "queries must not be empty" );

		}

		this.query = new ArrayList<>( queries );
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
	public TextSearchSpec<K> fuzzy(
		int maxEdits,
		int prefixLength,
		int maxExpansions
	) {
		this.fuzzy = SearchFuzzy.of( maxEdits, prefixLength, maxExpansions );
		return this;
	}

	/**
	 * Sets the token match criteria.
	 *
	 * @param matchCriteria
	 *            the match criteria
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> matchCriteria(
		SearchMatchCriteria matchCriteria
	) {
		this.matchCriteria = matchCriteria;
		return this;
	}

	/**
	 * Sets the Atlas Search synonym mapping name.
	 *
	 * @param synonyms
	 *            the synonym mapping name
	 *
	 * @return this builder
	 */
	public TextSearchSpec<K> synonyms(
		String synonyms
	) {
		this.synonyms = synonyms;
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
	public TextSearchSpec<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "text";
	}

	@Override
	public Document toDocument() {

		if (this.path == null) {
			throw new IllegalStateException( "text.path is required" );

		}

		if (this.query == null) {
			throw new IllegalStateException( "text.query is required" );

		}

		if (this.fuzzy != null && this.synonyms != null && ! this.synonyms.isBlank()) {
			throw new IllegalStateException( "text.fuzzy and text.synonyms cannot be used together" );

		}

		Document body = new Document()
			.append( "path", this.path )
			.append( "query", this.query );

		if (this.fuzzy != null) {
			body.append( "fuzzy", this.fuzzy.toDocument() );

		}

		if (this.matchCriteria != null) {
			body.append( "matchCriteria", this.matchCriteria.getValue() );

		}

		if (this.synonyms != null && ! this.synonyms.isBlank()) {
			body.append( "synonyms", this.synonyms );

		}

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
