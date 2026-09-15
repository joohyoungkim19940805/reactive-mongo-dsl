package com.byeolnaerim.mongodsl.internal;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;


/**
 * Best-effort in-memory evaluator for ordinary MongoDB find filters.
 * <p>The evaluator is deliberately conservative: unsupported operators return UNKNOWN instead of
 * guessing. Reservation queries can then fall back to MongoDB without compromising correctness.</p>
 */
public final class MongoDocumentMatcher {

	public enum MatchResult {
		MATCH,
		NO_MATCH,
		UNKNOWN
	}

	public record Analysis(Document filter, Set<String> referencedFields, boolean locallyEvaluable) {}

	private static final java.util.regex.Pattern ARRAY_INDEX = java.util.regex.Pattern.compile( "(^|\\.)[0-9]+(?=\\.|$)" );

	private MongoDocumentMatcher() {}

	public static Analysis analyze(
		Bson filter
	) {
		Document document = MongoBsonSupport.toDocument( filter );
		Set<String> fields = new LinkedHashSet<>();
		boolean supported = analyzeFilter( document, fields );
		return new Analysis( document, Set.copyOf( fields ), supported );
	}

	public static MatchResult matches(
		Analysis analysis, Document document
	) {
		Objects.requireNonNull( analysis, "analysis must not be null" );
		Objects.requireNonNull( document, "document must not be null" );
		if (! analysis.locallyEvaluable())
			return MatchResult.UNKNOWN;
		try {
			return evaluateFilter( analysis.filter(), document );
		} catch (RuntimeException ignored) {
			return MatchResult.UNKNOWN;
		}
	}

	/** Returns whether any changed path overlaps a referenced query path in either direction. */
	public static boolean touches(
		Collection<String> referencedFields, Collection<String> changedFields
	) {
		if (referencedFields == null || referencedFields.isEmpty() || changedFields == null || changedFields.isEmpty())
			return false;
		for (String referenced : referencedFields) {
			for (String changed : changedFields) {
				if (pathsOverlap( referenced, changed ))
					return true;
			}
		}
		return false;
	}

	private static boolean pathsOverlap(
		String left, String right
	) {
		// updateDescription includes numeric array indexes while query paths usually do not.
		if (left != null) left = ARRAY_INDEX.matcher( left ).replaceAll( "" );
		if (right != null) right = ARRAY_INDEX.matcher( right ).replaceAll( "" );
		if (Objects.equals( left, right ))
			return true;
		return left != null && right != null
			&& (left.startsWith( right + "." ) || right.startsWith( left + "." ));
	}

	private static boolean analyzeFilter(
		Document filter, Set<String> fields
	) {
		for (Map.Entry<String, Object> entry : filter.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (key.startsWith( "$" )) {
				if (! Set.of( "$and", "$or", "$nor" ).contains( key ) || ! (value instanceof Collection<?> clauses))
					return false;
				for (Object clause : clauses) {
					if (! (clause instanceof Document document) || ! analyzeFilter( document, fields ))
						return false;
				}
				continue;
			}
			if (key.isBlank() || java.util.Arrays.stream( key.split( "\\.", -1 ) ).anyMatch( part -> part.isEmpty() || part.chars().allMatch( Character::isDigit ) ))
				return false;
			fields.add( key );
			if (! analyzeFieldCondition( value, fields ))
				return false;
		}
		return true;
	}

