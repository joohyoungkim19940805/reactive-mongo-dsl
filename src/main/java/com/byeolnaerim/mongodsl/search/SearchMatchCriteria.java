package com.byeolnaerim.mongodsl.search;


/**
 * Match criteria for Atlas Search operators that support token matching mode.
 */
public enum SearchMatchCriteria {

	/**
	 * Matches when any analyzed token is satisfied.
	 */
	ANY("any"),

	/**
	 * Matches only when all analyzed tokens are satisfied.
	 */
	ALL("all");

	private final String value;

	SearchMatchCriteria(
						String value
	) {

		this.value = value;

	}

	/**
	 * Returns the Atlas Search wire value.
	 *
	 * @return the Atlas Search wire value
	 */
	public String getValue() { return this.value; }

}
