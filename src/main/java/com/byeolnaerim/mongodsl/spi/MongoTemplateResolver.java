package com.byeolnaerim.mongodsl.spi;


import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;


/**
 * Strategy interface for resolving {@link ReactiveMongoTemplate} and transaction resources
 * for a logical key.
 *
 * @param <K>
 *            the logical template key type
 */
public interface MongoTemplateResolver<K> {

	/**
	 * Returns the {@link ReactiveMongoTemplate} associated with the given key.
	 *
	 * @param key
	 *            the logical template key
	 * 
	 * @return the resolved reactive Mongo template
	 */
	ReactiveMongoTemplate getTemplate(
		K key
	);

	/**
	 * Returns the {@link ReactiveMongoTransactionManager} associated with the given key.
	 *
	 * @param key
	 *            the logical template key
	 * 
	 * @return the resolved transaction manager, or {@code null} if transaction management is not
	 *         configured
	 */
	@Nullable
	ReactiveMongoTransactionManager getTxManager(
		K key
	);

	/**
	 * Returns the {@link TransactionalOperator} associated with the given key.
	 *
	 * @param key
	 *            the logical template key
	 * 
	 * @return the resolved transactional operator, or {@code null} if transactional execution is not
	 *         configured
	 */
	@Nullable
	TransactionalOperator getTxOperator(
		K key
	);
}

