package com.byeolnaerim.mongodsl.spi;


/**
 * Strategy interface for resolving a {@link MongoExecutionContext} for a logical key.
 *
 * @param <K>
 *            the logical template key type
 */
public interface MongoTemplateResolver<K> {

	/**
	 * Returns the Mongo execution context associated with the given key.
	 *
	 * @param key
	 *            the logical template key
	 * 
	 * @return the resolved Mongo execution context
	 */
	MongoExecutionContext getTemplate(
		K key
	);

}
