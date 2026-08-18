package com.byeolnaerim.mongodsl.search;


import java.time.Instant;
import java.util.UUID;
import org.bson.types.ObjectId;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code equals} operator backed by MongoDB driver's search API.
 */
public final class EqualsClause extends AbstractSearchOperator {

	private FieldSearchPath path;

	private Object value;

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public EqualsClause path(
		String path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public EqualsClause path(
		Enum<?> path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public EqualsClause path(
		FieldSearchPath path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Fallback for custom path wrappers. Common callers should prefer String, Enum, or FieldSearchPath.
	 */
	public EqualsClause path(
		Object path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
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
	public EqualsClause value(
		String value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets a boolean value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Boolean value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets an integer value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Integer value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets a long value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Long value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets a double value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Double value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets a float value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Float value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets an instant value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		Instant value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets an object-id value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		ObjectId value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Sets a UUID value.
	 *
	 * @param value
	 *            the value
	 *
	 * @return this builder
	 */
	public EqualsClause value(
		UUID value
	) {

		this.value = value;
		return this;

	}

	/**
	 * Explicitly sets the value to {@code null}.
	 *
	 * @return this builder
	 */
	public EqualsClause valueNull() {

		this.value = null;
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
	public EqualsClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public EqualsClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "equals";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.path == null) { throw new IllegalStateException( "equals.path is required" ); }

		SearchOperator operator;

		if (this.value == null) {
			operator = SearchOperator.equalsNull( this.path );

		} else if (this.value instanceof Boolean bool) {
			operator = SearchOperator.equals( this.path, bool );

		} else if (this.value instanceof ObjectId objectId) {
			operator = SearchOperator.equals( this.path, objectId );

		} else if (this.value instanceof Number number) {
			operator = SearchOperator.equals( this.path, number );

		} else if (this.value instanceof Instant instant) {
			operator = SearchOperator.equals( this.path, instant );

		} else if (this.value instanceof String string) {
			operator = SearchOperator.equals( this.path, string );

		} else if (this.value instanceof UUID uuid) {
			operator = SearchOperator.equals( this.path, uuid );

		} else {
			throw new IllegalStateException( "Unsupported equals value type: " + this.value.getClass().getName() );

		}

		return applyScore( operator );

	}

}
