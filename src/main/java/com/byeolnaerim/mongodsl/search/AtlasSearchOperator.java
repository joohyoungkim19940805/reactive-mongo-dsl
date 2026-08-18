package com.byeolnaerim.mongodsl.search;


import java.util.Objects;
import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.search.SearchOperator;


/**
 * Application-level Atlas Search operator contract.
 * <p>The original {@link #toDocument()} contract remains the compatibility boundary for
 * application-defined operators. Built-in DSL operators additionally expose the MongoDB driver's
 * {@link SearchOperator} through {@link #toSearchOperator()}, allowing the driver to own BSON
 * rendering wherever it has a typed API.</p>
 */
public interface AtlasSearchOperator {

	String operatorName();

	Document toDocument();

	/**
	 * Returns this operator as a MongoDB driver search operator.
	 * <p>Custom operators written against earlier DSL versions continue to work because the
	 * default bridge wraps their existing BSON representation with the driver's official escape
	 * hatch.</p>
	 *
	 * @return the driver-native search operator
	 */
	default SearchOperator toSearchOperator() {

		return SearchOperator.of( toDocument() );

	}

	static AtlasSearchOperator of(
		String operatorName, SearchOperator operator
	) {

		Objects.requireNonNull( operatorName, "operatorName" );
		Objects.requireNonNull( operator, "operator" );
		return new AtlasSearchOperator() {

			@Override
			public String operatorName() {

				return operatorName;

			}

			@Override
			public Document toDocument() {

				return MongoBsonSupport.toDocument( operator );

			}

			@Override
			public SearchOperator toSearchOperator() {

				return operator;

			}

		};

	}

}
