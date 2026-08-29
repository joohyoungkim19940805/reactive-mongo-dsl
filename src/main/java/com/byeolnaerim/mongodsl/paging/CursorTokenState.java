package com.byeolnaerim.mongodsl.paging;


import java.util.Objects;
import org.bson.Document;


/** Store-side state referenced by an opaque cursor token. */
public record CursorTokenState(String queryKey, int pageSize, Document sortValues) {

	public CursorTokenState {

		queryKey = Objects.requireNonNull( queryKey, "queryKey must not be null" );
		if (queryKey.isBlank())
			throw new IllegalArgumentException( "queryKey must not be blank" );
		if (pageSize <= 0)
			throw new IllegalArgumentException( "pageSize must be > 0" );
		sortValues = sortValues == null ? new Document() : new Document( sortValues );

	}

	@Override
	public Document sortValues() { return new Document( sortValues ); }

}
