package com.byeolnaerim.mongodsl.change;


import org.bson.BsonDocument;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStoreMetadata;
import reactor.core.publisher.Mono;


/**
 * Reactive persistence SPI for MongoDB change-stream resume tokens.
 * <p>Implementations may persist checkpoints in Redis, MongoDB, R2DBC, or another shared store
 * without adding that dependency to the DSL core.</p>
 */
public interface ChangeStreamCheckpointStore extends ReactiveMongoDslStateStoreMetadata {

	Mono<BsonDocument> load(
		ChangeStreamScope scope
	);

	Mono<Void> save(
		ChangeStreamScope scope, BsonDocument resumeToken
	);

	default Mono<Void> delete(
		ChangeStreamScope scope
	) {

		return Mono.empty();

	}

}
