package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.cache.InMemoryExpirationWheel;


class InMemoryExpirationWheelTest {

	private static final class State {

		private final AtomicLong deadlineNanos;

		private State(
			long deadlineNanos
		) {

			this.deadlineNanos = new AtomicLong( deadlineNanos );

		}

	}

	// 같은 state의 만료시각을 연장해도 기존 wheel reference를 재사용하면서 이전 deadline에 잘못 만료되지 않는지 검증한다.
	@Test
	void hotTouchExtendsTheDeadlineWithoutCreatingAnotherScheduledReference() throws Exception {

		CountDownLatch expired = new CountDownLatch( 1 );
		State state = new State( System.nanoTime() + Duration.ofMillis( 200 ).toNanos() );
		try (InMemoryExpirationWheel<String, State> wheel = new InMemoryExpirationWheel<>(
			Duration.ofMillis( 20 ),
			64,
			value -> value.deadlineNanos.get(),
			(key, value) -> expired.countDown()
		)) {
			wheel.schedule( "hot", state );
			Thread.sleep( 100L );
			state.deadlineNanos.set( System.nanoTime() + Duration.ofMillis( 350 ).toNanos() );

			assertFalse( expired.await( 180L, TimeUnit.MILLISECONDS ), "the stale first deadline must not evict a touched state" );
			assertTrue( expired.await( 500L, TimeUnit.MILLISECONDS ), "the touched state must still expire at the extended deadline" );

		}

	}

	// 다수의 독립 만료 항목이 전체 map sweep 없이 timing wheel 기반으로 모두 만료 처리되는지 검증한다.
	@Test
	void manyIndependentDeadlinesExpireWithoutAFullMapSweepContract() throws Exception {

		int count = 1_000;
		CountDownLatch expired = new CountDownLatch( count );
		try (InMemoryExpirationWheel<Integer, State> wheel = new InMemoryExpirationWheel<>(
			Duration.ofMillis( 10 ),
			128,
			value -> value.deadlineNanos.get(),
			(key, value) -> expired.countDown()
		)) {
			long base = System.nanoTime();
			for (int i = 0; i < count; i++)
				wheel.schedule( i, new State( base + Duration.ofMillis( 50L + (i % 10) * 5L ).toNanos() ) );

			assertTrue( expired.await( 2L, TimeUnit.SECONDS ) );

		}

	}

}
