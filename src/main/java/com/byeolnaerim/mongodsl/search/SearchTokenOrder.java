package com.byeolnaerim.mongodsl.search;


/**
 * Token order options for the Atlas Search {@code autocomplete} operator.
 */
public enum SearchTokenOrder {

	/**
	 * Allows tokens to match in any order.
	 */
	ANY,

	/**
	 * Requires tokens to match in sequential order.
	 */
	SEQUENTIAL
}
