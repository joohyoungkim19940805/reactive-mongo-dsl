package com.byeolnaerim.mongodsl.search;

/**
 * Count modes supported by Atlas Search.
 */
public enum SearchCountType {

	/**
	 * Requests a lower-bound count.
	 */
	LOWER_BOUND("lowerBound"),

	/**
	 * Requests an exact total count.
	 */
	TOTAL("total");

	private final String value;

	SearchCountType(
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
