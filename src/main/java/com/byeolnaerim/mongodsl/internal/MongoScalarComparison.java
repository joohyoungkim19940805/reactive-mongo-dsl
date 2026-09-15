package com.byeolnaerim.mongodsl.internal;


import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import org.bson.BsonTimestamp;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;


/** Supported scalar BSON ordering shared by the local matcher and sort comparator. */
final class MongoScalarComparison {

	private MongoScalarComparison() {}

	static boolean supports(
		Object value
	) {
		return rank( value ) >= 0;
	}

	static int compare(
		Object left, Object right
	) {
		int leftRank = rank( left );
		int rightRank = rank( right );
		if (leftRank < 0 || rightRank < 0)
			throw new IllegalArgumentException( "unsupported local BSON scalar comparison" );
		if (leftRank != rightRank)
			return Integer.compare( leftRank, rightRank );
		if (left == right || left == null || left instanceof MinKey || left instanceof MaxKey)
			return 0;
		if (left instanceof Number a && right instanceof Number b)
			return compareNumbers( a, b );
		if (left instanceof String a && right instanceof String b)
			return MongoSimpleCollation.compare( a, b );
		if (left instanceof ObjectId a && right instanceof ObjectId b)
			return a.compareTo( b );
		if (left instanceof Boolean a && right instanceof Boolean b)
			return a.compareTo( b );
		if (left instanceof Date a && right instanceof Date b)
			return Long.compare( a.getTime(), b.getTime() );
		if (left instanceof BsonTimestamp a && right instanceof BsonTimestamp b) {
			int time = Integer.compareUnsigned( a.getTime(), b.getTime() );
			return time != 0 ? time : Integer.compareUnsigned( a.getInc(), b.getInc() );
		}
		if (left instanceof Binary a && right instanceof Binary b) {
			int size = Integer.compare( a.getData().length, b.getData().length );
			if (size != 0)
				return size;
			int type = Integer.compare( Byte.toUnsignedInt( a.getType() ), Byte.toUnsignedInt( b.getType() ) );
			return type != 0 ? type : Arrays.compareUnsigned( a.getData(), b.getData() );
		}
		throw new IllegalArgumentException( "unsupported local BSON scalar comparison" );
	}

	static boolean sameBracket(
		Object left, Object right
	) {
		return rank( left ) >= 0 && rank( left ) == rank( right );
	}

	private static int rank(
		Object value
	) {
		if (value instanceof MinKey) return 0;
		if (value == null) return 1;
		if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
			|| value instanceof Float || value instanceof Double || value instanceof Decimal128 || value instanceof BigDecimal) return 2;
		if (value instanceof String) return 3;
		if (value instanceof Binary) return 6;
		if (value instanceof ObjectId) return 7;
		if (value instanceof Boolean) return 8;
		if (value instanceof Date) return 9;
		if (value instanceof BsonTimestamp) return 10;
		if (value instanceof MaxKey) return 15;
		return -1;
	}

	private static int compareNumbers(
		Number left, Number right
	) {
		int a = numericClass( left );
		int b = numericClass( right );
		if (a != b)
			return Integer.compare( a, b );
		return a == 2 ? decimal( left ).compareTo( decimal( right ) ) : 0;
	}

	// BSON numeric ordering: NaN, -Infinity, finite, +Infinity.
	private static int numericClass(
		Number number
	) {
		if (number instanceof Decimal128 value) {
			if (value.isNaN()) return 0;
			if (value.isInfinite()) return value.isNegative() ? 1 : 3;
		} else if (number instanceof Double || number instanceof Float) {
			if (Double.isNaN( number.doubleValue() )) return 0;
			if (number.doubleValue() == Double.NEGATIVE_INFINITY) return 1;
			if (number.doubleValue() == Double.POSITIVE_INFINITY) return 3;
		}
		return 2;
	}

	private static BigDecimal decimal(
		Number number
	) {
		if (number instanceof Decimal128 value) {
			// Decimal128 permits signed zero at many exponents; BigDecimal cannot retain its
			// sign and the driver rejects a negative-zero bigDecimalValue() conversion.
			// The decimal's canonical text preserves its exact coefficient (unlike a double).
			return new BigDecimal( value.toString() );
		}
		if (number instanceof BigDecimal value) return value;
		// Preserve the exact binary value of a double. Stringifying 0.1 first would incorrectly
		// equate the BSON double to an exact Decimal128("0.1").
		if (number instanceof Double || number instanceof Float) return new BigDecimal( number.doubleValue() );
		return BigDecimal.valueOf( number.longValue() );
	}

}
