package com.byeolnaerim.mongodsl.result;


import java.util.List;


/**
 * One logical reservation result change.
 * <p>INITIAL and REFRESHED carry a complete snapshot. INSERTED/UPDATED/REMOVED carry the affected
 * value(s) plus their ordered result indexes so a streaming client can reproduce sorted query
 * results without receiving the complete list again.</p>
 */
public record ReservationDelta<T>(
	ReservationDeltaType type,
	Object documentId,
	T before,
	T after,
	Integer beforeIndex,
	Integer afterIndex,
	List<T> snapshot
) {

	public static <T> ReservationDelta<T> initial(
		List<T> snapshot
	) {
		return new ReservationDelta<>( ReservationDeltaType.INITIAL, null, null, null, null, null, List.copyOf( snapshot ) );
	}

	public static <T> ReservationDelta<T> refreshed(
		List<T> snapshot
	) {
		return new ReservationDelta<>( ReservationDeltaType.REFRESHED, null, null, null, null, null, List.copyOf( snapshot ) );
	}

	public static <T> ReservationDelta<T> inserted(
		Object documentId, T after, Integer afterIndex
	) {
		return new ReservationDelta<>( ReservationDeltaType.INSERTED, documentId, null, after, null, afterIndex, null );
	}

	public static <T> ReservationDelta<T> inserted(
		Object documentId, T after
	) {
		return inserted( documentId, after, null );
	}

	public static <T> ReservationDelta<T> updated(
		Object documentId, T before, T after, Integer beforeIndex, Integer afterIndex
	) {
		return new ReservationDelta<>( ReservationDeltaType.UPDATED, documentId, before, after, beforeIndex, afterIndex, null );
	}

	public static <T> ReservationDelta<T> updated(
		Object documentId, T before, T after
	) {
		return updated( documentId, before, after, null, null );
	}

	public static <T> ReservationDelta<T> removed(
		Object documentId, T before, Integer beforeIndex
	) {
		return new ReservationDelta<>( ReservationDeltaType.REMOVED, documentId, before, null, beforeIndex, null, null );
	}

	public static <T> ReservationDelta<T> removed(
		Object documentId, T before
	) {
		return removed( documentId, before, null );
	}

}
