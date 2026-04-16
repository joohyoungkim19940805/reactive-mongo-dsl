package com.byeolnaerim.mongodsl.search;

/**
 * Sort direction for Atlas Search sort specifications.
 */
public enum SearchSortDirection {

	/**
	 * Ascending order.
	 */
	ASC(1),

	/**
	 * Descending order.
	 */
	DESC(-1);

	private final int value;

	SearchSortDirection(
		int value
	) {
		this.value = value;
	}

	/**
	 * Returns the Atlas Search wire value.
	 *
	 * @return the Atlas Search wire value
	 */
	public int getValue() {
		return this.value;
	}
}
