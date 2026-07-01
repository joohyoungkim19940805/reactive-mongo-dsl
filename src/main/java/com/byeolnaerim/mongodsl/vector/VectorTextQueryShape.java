package com.byeolnaerim.mongodsl.vector;


/**
 * BSON shape for MongoDB Automated Embedding text queries in
 * {@code $vectorSearch.query}.
 */
public enum VectorTextQueryShape {

	/**
	 * Renders {@code query} as a plain string.
	 */
	STRING,

	/**
	 * Renders {@code query} as {@code { text: "..." }} for server/API variants
	 * that expect an object value.
	 */
	TEXT_OBJECT

}
