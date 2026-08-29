package com.byeolnaerim.mongodsl.state;


import com.byeolnaerim.mongodsl.change.ChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.paging.CursorAnchorStore;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncLeaseStore;


/**
 * Unified state-store SPI used by cursor paging, Change Stream checkpoints, and embedded-sync leases.
 * <p>The zero-configuration default is process-local. A shared implementation can be backed by
 * Redis, MongoDB, R2DBC, a distributed key/value store, or any other backend.</p>
 */
public interface ReactiveMongoDslStateStore extends CursorAnchorStore, ChangeStreamCheckpointStore, EmbeddedSyncLeaseStore {

	default boolean requiresDistributedCursorScopeKey() { return requiresDistributedStateScopeKey(); }

	default boolean requiresDistributedCheckpointScopeKey() { return requiresDistributedStateScopeKey(); }

	default boolean requiresDistributedEmbeddedSyncScopeKey() { return requiresDistributedStateScopeKey(); }

	/**
	 * Combines independently implemented stores into the single state-store API.
	 * This is the advanced escape hatch when each feature intentionally uses a different backend.
	 */
	static ReactiveMongoDslStateStore of(
		CursorAnchorStore cursorStore,
		ChangeStreamCheckpointStore checkpointStore,
		EmbeddedSyncLeaseStore embeddedSyncLeaseStore
	) {

		return new CompositeReactiveMongoDslStateStore( cursorStore, checkpointStore, embeddedSyncLeaseStore );

	}

}
