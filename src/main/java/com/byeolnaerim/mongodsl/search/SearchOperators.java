package com.byeolnaerim.mongodsl.search;

/**
 * Static entry points for strongly typed Atlas Search operator builders.
 */
public final class SearchOperators {

	private SearchOperators() {}

	/**
	 * Creates a {@code text} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code text} operator builder
	 */
	public static <K> TextSearchSpec<K> text() {
		return new TextSearchSpec<>();
	}

	/**
	 * Creates a {@code phrase} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code phrase} operator builder
	 */
	public static <K> PhraseSearchSpec<K> phrase() {
		return new PhraseSearchSpec<>();
	}

	/**
	 * Creates an {@code autocomplete} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code autocomplete} operator builder
	 */
	public static <K> AutocompleteSearchSpec<K> autocomplete() {
		return new AutocompleteSearchSpec<>();
	}

	/**
	 * Creates an {@code equals} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code equals} operator builder
	 */
	public static <K> EqualsClause<K> equals() {
		return new EqualsClause<>();
	}

	/**
	 * Creates an {@code in} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code in} operator builder
	 */
	public static <K> InClause<K> in() {
		return new InClause<>();
	}

	/**
	 * Creates an {@code exists} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code exists} operator builder
	 */
	public static <K> ExistsClause<K> exists() {
		return new ExistsClause<>();
	}

	/**
	 * Creates a {@code range} operator builder.
	 *
	 * @param <K>
	 *            the logical path type
	 *
	 * @return a new {@code range} operator builder
	 */
	public static <K> RangeSearchOperator<K> range() {
		return new RangeSearchOperator<>();
	}
}
