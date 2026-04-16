package com.byeolnaerim.mongodsl.search;

import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Strongly typed Atlas Search {@code equals} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class EqualsClause<K> extends AbstractSearchOperator {

	private String path;

	private Object value;

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public EqualsClause<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
		return this;
	}

	/**
	 * Sets a string value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		String value
	) { this.value = value; return this; }

	/**
	 * Sets a boolean value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Boolean value
	) { this.value = value; return this; }

	/**
	 * Sets an integer value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Integer value
	) { this.value = value; return this; }

	/**
	 * Sets a long value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Long value
	) { this.value = value; return this; }

	/**
	 * Sets a double value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Double value
	) { this.value = value; return this; }

	/**
	 * Sets a float value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Float value
	) { this.value = value; return this; }

	/**
	 * Sets an instant value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		Instant value
	) { this.value = value; return this; }

	/**
	 * Sets an object-id value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		ObjectId value
	) { this.value = value; return this; }

	/**
	 * Sets a UUID value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause<K> value(
		UUID value
	) { this.value = value; return this; }

	/**
	 * Explicitly sets the value to {@code null}.
	 *
	 * @return this builder
	 */
	public EqualsClause<K> valueNull() { this.value = null; return this; }

	/**
	 * Sets the score specification.
	 *
	 * @param score
	 *            the score specification
	 *
	 * @return this builder
	 */
	public EqualsClause<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "equals";
	}

	@Override
	public Document toDocument() {

		if (this.path == null || this.path.isBlank()) {
			throw new IllegalStateException( "equals.path is required" );

		}

		Document body = new Document()
			.append( "path", this.path )
			.append( "value", this.value );

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
