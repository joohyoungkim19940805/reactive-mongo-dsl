package com.byeolnaerim.mongodsl.vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Strongly typed query-vector wrapper for MongoDB {@code $vectorSearch}.
 *
 * <p>The MongoDB Java driver recommends {@code BinaryVector} for efficiency,
 * but MongoDB also accepts a {@code List<Double>} as the query vector. This DSL
 * intentionally keeps the public API independent from driver-specific vector
 * types and renders a BSON-ready {@code List<Double>} at stage-build time.</p>
 */
public final class VectorQueryVector {

	private final List<Double> values;

	private VectorQueryVector(
		List<Double> values
	) {
		this.values = values;
	}

	/**
	 * Creates a query vector from a float array.
	 *
	 * @param values
	 *            the float values
	 *
	 * @return the wrapped query vector
	 */
	public static VectorQueryVector ofFloatArray(
		float[] values
	) {

		Objects.requireNonNull( values, "values" );

		if (values.length == 0) {
			throw new IllegalArgumentException( "values must not be empty" );

		}

		List<Double> result = new ArrayList<>( values.length );

		for (float value : values) {
			result.add( (double) value );
		}

		return new VectorQueryVector( result );

	}

	/**
	 * Creates a query vector from a double array.
	 *
	 * @param values
	 *            the double values
	 *
	 * @return the wrapped query vector
	 */
	public static VectorQueryVector ofDoubleArray(
		double[] values
	) {

		Objects.requireNonNull( values, "values" );

		if (values.length == 0) {
			throw new IllegalArgumentException( "values must not be empty" );

		}

		List<Double> result = new ArrayList<>( values.length );

		for (double value : values) {
			result.add( value );
		}

		return new VectorQueryVector( result );

	}

	/**
	 * Creates a query vector from a collection of doubles.
	 *
	 * @param values
	 *            the vector values
	 *
	 * @return the wrapped query vector
	 */
	public static VectorQueryVector ofDoubleList(
		Collection<Double> values
	) {

		Objects.requireNonNull( values, "values" );

		if (values.isEmpty()) {
			throw new IllegalArgumentException( "values must not be empty" );

		}

		List<Double> result = new ArrayList<>( values.size() );

		for (Double value : values) {
			if (value == null) {
				throw new IllegalArgumentException( "values must not contain null" );

			}
			result.add( value );
		}

		return new VectorQueryVector( result );

	}

	/**
	 * Returns the BSON-ready vector value.
	 *
	 * @return the BSON-ready vector value
	 */
	public List<Double> toBsonValue() {
		return new ArrayList<>( this.values );
	}

}
