package com.byeolnaerim.mongodsl.search;


import java.util.Objects;
import com.mongodb.client.model.search.SearchOperator;


/**
 * Static entry points for strongly typed Atlas Search operator builders.
 */
public final class SearchOperators {

	private SearchOperators() {}

	/**
	 * Wraps a MongoDB driver {@link SearchOperator} so advanced callers can use driver-native
	 * operators without waiting for a dedicated DSL convenience method.
	 *
	 * @param operatorName
	 *            the operator name used for diagnostics
	 * @param operator
	 *            the driver-native search operator
	 * 
	 * @return the wrapped operator
	 */
	public static AtlasSearchOperator driver(
		String operatorName, SearchOperator operator
	) {

		return AtlasSearchOperator
			.of(
				Objects.requireNonNull( operatorName, "operatorName" ),
				Objects.requireNonNull( operator, "operator" )
			);

	}

	/**
	 * Creates a {@code text} operator builder.
	 *
	 * @return a new {@code text} operator builder
	 */
	public static TextClause text() {

		return new TextClause();

	}

	/**
	 * Creates a {@code phrase} operator builder.
	 *
	 * @return a new {@code phrase} operator builder
	 */
	public static PhraseClause phrase() {

		return new PhraseClause();

	}

	/**
	 * Creates an {@code autocomplete} operator builder.
	 *
	 * @return a new {@code autocomplete} operator builder
	 */
	public static AutocompleteClause autocomplete() {

		return new AutocompleteClause();

	}

	/**
	 * Creates an {@code equals} operator builder.
	 *
	 * @return a new {@code equals} operator builder
	 */
	public static EqualsClause equals() {

		return new EqualsClause();

	}

	/**
	 * Creates an {@code in} operator builder.
	 *
	 * @return a new {@code in} operator builder
	 */
	public static InClause in() {

		return new InClause();

	}

	/**
	 * Creates an {@code exists} operator builder.
	 *
	 * @return a new {@code exists} operator builder
	 */
	public static ExistsClause exists() {

		return new ExistsClause();

	}

	/**
	 * Creates a {@code range} operator builder.
	 *
	 * @return a new {@code range} operator builder
	 */
	public static RangeClause range() {

		return new RangeClause();

	}

}
