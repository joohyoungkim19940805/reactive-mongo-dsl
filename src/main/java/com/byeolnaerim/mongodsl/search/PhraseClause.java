package com.byeolnaerim.mongodsl.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import org.bson.Document;

/**
 * Strongly typed Atlas Search {@code phrase} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class PhraseClause<K> extends AbstractSearchOperator {

	private Object path;

	private Object query;

	private Integer slop;

	private String synonyms;

	/**
	 * Sets a single search path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public PhraseClause<K> path(
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
	public PhraseClause<K> paths(
		Collection<K> paths
	) {
		this.path = SearchPathResolver.resolveAll( paths );
		return this;
	}

	/**
	 * Sets a single phrase query.
	 *
	 * @param query
	 *            the query text
	 *
	 * @return this builder
	 */
	public PhraseClause<K> query(
		String query
	) {
		this.query = Objects.requireNonNull( query, "query" );
		return this;
	}

	/**
	 * Sets multiple phrase queries.
	 *
	 * @param queries
	 *            the query texts
	 *
	 * @return this builder
	 */
	public PhraseClause<K> queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) {
			throw new IllegalArgumentException( "queries must not be empty" );

		}

		this.query = new ArrayList<>( queries );
		return this;
	}

	/**
	 * Sets the allowed token distance.
	 *
	 * @param slop
	 *            the slop value
	 *
	 * @return this builder
	 */
	public PhraseClause<K> slop(
		int slop
	) {

		if (slop < 0) {
			throw new IllegalArgumentException( "slop must be >= 0" );

		}

		this.slop = slop;
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
	public PhraseClause<K> synonyms(
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
	public PhraseClause<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "phrase";
	}

	@Override
	public Document toDocument() {

		if (this.path == null) {
			throw new IllegalStateException( "phrase.path is required" );

		}

		if (this.query == null) {
			throw new IllegalStateException( "phrase.query is required" );

		}

		Document body = new Document()
			.append( "path", this.path )
			.append( "query", this.query );

		if (this.slop != null) {
			body.append( "slop", this.slop );

		}

		if (this.synonyms != null && ! this.synonyms.isBlank()) {
			body.append( "synonyms", this.synonyms );

		}

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
