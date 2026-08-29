package com.byeolnaerim.mongodsl.result;


import java.util.List;


/** One bounded keyset page plus the opaque token for the next page. */
public record CursorPage<T>(List<T> data, String nextCursor) {

	public CursorPage {

		data = data == null ? List.of() : List.copyOf( data );
		nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;

	}

	public boolean hasNext() { return nextCursor != null; }

}
