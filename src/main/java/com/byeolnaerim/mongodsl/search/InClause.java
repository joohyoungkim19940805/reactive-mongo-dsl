package com.byeolnaerim.mongodsl.search;


import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import com.mongodb.client.model.search.FieldSearchPath;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * DSL-friendly Atlas Search {@code in} operator backed by MongoDB driver's search API.
 */
public final class InClause extends AbstractSearchOperator {

	private FieldSearchPath path;

	private final List<Object> values = new ArrayList<>();

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public InClause path(
		String path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public InClause path(
		Enum<?> path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	public InClause path(
		FieldSearchPath path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Fallback for custom path wrappers. Common callers should prefer String, Enum, or FieldSearchPath.
	 */
	public InClause path(
		Object path
	) {

		this.path = SearchPathResolver.resolveFieldPath( path );
		return this;

	}

	/**
	 * Adds string values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesStrings(
		Collection<String> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds boolean values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesBooleans(
		Collection<Boolean> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds integer values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesIntegers(
		Collection<Integer> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds long values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesLongs(
		Collection<Long> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds double values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesDoubles(
		Collection<Double> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds instant values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesInstants(
		Collection<Instant> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds object-id values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesObjectIds(
		Collection<ObjectId> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds UUID values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause valuesUuids(
		Collection<UUID> values
	) {

		this.values.addAll( values );
		return this;

	}

	/**
	 * Adds raw values for cases where the caller has already normalized them.
	 *
	 * @param values
	 *            the raw values
	 *
	 * @return this builder
	 */
	public InClause valuesRaw(
		Collection<?> values
	) {

		this.values.addAll( values );
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
	public InClause score(
		SearchScoreSpec score
	) {

		this.score = score == null ? null : score.toSearchScore();
		return this;

	}

	public InClause score(
		SearchScore score
	) {

		this.score = score;
		return this;

	}

	@Override
	public String operatorName() {

		return "in";

	}

	@Override
	public SearchOperator toSearchOperator() {

		if (this.path == null) { throw new IllegalStateException( "in.path is required" ); }

		if (this.values.isEmpty()) { throw new IllegalStateException( "in.value is required" ); }

		return applyScore( SearchOperator.in( this.path, this.values ) );

	}

}
