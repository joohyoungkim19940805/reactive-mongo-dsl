package com.byeolnaerim.mongodsl.change;


/** Strategy used by reservation change streams to maintain query results. */
public enum ReservationMode {

	/** Use incremental maintenance when it is provably safe and fall back to query refreshes. */
	AUTO,

	/** Preserve the legacy behavior and re-run the finite query for every coalesced invalidation. */
	REQUERY,

	/** Require incremental/hybrid maintenance and fail when the query cannot be interpreted safely. */
	INCREMENTAL_ONLY

}
