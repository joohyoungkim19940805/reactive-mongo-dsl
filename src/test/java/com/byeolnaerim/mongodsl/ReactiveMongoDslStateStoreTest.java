package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.change.ChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.change.ChangeStreamScope;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.state.InMemoryReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.state.ReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.paging.InMemoryCursorAnchorStore;
import com.byeolnaerim.mongodsl.sync.InMemoryEmbeddedSyncLeaseStore;
import reactor.core.publisher.Mono;


class ReactiveMongoDslStateStoreTest {

	// 단일 InMemoryReactiveMongoDslStateStore가 cursor anchor, Change Stream checkpoint, embedded-sync lease 상태를 모두 제공하는지 검증한다.
	@Test
	void oneInMemoryStoreServesCursorCheckpointAndEmbeddedLeaseState() {

		CursorCacheOptions cursorOptions = new CursorCacheOptions(
			Duration.ofSeconds( 10 ),
			1,
			Duration.ofMinutes( 1 ),
			100,
			16,
			0L,
			Duration.ofMillis( 10 ),
			64
		);
		try (InMemoryReactiveMongoDslStateStore store = new InMemoryReactiveMongoDslStateStore( cursorOptions )) {
			String queryKey = "query";
			assertTrue( store.floor( queryKey, 5, 100L ).block().isEmpty() );
			store.put( queryKey, new CursorAnchor( 4, new Document( "rank", 40 ) ) ).block();
			assertEquals( 4, store.floor( queryKey, 5, 100L ).block().orElseThrow().pageNumber() );

			assertEquals( 0L, store.namespaceVersion( "scope:db:items" ).block() );
			store.invalidateNamespace( "scope:db:items" ).block();
			assertEquals( 1L, store.namespaceVersion( "scope:db:items" ).block() );

			ChangeStreamScope scope = new ChangeStreamScope( new Object(), "db", "shared-scope" );
			BsonDocument token = BsonDocument.parse( "{\"_data\": \"token\"}" );
			store.save( scope, token ).block();
			assertEquals( token, store.load( scope ).block() );
			store.delete( scope ).block();
			assertTrue( store.load( scope ).blockOptional().isEmpty() );

			assertTrue( store.tryAcquire( "lease", "node-a", Duration.ofSeconds( 5 ) ).block() );
			assertFalse( store.tryAcquire( "lease", "node-b", Duration.ofSeconds( 5 ) ).block() );
			assertTrue( store.renew( "lease", "node-a", Duration.ofSeconds( 5 ) ).block() );
			store.release( "lease", "node-a" ).block();
			assertTrue( store.tryAcquire( "lease", "node-b", Duration.ofSeconds( 5 ) ).block() );

		}

	}

	// 서로 다른 backend를 조합한 composite state store가 cursor/checkpoint/embedded lease별 distributed scope 요구사항을 독립적으로 보존하는지 검증한다.
	@Test
	void advancedCompositeKeepsDistributionRequirementsPerFeature() {

		ChangeStreamCheckpointStore sharedCheckpoint = new ChangeStreamCheckpointStore() {
			@Override
			public Mono<BsonDocument> load(
				ChangeStreamScope scope
			) { return Mono.empty(); }

			@Override
			public Mono<Void> save(
				ChangeStreamScope scope, BsonDocument resumeToken
			) { return Mono.empty(); }

			@Override
			public boolean requiresDistributedStateScopeKey() { return true; }
		};

		ReactiveMongoDslStateStore store = ReactiveMongoDslStateStore.of(
			new InMemoryCursorAnchorStore(),
			sharedCheckpoint,
			new InMemoryEmbeddedSyncLeaseStore()
		);
		try {
			assertFalse( store.requiresDistributedCursorScopeKey() );
			assertTrue( store.requiresDistributedCheckpointScopeKey() );
			assertFalse( store.requiresDistributedEmbeddedSyncScopeKey() );

		} finally {
			store.close();

		}

	}

}
