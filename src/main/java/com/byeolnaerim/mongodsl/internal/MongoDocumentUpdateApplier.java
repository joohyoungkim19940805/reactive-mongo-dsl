package com.byeolnaerim.mongodsl.internal;


import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import com.mongodb.client.model.changestream.TruncatedArray;
import com.mongodb.client.model.changestream.UpdateDescription;


/** Applies ordinary change-stream updateDescription deltas to an already materialized Document. */
public final class MongoDocumentUpdateApplier {

	public record ApplyResult(boolean applied, Document document, Set<String> changedFields) {}

	private MongoDocumentUpdateApplier() {}

	public static Set<String> changedFields(
		UpdateDescription description
	) {
		Set<String> fields = new LinkedHashSet<>();
		if (description == null)
			return fields;
		if (description.getUpdatedFields() != null)
			fields.addAll( description.getUpdatedFields().keySet() );
		if (description.getRemovedFields() != null)
			fields.addAll( description.getRemovedFields() );
		if (description.getTruncatedArrays() != null)
			description.getTruncatedArrays().stream().map( TruncatedArray::getField ).forEach( fields::add );
		return Set.copyOf( fields );
	}

	public static ApplyResult apply(
		Document source, UpdateDescription description
	) {
		if (source == null || description == null)
			return new ApplyResult( false, source, changedFields( description ) );
		/*
		 * MongoDB 6.1+ can report disambiguatedPaths when an update path such as a.0
		 * could mean either an array index or a literal document key. Guessing here could
		 * silently corrupt the materialized snapshot, so let the reservation perform the
		 * targeted _id lookup instead.
		 */
		if (description.getDisambiguatedPaths() != null && ! description.getDisambiguatedPaths().isEmpty())
			return new ApplyResult( false, source, changedFields( description ) );
		/*
		 * Numeric path segments can be array indexes or literal document keys, and array $unset
		 * semantics do not equal List.remove(index). Use the targeted _id lookup instead of
		 * guessing whenever an ordinary updated/removed path contains such a segment.
		 */
		if (changedFields( description ).stream().anyMatch( MongoDocumentUpdateApplier::containsNumericPathSegment ))
			return new ApplyResult( false, source, changedFields( description ) );
		Document copy = MongoDocumentSupport.copy( source );
		try {
			if (description.getUpdatedFields() != null) {
				Document updates = MongoBsonSupport.toDocument( description.getUpdatedFields() );
				for (Map.Entry<String, Object> entry : updates.entrySet())
					if (! setPath( copy, entry.getKey(), MongoDocumentSupport.copyValue( entry.getValue() ) ))
						return new ApplyResult( false, source, changedFields( description ) );
			}
			if (description.getRemovedFields() != null) {
				for (String field : description.getRemovedFields())
					if (! removePath( copy, field ))
						return new ApplyResult( false, source, changedFields( description ) );
			}
			if (description.getTruncatedArrays() != null) {
				for (TruncatedArray truncated : description.getTruncatedArrays())
					if (! truncateArray( copy, truncated.getField(), truncated.getNewSize() ))
						return new ApplyResult( false, source, changedFields( description ) );
			}
			return new ApplyResult( true, copy, changedFields( description ) );
		} catch (RuntimeException ignored) {
			return new ApplyResult( false, source, changedFields( description ) );
		}
	}

	private static boolean setPath(
		Document document, String path, Object value
	) {
		String[] segments = path.split( "\\." );
		Object current = document;
		for (int i = 0; i < segments.length - 1; i++) {
			String segment = segments[i];
			String next = segments[i + 1];
			if (current instanceof Document currentDocument) {
				Object nested = currentDocument.get( segment );
				if (nested == null) {
					if (isArrayIndex( next ))
						return false;
					Document created = new Document();
					currentDocument.put( segment, created );
					current = created;
				} else {
					current = nested;
				}
				continue;
			}
			if (current instanceof List<?> list && isArrayIndex( segment )) {
				int index = Integer.parseInt( segment );
				if (index < 0 || index >= list.size())
					return false;
				current = list.get( index );
				continue;
			}
			return false;
		}
		String terminal = segments[segments.length - 1];
		if (current instanceof Document currentDocument) {
			currentDocument.put( terminal, value );
			return true;
		}
		if (current instanceof List<?> list && isArrayIndex( terminal )) {
			int index = Integer.parseInt( terminal );
			if (index < 0 || index >= list.size())
				return false;
			@SuppressWarnings("unchecked")
			List<Object> mutable = (List<Object>) list;
			mutable.set( index, value );
			return true;
		}
		return false;
	}

	private static boolean removePath(
		Document document, String path
	) {
		String[] segments = path.split( "\\." );
		Object current = document;
		for (int i = 0; i < segments.length - 1; i++) {
			String segment = segments[i];
			if (current instanceof Document currentDocument) {
				if (! currentDocument.containsKey( segment ))
					return true;
				current = currentDocument.get( segment );
				continue;
			}
			if (current instanceof List<?> list && isArrayIndex( segment )) {
				int index = Integer.parseInt( segment );
				if (index < 0 || index >= list.size())
					return true;
				current = list.get( index );
				continue;
			}
			return false;
		}
		String terminal = segments[segments.length - 1];
		if (current instanceof Document currentDocument) {
			currentDocument.remove( terminal );
			return true;
		}
		if (current instanceof List<?> list && isArrayIndex( terminal )) {
			int index = Integer.parseInt( terminal );
			if (index < 0 || index >= list.size())
				return true;
			@SuppressWarnings("unchecked")
			List<Object> mutable = (List<Object>) list;
			mutable.remove( index );
			return true;
		}
		return false;
	}

	private static boolean truncateArray(
		Document document, String path, int newSize
	) {
		Object current = readPath( document, path );
		if (! (current instanceof List<?> list) || newSize < 0 || newSize > list.size())
			return false;
		@SuppressWarnings("unchecked")
		List<Object> mutable = (List<Object>) list;
		while (mutable.size() > newSize)
			mutable.remove( mutable.size() - 1 );
		return true;
	}

	private static Object readPath(
		Document document, String path
	) {
		Object current = document;
		for (String segment : path.split( "\\." )) {
			if (current instanceof Document currentDocument) {
				if (! currentDocument.containsKey( segment ))
					return null;
				current = currentDocument.get( segment );
			} else if (current instanceof List<?> list && isArrayIndex( segment )) {
				int index = Integer.parseInt( segment );
				if (index < 0 || index >= list.size())
					return null;
				current = list.get( index );
			} else {
				return null;
			}
		}
		return current;
	}

	private static boolean containsNumericPathSegment(
		String path
	) {
		if (path == null || path.isBlank())
			return false;
		for (String segment : path.split( "\\." ))
			if (isArrayIndex( segment ))
				return true;
		return false;
	}

	private static boolean isArrayIndex(
		String value
	) {
		if (value == null || value.isEmpty())
			return false;
		for (int i = 0; i < value.length(); i++)
			if (! Character.isDigit( value.charAt( i ) ))
				return false;
		return true;
	}

}
