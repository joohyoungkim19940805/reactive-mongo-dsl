package com.byeolnaerim.mongodsl.criteria;


import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


/**
 * Represents a field condition pair used by the DSL to build MongoDB query criteria.
 *
 * @param <K>
 *            the field name type
 * @param <V>
 *            the field value type
 */
public class FieldsPair<K, V> implements Map.Entry<K, V> {

	/**
	 * Supported query operators for {@link FieldsPair}.
	 */
	public static enum Condition {

		eq, // Equal
		notEq, // Not equal
		between, // Between (expects a range)
		gt, // Greater than
		gte, // Greater than or equal
		lt, // Less than
		lte, // Less than or equal
		in, // In (expects a collection)
		notIn, // Not in (expects a collection)
		like, // Like (for pattern matching)
		regex, // Regular expression
		exists, // Field exists
		isNull, // Field is null
		isNotNull, // Field is not null
		all, // array all match
		near, // coordinate x,y search legacy 2d
		nearSphere, // GeoJSON + 2dsphere
		elemMatch;

		/**
		 * Regex option flags used by LIKE-style matching.
		 */
		public static enum LikeOperator {
			i, m, s, x, l, u
		}

	}

	private K fieldName;

	private V fieldValue;

	private Condition queryType;

	/**
	 * Returns the configured query condition.
	 *
	 * @return the query condition
	 */
	public Condition getQueryType() { return this.queryType; }

	/**
	 * Creates an equality-based field condition pair.
	 *
	 * @param fieldName
	 *            the field name
	 * @param fieldValue
	 *            the field value
	 */
	public FieldsPair(
						K fieldName,
						V fieldValue
	) {

		this.fieldName = fieldName;
		this.fieldValue = fieldValue;
		this.queryType = Condition.eq; // 기본값은 eq

	}

	/**
	 * Creates a field condition pair with the given condition.
	 *
	 * @param fieldName
	 *            the field name
	 * @param fieldValue
	 *            the field value
	 * @param queryType
	 *            the query condition
	 */
	public FieldsPair(
						K fieldName,
						V fieldValue,
						Condition queryType
	) {

		this.fieldName = fieldName;
		this.fieldValue = fieldValue;
		this.queryType = queryType;

	}

	/**
	 * Creates a field condition pair for conditions that do not require an explicit value.
	 *
	 * @param fieldName
	 *            the field name
	 * @param queryType
	 *            the query condition
	 */
	public FieldsPair(
						K fieldName,
						Condition queryType
	) {

		this.fieldName = fieldName;
		this.fieldValue = null;
		this.queryType = queryType;

	}

	/**
	 * Returns the field name.
	 *
	 * @return the field name
	 */
	public K getFieldName() { return fieldName; }

	/**
	 * Returns the field value.
	 *
	 * @return the field value
	 */
	public V getFieldValue() { return fieldValue; }

	/**
	 * Returns the field name.
	 *
	 * @return the field name
	 */
	@Override
	public K getKey() {

		// TODO Auto-generated method stub
		return this.fieldName;

	}

	/**
	 * Returns the field value.
	 *
	 * @return the field value
	 */
	@Override
	public V getValue() {

		// TODO Auto-generated method stub
		return this.fieldValue;

	}

	/**
	 * Replaces the current field value.
	 *
	 * @param value
	 *            the new field value
	 * 
	 * @return the assigned value
	 */
	@Override
	public V setValue(
		V value
	) {

		// TODO Auto-generated method stub
		this.fieldValue = value;
		return this.fieldValue;

	}

	/**
	 * Creates an equality-based {@link FieldsPair}.
	 *
	 * @param k
	 *            the field name
	 * @param v
	 *            the field value
	 * @param <K>
	 *            the field name type
	 * @param <V>
	 *            the field value type
	 * 
	 * @return a new field pair
	 */
	public static <K, V> FieldsPair<K, V> pair(
		K k, V v
	) {

		return new FieldsPair<>( k, v );

	}

	/**
	 * Creates a {@link FieldsPair} with the given condition.
	 *
	 * @param k
	 *            the field name
	 * @param v
	 *            the field value
	 * @param queryType
	 *            the query condition
	 * @param <K>
	 *            the field name type
	 * @param <V>
	 *            the field value type
	 * 
	 * @return a new field pair
	 */
	public static <K, V> FieldsPair<K, V> pair(
		K k, V v, Condition queryType
	) {

		return new FieldsPair<>( k, v, queryType );

	}