	private static boolean analyzeFieldCondition(
		Object condition, Set<String> fields
	) {
		/*
		 * MongoDB regular expressions use PCRE semantics while java.util.regex is not an exact
		 * substitute. Treat regex-bearing filters as opaque so AUTO falls back to MongoDB rather
		 * than risking a silently incorrect materialized result. Embedded-document equality is
		 * also order-sensitive in MongoDB, unlike java.util.Map equality, so keep that opaque too.
		 */
		if (isRegexValue( condition ))
			return false;
		if (! (condition instanceof Document operators) || operators.keySet().stream().noneMatch( key -> key.startsWith( "$" ) ))
			return ! containsEmbeddedDocumentValue( condition );
		if (operators.containsKey( "$regex" ) || operators.containsKey( "$options" ))
			return false;
		for (Map.Entry<String, Object> operator : operators.entrySet()) {
			String key = operator.getKey();
			Object value = operator.getValue();
			if (! Set.of( "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$all", "$exists", "$elemMatch", "$not", "$size" ).contains( key ))
				return false;
			if (Set.of( "$gt", "$gte", "$lt", "$lte" ).contains( key ) && (value == null || value instanceof org.bson.types.MinKey || value instanceof org.bson.types.MaxKey || ! MongoScalarComparison.supports( value )))
				return false;
			if ("$size".equals( key ) && (! (value instanceof Number size) || size.doubleValue() < 0D
				|| size.doubleValue() > Integer.MAX_VALUE || size.doubleValue() != size.intValue()))
				return false;
			if (("$eq".equals( key ) || "$ne".equals( key )) && containsEmbeddedDocumentValue( value ))
				return false;
			if (("$in".equals( key ) || "$nin".equals( key ) || "$all".equals( key ))
				&& (containsRegexValue( value ) || containsEmbeddedDocumentValue( value ) || containsNestedCollectionValue( value )))
				return false;
			if ("$elemMatch".equals( key )) {
				if (! (value instanceof Document nested))
					return false;
				if (nested.keySet().stream().anyMatch( nestedKey -> nestedKey.startsWith( "$" ) )) {
					if (! analyzeFieldCondition( nested, fields ))
						return false;
				} else if (! analyzeFilter( nested, fields )) {
					return false;
				}
			}
			if ("$all".equals( key ) && value instanceof Collection<?> expected) {
				for (Object item : expected) {
					if (item instanceof Document nested && nested.keySet().stream().anyMatch( nestedKey -> nestedKey.startsWith( "$" ) ))
						return false;
				}
			}
			if ("$not".equals( key ) && ! analyzeFieldCondition( value, fields ))
				return false;
		}
		return true;
	}

	private static boolean isRegexValue(
		Object value
	) {
		return value instanceof org.bson.BsonRegularExpression || value instanceof java.util.regex.Pattern;
	}


	private static boolean containsEmbeddedDocumentValue(
		Object value
	) {
		if (value instanceof Map<?, ?>)
			return true;
		if (value instanceof Collection<?> collection)
			return collection.stream().anyMatch( MongoDocumentMatcher::containsEmbeddedDocumentValue );
		return false;
	}

	private static boolean containsNestedCollectionValue(
		Object value
	) {
		if (! (value instanceof Collection<?> collection))
			return false;
		return collection.stream().anyMatch( item -> item instanceof Collection<?> );
	}

	private static boolean containsRegexValue(
		Object value
	) {
		if (isRegexValue( value ))
			return true;
		if (value instanceof Collection<?> collection)
			return collection.stream().anyMatch( MongoDocumentMatcher::containsRegexValue );
		if (value instanceof Map<?, ?> map)
			return map.values().stream().anyMatch( MongoDocumentMatcher::containsRegexValue );
		return false;
	}

	private static MatchResult evaluateFilter(
		Document filter, Document document
	) {
		for (Map.Entry<String, Object> entry : filter.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			MatchResult result;
			if (key.startsWith( "$" ))
				result = evaluateLogical( key, value, document );
			else
				result = evaluateField( readPathValues( document, key ), value );
			if (result != MatchResult.MATCH)
				return result;
		}
		return MatchResult.MATCH;
	}

	private static MatchResult evaluateLogical(
		String operator, Object value, Document document
	) {
		if (! (value instanceof Collection<?> clauses))
			return MatchResult.UNKNOWN;
		return switch (operator) {
			case "$and" -> evaluateAnd( clauses, document );
			case "$or" -> evaluateOr( clauses, document );
			case "$nor" -> negate( evaluateOr( clauses, document ) );
			default -> MatchResult.UNKNOWN;
		};
	}

	private static MatchResult evaluateAnd(
		Collection<?> clauses, Document document
	) {
		boolean unknown = false;
		for (Object clause : clauses) {
			if (! (clause instanceof Document nested))
				return MatchResult.UNKNOWN;
			MatchResult result = evaluateFilter( nested, document );
			if (result == MatchResult.NO_MATCH)
				return MatchResult.NO_MATCH;
			if (result == MatchResult.UNKNOWN)
				unknown = true;
		}
		return unknown ? MatchResult.UNKNOWN : MatchResult.MATCH;
	}

	private static MatchResult evaluateOr(
		Collection<?> clauses, Document document
	) {
		boolean unknown = false;
		for (Object clause : clauses) {
			if (! (clause instanceof Document nested))
				return MatchResult.UNKNOWN;
			MatchResult result = evaluateFilter( nested, document );
			if (result == MatchResult.MATCH)
				return MatchResult.MATCH;
			if (result == MatchResult.UNKNOWN)
				unknown = true;
		}
		return unknown ? MatchResult.UNKNOWN : MatchResult.NO_MATCH;
	}

