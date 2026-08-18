package com.byeolnaerim.mongodsl.search;


import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.ObjectId;
import com.mongodb.client.model.search.DateRangeSearchOperatorBase;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.NumberRangeSearchOperatorBase;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code range} operator.
 * <p>Number and date ranges are delegated to the MongoDB driver's typed range builders. The DSL
 * keeps a narrow driver escape hatch for range value types/combinations the current driver does
 * not model directly, preserving the existing API without making the rest of the search package
 * own Atlas Search BSON grammar.</p>
 */
public final class RangeClause extends AbstractSearchOperator {

	private FieldSearchPath path;

	private Object gt;

	private Object gte;

	private Object lt;

	private Object lte;

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public RangeClause path(
		String path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public RangeClause path(
		Enum<?> path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public RangeClause path(
		FieldSearchPath path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Fallback for custom path wrappers. Common callers should prefer String, Enum, or FieldSearchPath.
	 */
	public RangeClause path(
		Object path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		int value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		long value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		double value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		String value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		Instant value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gt(
		ObjectId value
	) {

		this.gt = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		int value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		long value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		double value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		String value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		Instant value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause gte(
		ObjectId value
	) {

		this.gte = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		int value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		long value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		double value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		String value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		Instant value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lt(
		ObjectId value
	) {

		this.lt = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		int value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		long value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		double value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		String value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		Instant value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause lte(
		ObjectId value
	) {

		this.lte = value;
		return this;

	}

	/**
	 * Sets the score specification.
	 *
	 * @param score
	 *            the score specification
	 *
	 * @return this builder
	 */
	public RangeClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public RangeClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "range";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.path == null) { throw new IllegalStateException( "range.path is required" ); }

		if (this.gt == null && this.gte == null && this.lt == null && this.lte == null) { throw new IllegalStateException( "range requires at least one of gt/gte/lt/lte" ); }

		if (this.gt != null && this.gte != null) { throw new IllegalStateException( "range cannot use gt and gte together" ); }

		if (this.lt != null && this.lte != null) { throw new IllegalStateException( "range cannot use lt and lte together" ); }

		List<Object> bounds = java.util.stream.Stream
			.of( this.gt, this.gte, this.lt, this.lte )
			.filter( Objects::nonNull )
			.toList();

		if (bounds.stream().allMatch( Number.class::isInstance )) { return applyScore( buildNumberRange( SearchOperator.numberRange( this.path ) ) ); }

		if (bounds.stream().allMatch( Instant.class::isInstance )) { return applyScore( buildDateRange( SearchOperator.dateRange( this.path ) ) ); }

		if (bounds.stream().allMatch( String.class::isInstance ) || bounds.stream().allMatch( ObjectId.class::isInstance )) { return applyScore( buildDriverEscapeHatchRange() ); }

		throw new IllegalStateException( "range bounds must all use the same supported value type" );

	}

	private SearchOperator buildNumberRange(
		NumberRangeSearchOperatorBase range
	) {

		Number lower = this.gt instanceof Number number ? number
			: this.gte instanceof Number number ? number : null;
		Number upper = this.lt instanceof Number number ? number
			: this.lte instanceof Number number ? number : null;

		if (lower != null && upper != null) {

			if (this.gt != null) { return this.lt != null ? range.gtLt( lower, upper ) : range.gtLte( lower, upper ); }

			return this.lt != null ? range.gteLt( lower, upper ) : range.gteLte( lower, upper );

		}

		if (lower != null) { return this.gt != null ? range.gt( lower ) : range.gte( lower ); }

		return this.lt != null ? range.lt( upper ) : range.lte( upper );

	}

	private SearchOperator buildDateRange(
		DateRangeSearchOperatorBase range
	) {

		Instant lower = this.gt instanceof Instant instant ? instant
			: this.gte instanceof Instant instant ? instant : null;
		Instant upper = this.lt instanceof Instant instant ? instant
			: this.lte instanceof Instant instant ? instant : null;

		if (lower != null && upper != null) {

			if (this.gt != null) { return this.lt != null ? range.gtLt( lower, upper ) : range.gtLte( lower, upper ); }

			return this.lt != null ? range.gteLt( lower, upper ) : range.gteLte( lower, upper );

		}

		if (lower != null) { return this.gt != null ? range.gt( lower ) : range.gte( lower ); }

		return this.lt != null ? range.lt( upper ) : range.lte( upper );

	}

	private SearchOperator buildDriverEscapeHatchRange() {

		Document body = new Document( "path", this.path.toValue() );

		if (this.gt != null) {
			body.append( "gt", this.gt );

		}

		if (this.gte != null) {
			body.append( "gte", this.gte );

		}

		if (this.lt != null) {
			body.append( "lt", this.lt );

		}

		if (this.lte != null) {
			body.append( "lte", this.lte );

		}

		return SearchOperator.of( new Document( "range", body ) );

	}

}