	/**
	 * Creates a {@link FieldsPair} for a condition that does not require an explicit value.
	 *
	 * @param k
	 *            the field name
	 * @param queryType
	 *            the query condition
	 * @param <K>
	 *            the field name type
	 * @param <V>
	 *            the field value type
	 * 
	 * @return a new field pair
	 */
	public static <K, V> FieldsPair<K, V> pair(
		K k, Condition queryType
	) {

		return new FieldsPair<>( k, queryType );

	}


	/**
	 * range 리스트([from, to])를 받아 between/gte/lte를 자동으로 선택.
	 * - 둘 다 있으면 between
	 * - from만 있으면 gte
	 * - to만 있으면 lte
	 * - 둘 다 없으면 null
	 * {@code
	 * 	List<Instant>, List<LocalDate> 등은 제네릭 타입만 달라 오버로드가 불가능하므로
	 * 	List<? extends T> 하나로 공통 처리한다.
	 * }
	 */
	/**
	 * Creates a range-based {@link FieldsPair} from a two-value range list.
	 * <p>This method automatically chooses {@code between}, {@code gte}, or {@code lte}
	 * depending on which bounds are present. If both bounds are missing, {@code null} is returned.</p>
	 * range 리스트([from, to])를 받아 between/gte/lte를 자동으로 선택.
	 * - 둘 다 있으면 between
	 * - from만 있으면 gte
	 * - to만 있으면 lte
	 * - 둘 다 없으면 null
	 * {@code
	 * 	List<Instant>, List<LocalDate> 등은 제네릭 타입만 달라 오버로드가 불가능하므로
	 * 	List<? extends T> 하나로 공통 처리한다.
	 * }
	 * 
	 * @param field
	 *            the field name
	 * @param range
	 *            the range list in the form {@code [from, to]}
	 * @param <K>
	 *            the field name type
	 * @param <T>
	 *            the bound type
	 * 
	 * @return a generated range pair, or {@code null} if no bound is available
	 */
	public static <K, T> FieldsPair<K, Object> autoRangePair(
		K field, List<? extends T> range
	) {

		if (range == null || range.isEmpty())
			return null;

		T from = range.size() >= 1 ? range.get( 0 ) : null;
		T to = range.size() >= 2 ? range.get( 1 ) : null;

		return buildAutoRangePair( field, from, to );

	}

	/**
	 * Convenience overload for automatically creating a range-based {@link FieldsPair}.
	 *
	 * @param field
	 *            the field name
	 * @param from
	 *            the lower bound
	 * @param to
	 *            the upper bound
	 * @param <K>
	 *            the field name type
	 * 
	 * @return a generated range pair, or {@code null} if both bounds are missing
	 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, Instant from, Instant to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/**
	 * Convenience overload for automatically creating a range-based {@link FieldsPair}.
	 *
	 * @param field
	 *            the field name
	 * @param from
	 *            the lower bound
	 * @param to
	 *            the upper bound
	 * @param <K>
	 *            the field name type
	 * 
	 * @return a generated range pair, or {@code null} if both bounds are missing
	 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, LocalDateTime from, LocalDateTime to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/**
	 * Convenience overload for automatically creating a range-based {@link FieldsPair}.
	 *
	 * @param field
	 *            the field name
	 * @param from
	 *            the lower bound
	 * @param to
	 *            the upper bound
	 * @param <K>
	 *            the field name type
	 * 
	 * @return a generated range pair, or {@code null} if both bounds are missing
	 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, LocalDate from, LocalDate to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/**
	 * 내부 공통 로직 (오버로드/리스트 모두 여기로 수렴)
	 */
	private static <K, T> FieldsPair<K, Object> buildAutoRangePair(
		K field, T from, T to
	) {

		if (from != null && to != null)
			return pair( field, List.of( from, to ), Condition.between );
		if (from != null)
			return pair( field, from, Condition.gte );
		if (to != null)
			return pair( field, to, Condition.lte );
		return null;

	}

}
