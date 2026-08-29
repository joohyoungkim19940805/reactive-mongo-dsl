package com.byeolnaerim.mongodsl.sync;


import java.time.Duration;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStoreMetadata;
import reactor.core.publisher.Mono;


/**
 * Coordination SPI used to ensure that only one application instance performs an embedded-sync
 * relation at a time. Shared implementations may use Redis, MongoDB, R2DBC, or another backend.
 */
public interface EmbeddedSyncLeaseStore extends ReactiveMongoDslStateStoreMetadata {

	Mono<Boolean> tryAcquire(
		String leaseKey, String ownerId, Duration ttl
	);

	Mono<Boolean> renew(
		String leaseKey, String ownerId, Duration ttl
	);

	Mono<Void> release(
		String leaseKey, String ownerId
	);

}
