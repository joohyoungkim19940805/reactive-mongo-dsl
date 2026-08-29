package com.byeolnaerim.mongodsl.paging;


import org.bson.Document;


/** Sort tuple of the first document of a zero-based page. */
public record CursorAnchor(int pageNumber, Document sortValues) {

	public CursorAnchor {

		if (pageNumber < 0)
			throw new IllegalArgumentException( "pageNumber must be >= 0" );
		sortValues = sortValues == null ? new Document() : new Document( sortValues );

	}

	@Override
	public Document sortValues() { return new Document( sortValues ); }

}
