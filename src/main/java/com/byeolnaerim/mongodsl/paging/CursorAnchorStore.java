package com.byeolnaerim.mongodsl.paging;


import java.time.Duration;
import java.util.Optional;
import org.bson.BsonTimestamp;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStoreMetadata;
import reactor.core.publisher.Mono;


/**
 * Reactive SPI for page-number cursor anchors, opaque cursor tokens, and namespace invalidation versions.
 * <p>The default implementation is process-local. Shared implementations can use Redis, MongoDB,
 * R2DBC, or another backend so anchors and invalidation versions are shared across nodes.</p>
 */
public interface CursorAnchorStore extends ReactiveMongoDslStateStoreMetadata {


	/** Returns the cursor safety/cache policy exposed by this store. */
	default CursorCacheOptions cursorCacheOptions() { return CursorCacheOptions.defaults(); }

	/** Stores an opaque token binding to a concrete keyset position. */
	default Mono<Void> putToken(
		String token, CursorTokenState state, Duration ttl
	) {

		return Mono.error( new UnsupportedOperationException( "This CursorAnchorStore does not support opaque cursor tokens." ) );

	}

	/** Resolves an opaque token previously issued by this store. */
	default Mono<Optional<CursorTokenState>> resolveToken(
		String token
	) {

		return Mono.error( new UnsupportedOperationException( "This CursorAnchorStore does not support opaque cursor tokens." ) );

	}

	Mono<Optional<CursorAnchor>> floor(
		String queryKey, int pageNumber, long estimatedSkip
	);

	Mono<Void> put(
		String queryKey, CursorAnchor anchor
	);

	/** Returns the current invalidation version for one physical collection namespace. */
	Mono<Long> namespaceVersion(
		String namespaceKey
	);

	/** Invalidates all query signatures depending on the namespace by advancing its version. */
	Mono<Void> invalidateNamespace(
		String namespaceKey
	);

	/**
	 * Invalidates one namespace for a concrete Change Stream event. Shared stores may override this
	 * overload to make duplicate/out-of-order delivery idempotent across application nodes.
	 */
	default Mono<Void> invalidateNamespace(
		String namespaceKey, BsonTimestamp clusterTime
	) {

		return invalidateNamespace( namespaceKey );

	}


}
