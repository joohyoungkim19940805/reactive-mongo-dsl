package com.byeolnaerim.mongodsl.search;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.search.FuzzySearchOptions;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.SearchScore;
import com.mongodb.client.model.search.TextSearchOperator;


/**
 * DSL-friendly Atlas Search {@code text} operator backed by MongoDB driver's search API.
 */
public final class TextClause extends AbstractSearchOperator {

	private List<SearchPath> paths;

	private List<String> queries;

	private FuzzySearchOptions fuzzy;

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
	public TextClause path(
		String path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public TextClause path(
		Enum<?> path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public TextClause path(
		SearchPath path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	/** Fallback for custom path wrappers. Common callers should prefer String, Enum, or SearchPath. */
	public TextClause path(
		Object path
	) {

		this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
		return this;

	}

	public TextClause paths(
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

	public TextClause paths(
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

	public TextClause paths(
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
	public TextClause paths(
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
	public TextClause paths(
		Collection<?> paths
	) {

		this.paths = SearchPathResolver.resolveSearchPaths( paths );
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
	public TextClause query(
		String query
	) {

		this.queries = List.of( Objects.requireNonNull( query, "query" ) );
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
	public TextClause queries(
		Collection<String> queries
	) {

		if (queries == null || queries.isEmpty()) { throw new IllegalArgumentException( "queries must not be empty" ); }

		this.queries = new ArrayList<>( queries );
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
	public TextClause fuzzy(
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
	 * Sets the token match criteria.
	 *
	 * @param matchCriteria
	 *            the match criteria
	 *
	 * @return this builder
	 */
	public TextClause matchCriteria(
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
	public TextClause synonyms(
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
	public TextClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public TextClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "text";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.paths == null || this.paths.isEmpty()) { throw new IllegalStateException( "text.path is required" ); }

		if (this.queries == null || this.queries.isEmpty()) { throw new IllegalStateException( "text.query is required" ); }

		if (this.fuzzy != null && this.synonyms != null && ! this.synonyms.isBlank()) { throw new IllegalStateException( "text.fuzzy and text.synonyms cannot be used together" ); }

		TextSearchOperator operator = SearchOperator.text( this.paths, this.queries );

		if (this.fuzzy != null) {
			operator = operator.fuzzy( this.fuzzy );

		}

		if (this.synonyms != null && ! this.synonyms.isBlank()) {
			operator = operator.synonyms( this.synonyms );

		}

		SearchOperator rendered = applyScore( operator );

		if (this.matchCriteria == null) { return rendered; }

		// Driver 5.9.x does not expose matchCriteria on TextSearchOperator yet. Keep this one
		// driver-gap bridge narrow: let the driver render everything it knows, add only the
		// missing option, then wrap it back as a driver SearchOperator.
		Document document = MongoBsonSupport.toDocument( rendered );
		document.get( "text", Document.class ).append( "matchCriteria", this.matchCriteria.getValue() );
		return SearchOperator.of( document );

	}

}
