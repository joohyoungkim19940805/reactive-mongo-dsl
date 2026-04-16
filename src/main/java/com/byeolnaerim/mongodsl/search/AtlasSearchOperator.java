package com.byeolnaerim.mongodsl.search;

import org.bson.Document;

/**
 * Common contract for Atlas Search operators that can be rendered into the
 * body of a {@code $search} or {@code $searchMeta} stage.
 *
 * <p>Implementations should keep a strongly-typed fluent API on the outside and
 * convert themselves to {@link Document} only at render time.</p>
 */
public interface AtlasSearchOperator {

	/**
	 * Returns the Atlas Search operator name such as {@code text}, {@code phrase},
	 * or {@code compound}.
	 *
	 * @return the Atlas Search operator name
	 */
	String operatorName();

	/**
	 * Renders this operator as a one-entry document whose key is the operator name.
	 *
	 * @return the rendered operator document
	 */
	Document toDocument();
}
