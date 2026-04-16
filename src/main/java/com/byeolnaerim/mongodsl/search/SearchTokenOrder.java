package com.byeolnaerim.mongodsl.search;

/**
 * Token order options for the Atlas Search {@code autocomplete} operator.
 */
public enum SearchTokenOrder {

	/**
	 * Allows tokens to match in any order.
	 */
	ANY("any"),

	/**
	 * Requires tokens to match in sequential order.
	 */
	SEQUENTIAL("sequential");

	private final String value;

	SearchTokenOrder(
		String value
	) {
		this.value = value;
	}

	/**
	 * Returns the Atlas Search wire value.
	 *
	 * @return the Atlas Search wire value
	 */
	public String getValue() {
		return this.value;
	}
}
