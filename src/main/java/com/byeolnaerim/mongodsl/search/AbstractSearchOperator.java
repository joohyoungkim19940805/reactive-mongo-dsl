package com.byeolnaerim.mongodsl.search;


import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchScore;


/**
 * Base class for Atlas Search operators that optionally support a score modifier.
 */
abstract class AbstractSearchOperator implements AtlasSearchOperator {

	protected SearchScore score;

	protected SearchOperator applyScore(
		SearchOperator operator
	) {

		return this.score == null ? operator : operator.score( this.score );

	}

	@Override
	public Document toDocument() {

		return MongoBsonSupport.toDocument( toSearchOperator() );

	}

}
