package com.byeolnaerim.mongodsl.state;


import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;


/**
 * Configuration for the MongoDB-backed unified DSL state store.
 * <p>{@code changeStreamConsumerId} isolates resume tokens between load-balanced Change Stream
 * consumers. Leave it null for a process-unique safe default; provide a stable per-instance value
 * only when that consumer must resume from the same token across process restarts.</p>
 */
public record MongoReactiveMongoDslStateStoreOptions(
	String collectionName,
	CursorCacheOptions cursorCacheOptions,
	boolean ensureIndexes,
	String changeStreamConsumerId
) {

	public MongoReactiveMongoDslStateStoreOptions(
		String collectionName,
		CursorCacheOptions cursorCacheOptions,
		boolean ensureIndexes
	) {

		this( collectionName, cursorCacheOptions, ensureIndexes, null );

	}

	public MongoReactiveMongoDslStateStoreOptions {

		if (collectionName == null || collectionName.isBlank())
			throw new IllegalArgumentException( "collectionName must not be blank" );
		collectionName = collectionName.trim();
		cursorCacheOptions = cursorCacheOptions == null ? CursorCacheOptions.defaults() : cursorCacheOptions;
		changeStreamConsumerId = changeStreamConsumerId == null || changeStreamConsumerId.isBlank()
			? null
			: changeStreamConsumerId.trim();

	}

	public static MongoReactiveMongoDslStateStoreOptions defaults() {

		return new MongoReactiveMongoDslStateStoreOptions(
			"__reactive_mongo_dsl_state",
			CursorCacheOptions.defaults(),
			true,
			null
		);

	}

}
