package com.byeolnaerim.mongodsl.search;

import org.bson.Document;

/**
 * Strongly typed Atlas Search sort specification.
 *
 * @param <K>
 *            the logical path type
 */
public final class SearchSortSpec<K> {

	private final Document document;

	private SearchSortSpec(
		Document document
	) {
		this.document = document;
	}

	/**
	 * Sorts by search score in descending order.
	 *
	 * @return a score-descending sort specification
	 */
	public static SearchSortSpec<Object> scoreDesc() {
		return new SearchSortSpec<>(
			new Document( "score", new Document( "$meta", "searchScore" ) )
		);
	}

	/**
	 * Sorts by search score in ascending order.
	 *
	 * @return a score-ascending sort specification
	 */
	public static SearchSortSpec<Object> scoreAsc() {
		return new SearchSortSpec<>(
			new Document(
				"score",
				new Document()
					.append( "$meta", "searchScore" )
					.append( "order", 1 )
			)
		);
	}

	/**
	 * Sorts the given path in ascending order.
	 *
	 * @param path
	 *            the sort path
	 * @param <K>
	 *            the logical path type
	 *
	 * @return an ascending sort specification
	 */
	public static <K> SearchSortSpec<K> asc(
		K path
	) {
		return new SearchSortSpec<>(
			new Document( SearchPathResolver.resolve( path ), SearchSortDirection.ASC.getValue() )
		);
	}

	/**
	 * Sorts the given path in descending order.
	 *
	 * @param path
	 *            the sort path
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a descending sort specification
	 */
	public static <K> SearchSortSpec<K> desc(
		K path
	) {
		return new SearchSortSpec<>(
			new Document( SearchPathResolver.resolve( path ), SearchSortDirection.DESC.getValue() )
		);
	}

	/**
	 * Renders the sort specification.
	 *
	 * @return the rendered sort specification
	 */
	public Document toDocument() {
		return new Document( this.document );
	}
}
