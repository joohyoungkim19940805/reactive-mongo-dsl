package com.byeolnaerim.mongodsl.change;


import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonDocument;
import reactor.core.publisher.Mono;


/** Default process-local change-stream resume-token store. */
public final class InMemoryChangeStreamCheckpointStore implements ChangeStreamCheckpointStore {

	private final ConcurrentHashMap<ChangeStreamScope, BsonDocument> tokens = new ConcurrentHashMap<>();

	@Override
	public Mono<BsonDocument> load(
		ChangeStreamScope scope
	) {

		return Mono.defer( () -> Mono.justOrEmpty( tokens.get( scope ) ) );

	}

	@Override
	public Mono<Void> save(
		ChangeStreamScope scope, BsonDocument resumeToken
	) {

		return Mono.fromRunnable( () -> tokens.put( scope, resumeToken ) );

	}

	@Override
	public Mono<Void> delete(
		ChangeStreamScope scope
	) {

		return Mono.fromRunnable( () -> tokens.remove( scope ) );

	}

}
