package com.byeolnaerim.mongodsl.search;

import org.bson.Document;

/**
 * Strongly typed fuzzy-search options for Atlas Search operators that support
 * fuzzy matching.
 */
public final class SearchFuzzy {

	private final int maxEdits;

	private final int prefixLength;

	private final int maxExpansions;

	private SearchFuzzy(
		int maxEdits,
		int prefixLength,
		int maxExpansions
	) {

		if (maxEdits < 1 || maxEdits > 2) {
			throw new IllegalArgumentException( "maxEdits must be 1 or 2" );

		}

		if (prefixLength < 0) {
			throw new IllegalArgumentException( "prefixLength must be >= 0" );

		}

		if (maxExpansions <= 0) {
			throw new IllegalArgumentException( "maxExpansions must be > 0" );

		}

		this.maxEdits = maxEdits;
		this.prefixLength = prefixLength;
		this.maxExpansions = maxExpansions;

	}

	/**
	 * Creates a new fuzzy-search option object.
	 *
	 * @param maxEdits
	 *            maximum edit distance
	 * @param prefixLength
	 *            number of prefix characters to keep exact
	 * @param maxExpansions
	 *            maximum number of variations to expand
	 *
	 * @return a new fuzzy-search option object
	 */
	public static SearchFuzzy of(
		int maxEdits,
		int prefixLength,
		int maxExpansions
	) {
		return new SearchFuzzy( maxEdits, prefixLength, maxExpansions );
	}

	/**
	 * Renders the fuzzy options as an Atlas Search document.
	 *
	 * @return the rendered fuzzy options
	 */
	public Document toDocument() {
		return new Document()
			.append( "maxEdits", this.maxEdits )
			.append( "prefixLength", this.prefixLength )
			.append( "maxExpansions", this.maxExpansions );
	}
}
