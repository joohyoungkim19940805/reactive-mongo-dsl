package com.byeolnaerim.mongodsl.paging;


/**
 * Action to take when page-number cursor paging would need to skip more rows than
 * the configured relative-skip limit from the nearest stored anchor.
 */
public enum CursorSkipExceededAction {

	/** Reject the cursor request before executing the business MongoDB query. */
	FAIL,

	/** Return an empty result without executing the business MongoDB query. */
	RETURN_EMPTY,

	/** Execute the query anyway using the calculated relative skip. */
	EXECUTE_ANYWAY

}
