package com.byeolnaerim.mongodsl.search;

import java.time.Instant;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Strongly typed Atlas Search {@code range} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class RangeClause<K> extends AbstractSearchOperator {

	private String path;

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
	public RangeClause<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
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
	public RangeClause<K> gt(
		int value
	) { this.gt = value; return this; }

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gt(
		long value
	) { this.gt = value; return this; }

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gt(
		double value
	) { this.gt = value; return this; }

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gt(
		String value
	) { this.gt = value; return this; }

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gt(
		Instant value
	) { this.gt = value; return this; }

	/**
	 * Sets a strict lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gt(
		ObjectId value
	) { this.gt = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		int value
	) { this.gte = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		long value
	) { this.gte = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		double value
	) { this.gte = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		String value
	) { this.gte = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		Instant value
	) { this.gte = value; return this; }

	/**
	 * Sets an inclusive lower bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> gte(
		ObjectId value
	) { this.gte = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		int value
	) { this.lt = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		long value
	) { this.lt = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		double value
	) { this.lt = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		String value
	) { this.lt = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		Instant value
	) { this.lt = value; return this; }

	/**
	 * Sets a strict upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lt(
		ObjectId value
	) { this.lt = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		int value
	) { this.lte = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		long value
	) { this.lte = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		double value
	) { this.lte = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		String value
	) { this.lte = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		Instant value
	) { this.lte = value; return this; }

	/**
	 * Sets an inclusive upper bound.
	 *
	 * @param value
	 *            the bound value
	 *
	 * @return this builder
	 */
	public RangeClause<K> lte(
		ObjectId value
	) { this.lte = value; return this; }

	/**
	 * Sets the score specification.
	 *
	 * @param score
	 *            the score specification
	 *
	 * @return this builder
	 */
	public RangeClause<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "range";
	}

	@Override
	public Document toDocument() {

		if (this.path == null || this.path.isBlank()) {
			throw new IllegalStateException( "range.path is required" );

		}

		if (this.gt == null && this.gte == null && this.lt == null && this.lte == null) {
			throw new IllegalStateException( "range requires at least one of gt/gte/lt/lte" );

		}

		Document body = new Document( "path", this.path );

		if (this.gt != null)
			body.append( "gt", this.gt );
		if (this.gte != null)
			body.append( "gte", this.gte );
		if (this.lt != null)
			body.append( "lt", this.lt );
		if (this.lte != null)
			body.append( "lte", this.lte );

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
