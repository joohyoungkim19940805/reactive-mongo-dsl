package com.byeolnaerim.mongodsl.internal;


import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;


/** Builds conservative in-memory comparators for ordinary numeric MongoDB sort documents. */
public final class MongoDocumentComparator {

	private MongoDocumentComparator() {}

	public static Optional<Comparator<Document>> from(
		Bson sort
	) {
		if (sort == null)
			return Optional.empty();
		Document document = MongoBsonSupport.toDocument( sort );
		for (Map.Entry<String, Object> entry : document.entrySet()) {
			if (! (entry.getValue() instanceof Number number) || (number.doubleValue() != 1D && number.doubleValue() != -1D))
				return Optional.empty();
		}
		Comparator<Document> comparator = (left, right) -> 0;
		for (Map.Entry<String, Object> entry : document.entrySet()) {
			String field = entry.getKey();
			int direction = ((Number) entry.getValue()).intValue();
			Comparator<Document> next = (left, right) -> direction * MongoScalarComparison.compare( readSortablePath( left, field ), readSortablePath( right, field ) );
			comparator = comparator.thenComparing( next );
		}
		return Optional.of( comparator );
	}

	/**
	 * Returns whether every sort path in one document can be reproduced locally. MongoDB array
	 * sorting and embedded-document BSON ordering are deliberately left to the server.
	 */
	public static boolean canEvaluate(
		Bson sort, Document document
	) {
		if (sort == null || document == null)
			return true;
		try {
			for (String field : MongoBsonSupport.toDocument( sort ).keySet()) {
				Object value = readSortablePath( document, field );
				if (value instanceof Collection<?> || value instanceof Map<?, ?>)
					return false;
				if (! MongoScalarComparison.supports( value ))
					return false;
			}
			return true;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	public static java.util.Set<String> fields(
		Bson sort
	) {
		return sort == null ? java.util.Set.of() : java.util.Set.copyOf( MongoBsonSupport.toDocument( sort ).keySet() );
	}

	private static Object readSortablePath(
		Document document, String path
	) {
		Object current = document;
		for (String segment : path.split( "\\." )) {
			if (current instanceof Collection<?>)
				throw new IllegalArgumentException( "MongoDB array sort semantics are not reproduced locally for path " + path );
			if (! (current instanceof Document currentDocument))
				return null;
			current = currentDocument.get( segment );
		}
		return current;
	}

}
