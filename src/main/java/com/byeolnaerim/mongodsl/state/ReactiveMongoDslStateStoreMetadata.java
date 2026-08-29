package com.byeolnaerim.mongodsl.state;


import java.util.Set;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Mono;


/**
 * Common capabilities exposed by state stores used by the DSL.
 * <p>Implementations that persist state in a MongoDB collection can expose that collection here
 * so a database-wide Change Stream can exclude internal state writes at the server pipeline.</p>
 */
public interface ReactiveMongoDslStateStoreMetadata extends AutoCloseable {

	/** Shared external implementations should require a stable cross-process scope key. */
	default boolean requiresDistributedStateScopeKey() { return false; }

	/**
	 * Returns internal collection names that must be excluded for the exact watched Mongo scope.
	 * Non-Mongo stores normally return an empty set.
	 */
	default Mono<Set<String>> changeStreamExcludedCollections(
		MongoExecutionContext executionContext, MongoDatabase database
	) {

		return Mono.just( Set.of() );

	}

	@Override
	default void close() {}

}