	private static MatchResult evaluateField(
		PathValues values, Object condition
	) {
		if (isRegexValue( condition ))
			return MatchResult.UNKNOWN;
		if (! (condition instanceof Document operators) || operators.keySet().stream().noneMatch( key -> key.startsWith( "$" ) ))
			return equality( values, condition );

		for (Map.Entry<String, Object> entry : operators.entrySet()) {
			String operator = entry.getKey();
			if ("$options".equals( operator ))
				continue;
			MatchResult result = switch (operator) {
				case "$eq" -> equality( values, entry.getValue() );
				case "$ne" -> negate( equality( values, entry.getValue() ) );
				case "$gt" -> compareAny( values, entry.getValue(), comparison -> comparison > 0 );
				case "$gte" -> compareAny( values, entry.getValue(), comparison -> comparison >= 0 );
				case "$lt" -> compareAny( values, entry.getValue(), comparison -> comparison < 0 );
				case "$lte" -> compareAny( values, entry.getValue(), comparison -> comparison <= 0 );
				case "$in" -> in( values, entry.getValue(), false );
				case "$nin" -> in( values, entry.getValue(), true );
				case "$all" -> all( values, entry.getValue() );
				case "$exists" -> exists( values, entry.getValue() );
				case "$elemMatch" -> elemMatch( values, entry.getValue() );
				case "$not" -> negate( evaluateField( values, entry.getValue() ) );
				case "$size" -> size( values, entry.getValue() );
				default -> MatchResult.UNKNOWN;
			};
			if (result != MatchResult.MATCH)
				return result;
		}
		return MatchResult.MATCH;
	}

	private static MatchResult equality(
		PathValues values, Object expected
	) {
		if (! values.present())
			return expected == null ? MatchResult.MATCH : MatchResult.NO_MATCH;
		for (Object actual : values.values()) {
			if (valueEquals( actual, expected ))
				return MatchResult.MATCH;
			if (actual instanceof Collection<?> collection) {
				for (Object item : collection)
					if (valueEquals( item, expected ))
						return MatchResult.MATCH;
			}
		}
		return MatchResult.NO_MATCH;
	}

	private static MatchResult compareAny(
		PathValues values, Object expected, java.util.function.IntPredicate predicate
	) {
		if (! values.present())
			return MatchResult.NO_MATCH;
		boolean unknown = false;
		for (Object actual : flattenTerminalValues( values.values() )) {
			if (actual == Missing.VALUE)
				continue;
			if (MongoScalarComparison.supports( actual ) && MongoScalarComparison.supports( expected )
				&& ! MongoScalarComparison.sameBracket( actual, expected ))
				continue; // Find range predicates use type bracketing, unlike sort ordering.
			if (actual instanceof Number a && Double.isNaN( a.doubleValue() )
				|| expected instanceof Number b && Double.isNaN( b.doubleValue() )) {
				unknown = true; // Find NaN range rules differ from BSON sort ordering.
				continue;
			}
			Integer comparison = compareValues( actual, expected );
			if (comparison == null) {
				unknown = true;
				continue;
			}
			if (predicate.test( comparison ))
				return MatchResult.MATCH;
		}
		return unknown ? MatchResult.UNKNOWN : MatchResult.NO_MATCH;
	}

	private static MatchResult in(
		PathValues values, Object candidatesValue, boolean negate
	) {
		if (! (candidatesValue instanceof Collection<?> candidates))
			return MatchResult.UNKNOWN;
		boolean unknown = false;
		for (Object candidate : candidates) {
			if (isRegexValue( candidate ))
				return MatchResult.UNKNOWN;
			MatchResult candidateResult = equality( values, candidate );
			if (candidateResult == MatchResult.MATCH)
				return negate ? MatchResult.NO_MATCH : MatchResult.MATCH;
			if (candidateResult == MatchResult.UNKNOWN)
				unknown = true;
		}
		if (unknown)
			return MatchResult.UNKNOWN;
		return negate ? MatchResult.MATCH : MatchResult.NO_MATCH;
	}

	private static MatchResult all(
		PathValues values, Object expectedValue
	) {
		if (! (expectedValue instanceof Collection<?> expected))
			return MatchResult.UNKNOWN;
		if (expected.isEmpty())
			return MatchResult.NO_MATCH;
		// $all is a conjunction of equality predicates on the same path, not necessarily
		// a single terminal array when that path traverses multiple array elements.
		boolean unknown = false;
		for (Object item : expected) {
			if (! values.present() && item == null) {
				unknown = true;
				continue;
			}
			MatchResult result = equality( values, item );
			if (result == MatchResult.NO_MATCH)
				return result;
			unknown |= result == MatchResult.UNKNOWN;
		}
		return unknown ? MatchResult.UNKNOWN : MatchResult.MATCH;
	}

	private static MatchResult exists(
		PathValues values, Object expected
	) {
		if (! (expected instanceof Boolean bool))
			return MatchResult.UNKNOWN;
		return values.present() == bool ? MatchResult.MATCH : MatchResult.NO_MATCH;
	}

