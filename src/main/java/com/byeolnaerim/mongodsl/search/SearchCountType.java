package com.byeolnaerim.mongodsl.search;


import com.mongodb.client.model.search.SearchCount;


/**
 * Count modes supported by Atlas Search.
 */
public enum SearchCountType {

	/**
	 * Requests a lower-bound count.
	 */
	LOWER_BOUND,

	/**
	 * Requests an exact total count.
	 */
	TOTAL;

	/**
	 * Returns the MongoDB driver-native count option.
	 *
	 * @return the driver count option
	 */
	public SearchCount toSearchCount() {

		return this == TOTAL ? SearchCount.total() : SearchCount.lowerBound();

	}

}
