package com.byeolnaerim.mongodsl.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Strongly typed Atlas Search {@code in} operator.
 *
 * @param <K>
 *            the logical path type
 */
public final class InClause<K> extends AbstractSearchOperator {

	private String path;

	private final List<Object> values = new ArrayList<>();

	/**
	 * Sets the target path.
	 *
	 * @param path
	 *            the path input
	 *
	 * @return this builder
	 */
	public InClause<K> path(
		K path
	) {
		this.path = SearchPathResolver.resolve( path );
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
	public InClause<K> valuesStrings(
		Collection<String> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds boolean values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesBooleans(
		Collection<Boolean> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds integer values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesIntegers(
		Collection<Integer> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds long values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesLongs(
		Collection<Long> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds double values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesDoubles(
		Collection<Double> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds instant values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesInstants(
		Collection<Instant> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds object-id values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesObjectIds(
		Collection<ObjectId> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds UUID values.
	 *
	 * @param values
	 *            the values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesUuids(
		Collection<UUID> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Adds raw values for cases where the caller has already normalized them.
	 *
	 * @param values
	 *            the raw values
	 *
	 * @return this builder
	 */
	public InClause<K> valuesRaw(
		Collection<?> values
	) { this.values.addAll( values ); return this; }

	/**
	 * Sets the score specification.
	 *
	 * @param score
	 *            the score specification
	 *
	 * @return this builder
	 */
	public InClause<K> score(
		SearchScoreSpec score
	) {
		this.score = score;
		return this;
	}

	@Override
	public String operatorName() {
		return "in";
	}

	@Override
	public Document toDocument() {

		if (this.path == null || this.path.isBlank()) {
			throw new IllegalStateException( "in.path is required" );

		}

		if (this.values.isEmpty()) {
			throw new IllegalStateException( "in.value is required" );

		}

		Document body = new Document()
			.append( "path", this.path )
			.append( "value", new ArrayList<>( this.values ) );

		applyScore( body );
		return new Document( operatorName(), body );

	}
}
