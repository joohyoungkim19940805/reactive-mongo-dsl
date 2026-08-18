package com.byeolnaerim.mongodsl.search;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import com.mongodb.client.model.search.PhraseSearchOperator;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code phrase} operator backed by MongoDB driver's search API.
 */
public final class PhraseClause extends AbstractSearchOperator {

	private List<SearchPath> paths;

	private List<String> queries;

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
	public PhraseClause path(
		String path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public PhraseClause path(
		Enum<?> path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public PhraseClause path(
		SearchPath path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	/** Fallback for custom path wrappers. Common callers should prefer String, Enum, or SearchPath. */
	public PhraseClause path(
		Object path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public PhraseClause paths(
		String path, String... paths
	) {

		return paths(
			java.util.stream.Stream
				.concat(
					java.util.stream.Stream.of( path ),
					Arrays.stream( paths )
				)
				.toList()
		);

	}

	public PhraseClause paths(
		Enum<?> path, Enum<?>... paths
	) {

		return paths(
			java.util.stream.Stream
				.concat(
					java.util.stream.Stream.of( path ),
					Arrays.stream( paths )
				)
				.toList()
		);

	}

	public PhraseClause paths(
		SearchPath path, SearchPath... paths
	) {

		return paths(
			java.util.stream.Stream
				.concat(
					java.util.stream.Stream.of( path ),
					Arrays.stream( paths )
				)
				.toList()
		);

	}

	/** Fallback for mixed/custom path wrappers. */
	public PhraseClause paths(
		Object path, Object... paths
	) {

		return paths(
			java.util.stream.Stream
				.concat(
					java.util.stream.Stream.of( path ),
					Arrays.stream( paths )
				)
				.toList()
		);

	}

	/**
	 * Sets multiple search paths.
	 *
	 * @param paths
	 *            the path inputs
	 *
	 * @return this builder
	 */
	public PhraseClause paths(
		Collection<?> paths
	) {

		this.paths = SearchPathResolver.resolveSearchPaths( paths );
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
	public PhraseClause query(
		String query
	) {

		this.queries = List.of( Objects.requireNonNull( query, "query" ) );
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
	public PhraseClause queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) { throw new IllegalArgumentException( "queries must not be empty" ); }

		this.queries = new ArrayList<>( queries );
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
	public PhraseClause slop(
		int slop
	) {

		if (slop < 0) { throw new IllegalArgumentException( "slop must be >= 0" ); }

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
	public PhraseClause synonyms(
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
	public PhraseClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public PhraseClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "phrase";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.paths == null || this.paths.isEmpty()) { throw new IllegalStateException( "phrase.path is required" ); }

		if (this.queries == null || this.queries.isEmpty()) { throw new IllegalStateException( "phrase.query is required" ); }

		PhraseSearchOperator operator = SearchOperator.phrase( this.paths, this.queries );

		if (this.slop != null) {
			operator = operator.slop( this.slop );

		}

		if (this.synonyms != null && ! this.synonyms.isBlank()) {
			operator = operator.synonyms( this.synonyms );

		}

		return applyScore( operator );

	}

}
