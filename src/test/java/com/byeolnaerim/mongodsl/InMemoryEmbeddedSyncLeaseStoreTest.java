package com.byeolnaerim.mongodsl;


import java.time.Duration;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.sync.InMemoryEmbeddedSyncLeaseStore;
import reactor.test.StepVerifier;


class InMemoryEmbeddedSyncLeaseStoreTest {

	// 동일 relation lease는 한 owner만 획득하고 기존 owner가 release한 뒤 다음 owner가 획득할 수 있는지 검증한다.
	@Test
	void onlyOneOwnerHoldsARelationLeaseAndReleaseAllowsTheNextOwner() {

		try (InMemoryEmbeddedSyncLeaseStore store = new InMemoryEmbeddedSyncLeaseStore()) {
			StepVerifier.create( store.tryAcquire( "relation", "node-a", Duration.ofSeconds( 5 ) ) )
				.expectNext( true )
				.verifyComplete();
			StepVerifier.create( store.tryAcquire( "relation", "node-b", Duration.ofSeconds( 5 ) ) )
				.expectNext( false )
				.verifyComplete();
			StepVerifier.create( store.renew( "relation", "node-a", Duration.ofSeconds( 5 ) ) )
				.expectNext( true )
				.verifyComplete();
			StepVerifier.create( store.release( "relation", "node-a" ) )
				.verifyComplete();
			StepVerifier.create( store.tryAcquire( "relation", "node-b", Duration.ofSeconds( 5 ) ) )
				.expectNext( true )
				.verifyComplete();

		}

	}

	// 만료된 lease는 별도의 전체 cleanup scan을 기다리지 않고 다음 owner가 즉시 대체 획득할 수 있는지 검증한다.
	@Test
	void expiredOwnerCanBeReplacedWithoutWaitingForAGlobalCleanupScan() throws Exception {

		try (InMemoryEmbeddedSyncLeaseStore store = new InMemoryEmbeddedSyncLeaseStore()) {
			StepVerifier.create( store.tryAcquire( "relation", "node-a", Duration.ofMillis( 100 ) ) )
				.expectNext( true )
				.verifyComplete();

			Thread.sleep( 150L );

			StepVerifier.create( store.tryAcquire( "relation", "node-b", Duration.ofSeconds( 5 ) ) )
				.expectNext( true )
				.verifyComplete();

		}

	}

}
