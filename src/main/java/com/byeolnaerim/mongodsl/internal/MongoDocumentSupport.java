package com.byeolnaerim.mongodsl.internal;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;


/** Shared lossless-enough helpers for materialized BSON {@link Document} values. */
public final class MongoDocumentSupport {

	private MongoDocumentSupport() {}

	/** Deep-copies nested Documents, Maps, collections, byte arrays, and mutable Dates. */
	public static Document copy(
		Document source
	) {
		if (source == null)
			return null;
		Document copy = new Document();
		source.forEach( (key, value) -> copy.put( key, copyValue( value ) ) );
		return copy;
	}

	/** Deep-copies a value using the same rules as {@link #copy(Document)}. */
	public static Object copyValue(
		Object value
	) {
		if (value instanceof Document document)
			return copy( document );
		if (value instanceof Map<?, ?> map) {
			Document copy = new Document();
			map.forEach( (key, nestedValue) -> copy.put( String.valueOf( key ), copyValue( nestedValue ) ) );
			return copy;
		}
		if (value instanceof Collection<?> collection) {
			List<Object> copy = new ArrayList<>( collection.size() );
			for (Object item : collection)
				copy.add( copyValue( item ) );
			return copy;
		}
		if (value instanceof byte[] bytes)
			return bytes.clone();
		if (value instanceof Date date)
			return new Date( date.getTime() );
		return value;
	}

	/** Reads an ordinary dotted embedded-document path. Array traversal is intentionally not guessed. */
	public static Object readPath(
		Document document, String path
	) {
		Object current = document;
		for (String segment : path.split( "\\." )) {
			if (! (current instanceof Document currentDocument))
				return null;
			current = currentDocument.get( segment );
		}
		return current;
	}

	/** Removes an ordinary dotted embedded-document path. Array projection semantics are not emulated. */
	public static void removePath(
		Document document, String path
	) {
		String[] segments = path.split( "\\." );
		Document current = document;
		for (int i = 0; i < segments.length - 1; i++) {
			Object nested = current.get( segments[i] );
			if (! (nested instanceof Document nestedDocument))
				return;
			current = nestedDocument;
		}
		current.remove( segments[segments.length - 1] );
	}

}