	private static MatchResult elemMatch(
		PathValues values, Object condition
	) {
		if (! (condition instanceof Document nested))
			return MatchResult.UNKNOWN;
		for (Object actual : values.values()) {
			if (! (actual instanceof Collection<?> collection))
				continue;
			boolean unknown = false;
			for (Object element : collection) {
				MatchResult result;
				if (nested.keySet().stream().noneMatch( key -> key.startsWith( "$" ) )) {
					if (! (element instanceof Document elementDocument))
						continue;
					result = evaluateFilter( nested, elementDocument );
				} else {
					// Value-form $elemMatch evaluates the element itself, not another implicit
					// array traversal. Nested arrays need distinct server semantics.
					if (element instanceof Collection<?>) {
						unknown = true;
						continue;
					}
					result = evaluateField( new PathValues( true, Collections.singletonList( element ) ), nested );
				}
				if (result == MatchResult.MATCH)
					return MatchResult.MATCH;
				if (result == MatchResult.UNKNOWN)
					unknown = true;
			}
			if (unknown)
				return MatchResult.UNKNOWN;
		}
		return MatchResult.NO_MATCH;
	}

	private static MatchResult size(
		PathValues values, Object expected
	) {
		if (! (expected instanceof Number number))
			return MatchResult.UNKNOWN;
		for (Object actual : values.values()) {
			if (actual instanceof Collection<?> collection && collection.size() == number.intValue())
				return MatchResult.MATCH;
		}
		return MatchResult.NO_MATCH;
	}

	private static MatchResult negate(
		MatchResult result
	) {
		return switch (result) {
			case MATCH -> MatchResult.NO_MATCH;
			case NO_MATCH -> MatchResult.MATCH;
			case UNKNOWN -> MatchResult.UNKNOWN;
		};
	}

	private static boolean valueEquals(
		Object actual, Object expected
	) {
		if (actual == Missing.VALUE) {
			if (expected == null)
				throw new IllegalArgumentException( "mixed missing/null array paths require server evaluation" );
			return false;
		}
		if (actual instanceof org.bson.BsonUndefined || expected instanceof org.bson.BsonUndefined
			|| actual instanceof org.bson.types.Symbol || expected instanceof org.bson.types.Symbol)
			throw new IllegalArgumentException( "legacy BSON null/string semantics require server evaluation" );
		if (actual instanceof Collection<?> left && expected instanceof Collection<?> right) {
			if (left.size() != right.size())
				return false;
			var a = left.iterator();
			var b = right.iterator();
			while (a.hasNext())
				if (! valueEquals( a.next(), b.next() ))
					return false;
			return true;
		}
		if (MongoScalarComparison.supports( actual ) && MongoScalarComparison.supports( expected ))
			return MongoScalarComparison.sameBracket( actual, expected ) && MongoScalarComparison.compare( actual, expected ) == 0;
		return Objects.deepEquals( actual, expected );
	}

	private static Integer compareValues(
		Object left, Object right
	) {
		if (! MongoScalarComparison.supports( left ) || ! MongoScalarComparison.supports( right ))
			return null;
		return MongoScalarComparison.sameBracket( left, right ) ? MongoScalarComparison.compare( left, right ) : null;
	}

	private static List<Object> flattenTerminalValues(
		Collection<Object> values
	) {
		List<Object> flattened = new ArrayList<>();
		for (Object value : values) {
			if (value instanceof Collection<?> collection)
				flattened.addAll( collection );
			else
				flattened.add( value );
		}
		return flattened;
	}

	private static PathValues readPathValues(
		Document document, String path
	) {
		List<Object> values = new ArrayList<>();
		boolean present = collectPathValues( document, path.split( "\\." ), 0, values );
		return new PathValues( present, Collections.unmodifiableList( values ) );
	}

	private static boolean collectPathValues(
		Object current, String[] segments, int index, List<Object> output
	) {
		if (index == segments.length) {
			output.add( current );
			return true;
		}
		if (current instanceof Collection<?> collection) {
			boolean present = false;
			for (Object element : collection) {
				if (element instanceof Collection<?>)
					throw new IllegalArgumentException( "nested array traversal is delegated to MongoDB" );
				present |= collectPathValues( element, segments, index, output );
			}
			return present;
		}
		if (! (current instanceof Map<?, ?> map) || ! map.containsKey( segments[index] )) {
			output.add( Missing.VALUE );
			return false;
		}
		return collectPathValues( map.get( segments[index] ), segments, index + 1, output );
	}

	private enum Missing { VALUE }

	private record PathValues(boolean present, List<Object> values) {}

}
