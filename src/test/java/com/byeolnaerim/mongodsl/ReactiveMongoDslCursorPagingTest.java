package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.BsonTimestamp;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.paging.CursorAnchor;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorSkipExceededAction;
import com.byeolnaerim.mongodsl.paging.CursorPaginationSupport;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.paging.InMemoryCursorAnchorStore;
import com.mongodb.client.model.Filters;
import reactor.test.StepVerifier;


class ReactiveMongoDslCursorPagingTest {

	// cursor sort에 _id tie-breaker를 자동 추가하고 anchor 포함 시작 조건을 정렬 방향에 맞게 생성하는지 검증한다.
	@Test
	void cursorSortAddsUniqueIdTieBreakerAndBuildsInclusivePageStartFilter() {

		Document sort = CursorPaginationSupport.normalizeSort( new Document( "createdAt", -1 ) ).orElseThrow();
		assertEquals( new Document( "createdAt", -1 ).append( "_id", -1 ), sort );

		Document values = new Document( "createdAt", 10 ).append( "_id", 5 );
		assertEquals(
			MongoBsonSupport.toDocument(
				Filters.or(
					Filters.lt( "createdAt", 10 ),
					Filters.and( Filters.eq( "createdAt", 10 ), Filters.lte( "_id", 5 ) )
				)
			),
			MongoBsonSupport.toDocument( CursorPaginationSupport.atOrAfterAnchor( sort, values ) )
		);

	}

	// 예상 skip이 큰 deep page query는 즉시 cursor cache에 admission되고 저장한 page anchor를 재사용하는지 검증한다.
	@Test
	void deepPageIsAdmittedImmediatelyAndReusesItsOwnPageStartAnchor() {

		try (InMemoryCursorAnchorStore store = new InMemoryCursorAnchorStore(
			new CursorCacheOptions(
				Duration.ofSeconds( 10 ),
				4,
				Duration.ofMinutes( 1 ),
				100,
				16,
				5_000,
				Duration.ofMillis( 10 ),
				64
			)
		)) {
			StepVerifier.create( store.floor( "query", 777, 15_540 ) )
				.expectNext( Optional.empty() )
				.verifyComplete();

			StepVerifier.create( store.put( "query", new CursorAnchor( 777, new Document( "_id", 777 ) ) ) )
				.verifyComplete();

			StepVerifier.create( store.floor( "query", 777, 15_540 ) )
				.assertNext( anchor -> {
					assertTrue( anchor.isPresent() );
					assertEquals( 777, anchor.orElseThrow().pageNumber() );

				} )
				.verifyComplete();

		}

	}

	// 일반 query는 설정된 hot request 횟수를 충족한 뒤에만 cursor anchor cache에 admission되는지 검증한다.
	@Test
	void ordinaryQueryIsAdmittedOnlyAfterConfiguredHotRequestThreshold() {

		try (InMemoryCursorAnchorStore store = new InMemoryCursorAnchorStore(
			new CursorCacheOptions(
				Duration.ofSeconds( 10 ),
				2,
				Duration.ofMinutes( 1 ),
				100,
				16,
				10_000,
				Duration.ofMillis( 10 ),
				64
			)
		)) {
			StepVerifier.create( store.floor( "query", 2, 40 ) ).expectNext( Optional.empty() ).verifyComplete();
			StepVerifier.create( store.put( "query", new CursorAnchor( 2, new Document( "_id", 2 ) ) ) ).verifyComplete();
			StepVerifier.create( store.floor( "query", 2, 40 ) ).expectNext( Optional.empty() ).verifyComplete();
			StepVerifier.create( store.put( "query", new CursorAnchor( 2, new Document( "_id", 2 ) ) ) ).verifyComplete();

			StepVerifier.create( store.floor( "query", 2, 40 ) )
				.assertNext( anchor -> assertTrue( anchor.isPresent() ) )
				.verifyComplete();

		}

	}

	// 동일하거나 더 오래된 Change Stream clusterTime의 namespace invalidation은 version을 중복 증가시키지 않는지 검증한다.
	@Test
	void namespaceInvalidationWithClusterTimeIsIdempotentForDuplicateOrOlderEvents() {

		try (InMemoryCursorAnchorStore store = new InMemoryCursorAnchorStore()) {
			String namespace = "scope:db:collection";
			StepVerifier.create( store.invalidateNamespace( namespace, new BsonTimestamp( 100, 2 ) ) ).verifyComplete();
			StepVerifier.create( store.invalidateNamespace( namespace, new BsonTimestamp( 100, 2 ) ) ).verifyComplete();
			StepVerifier.create( store.invalidateNamespace( namespace, new BsonTimestamp( 100, 1 ) ) ).verifyComplete();
			StepVerifier.create( store.namespaceVersion( namespace ) ).expectNext( 1L ).verifyComplete();
			StepVerifier.create( store.invalidateNamespace( namespace, new BsonTimestamp( 100, 3 ) ) ).verifyComplete();
			StepVerifier.create( store.namespaceVersion( namespace ) ).expectNext( 2L ).verifyComplete();

		}

	}

	// opaque cursor token이 store에 query/pageSize/sort 위치와 함께 저장되고 다시 동일한 상태로 조회되는지 검증한다.
	@Test
	void opaqueCursorTokenStateRoundTripsThroughInMemoryStore() {

		try (InMemoryCursorAnchorStore store = new InMemoryCursorAnchorStore()) {
			Document values = new Document( "rank", 20 ).append( "_id", 30 );
			CursorTokenState state = new CursorTokenState( "query-key", 20, values );
			String token = CursorPaginationSupport.tokenId( state.queryKey(), state.pageSize(), state.sortValues() );

			StepVerifier.create( store.putToken( token, state, Duration.ofMinutes( 1 ) ) ).verifyComplete();
			StepVerifier.create( store.resolveToken( token ) )
				.assertNext( resolved -> {
					assertTrue( resolved.isPresent() );
					assertEquals( state.queryKey(), resolved.orElseThrow().queryKey() );
					assertEquals( state.pageSize(), resolved.orElseThrow().pageSize() );
					assertEquals( state.sortValues(), resolved.orElseThrow().sortValues() );

				} )
				.verifyComplete();

		}

	}

	// opaque cursor는 라이브러리가 발급한 고정 길이 hex token 형식만 허용해 비정상적으로 큰 client 입력이 store 조회로 전달되지 않는지 검증한다.
	@Test
	void opaqueCursorTokenFormatRejectsMalformedClientValues() {

		String token = CursorPaginationSupport.tokenId( "query-key", 20, new Document( "_id", 1 ) );
		assertTrue( CursorPaginationSupport.isTokenId( token ) );
		assertFalse( CursorPaginationSupport.isTokenId( "invalid" ) );
		assertFalse( CursorPaginationSupport.isTokenId( "a".repeat( 65 ) ) );
		assertFalse( CursorPaginationSupport.isTokenId( "A".repeat( 64 ) ) );

	}

	// 기존 8개 인자 CursorCacheOptions 생성자를 사용해도 신규 cursor 안전 기본값이 함께 적용되는지 검증한다.
	@Test
	void legacyCursorCacheOptionsConstructorKeepsSafetyDefaults() {

		CursorCacheOptions options = new CursorCacheOptions(
			Duration.ofSeconds( 10 ),
			4,
			Duration.ofMinutes( 1 ),
			100,
			16,
			5_000L,
			Duration.ofSeconds( 1 ),
			64
		);

		assertEquals( 5_000L, options.maxRelativeSkip() );
		assertEquals( CursorSkipExceededAction.FAIL, options.skipExceededAction() );
		assertEquals( 500, options.maxPageSize() );
		assertEquals( Duration.ofMinutes( 10 ), options.tokenTtl() );

	}

	// CursorCacheOptions에서 page-number cursor의 기본 초과 동작을 전역으로 변경할 수 있는지 검증한다.
	@Test
	void cursorCacheOptionsCanOverrideSkipExceededAction() {

		CursorCacheOptions options = CursorCacheOptions
			.defaults()
			.withCursorSkipPolicy( 12_345L, CursorSkipExceededAction.RETURN_EMPTY );

		assertEquals( 12_345L, options.maxRelativeSkip() );
		assertEquals( CursorSkipExceededAction.RETURN_EMPTY, options.skipExceededAction() );

	}

	// namespace invalidation이 개별 query anchor를 전수 scan하지 않고 namespace version만 증가시키는지 검증한다.
	@Test
	void namespaceInvalidationAdvancesVersionWithoutScanningQueryEntries() {

		try (InMemoryCursorAnchorStore store = new InMemoryCursorAnchorStore()) {
			StepVerifier.create( store.namespaceVersion( "scope:db:collection" ) )
				.expectNext( 0L )
				.verifyComplete();

			StepVerifier.create( store.invalidateNamespace( "scope:db:collection" ) )
				.verifyComplete();

			StepVerifier.create( store.namespaceVersion( "scope:db:collection" ) )
				.expectNext( 1L )
				.verifyComplete();

		}

	}

	// paging 전략별 typed builder가 IDE 자동완성에서 서로 다른 API만 노출하고 기존 일반 pageNumber/pageSize builder는 그대로 유지되는지 검증한다.
	@Test
	void pagingStrategiesExposeOnlyTheirRelevantCursorApi() {

		Set<String> findAllMethods = methodNames( ReactiveMongoDsl.AbstractQueryBuilder.FindAllQueryBuilder.class );
		assertFalse( findAllMethods.contains( "executePageCursor" ) );
		assertFalse( findAllMethods.contains( "executeCursorPage" ) );
		assertFalse( findAllMethods.contains( "cursorSkipPolicy" ) );
		assertFalse( findAllMethods.contains( "executeLookupCursor" ) );

		Set<String> pageBuilderMethods = methodNames( ReactiveMongoDsl.AbstractQueryBuilder.FindAllQueryBuilder.PageBuilder.class );
		assertTrue( pageBuilderMethods.contains( "pageNumber" ) );
		assertTrue( pageBuilderMethods.contains( "pageSize" ) );
		assertTrue( pageBuilderMethods.contains( "and" ) );
		assertTrue( pageBuilderMethods.contains( "pageNumberCursor" ) );
		assertTrue( pageBuilderMethods.contains( "cursor" ) );
		assertFalse( pageBuilderMethods.contains( "offset" ) );

		Set<String> pageNumberCursorMethods = methodNames( ReactiveMongoDsl.AbstractQueryBuilder.FindAllQueryBuilder.PageNumberCursorPagingBuilder.class );
		assertTrue( pageNumberCursorMethods.contains( "pageNumber" ) );
		assertTrue( pageNumberCursorMethods.contains( "pageSize" ) );
		assertTrue( pageNumberCursorMethods.contains( "skipPolicy" ) );
		assertTrue( pageNumberCursorMethods.contains( "execute" ) );
		assertFalse( pageNumberCursorMethods.contains( "after" ) );

		Set<String> cursorMethods = methodNames( ReactiveMongoDsl.AbstractQueryBuilder.FindAllQueryBuilder.CursorPagingBuilder.class );
		assertTrue( cursorMethods.contains( "pageSize" ) );
		assertTrue( cursorMethods.contains( "after" ) );
		assertTrue( cursorMethods.contains( "execute" ) );
		assertFalse( cursorMethods.contains( "pageNumber" ) );
		assertFalse( cursorMethods.contains( "skipPolicy" ) );

	}

	private Set<String> methodNames(
		Class<?> type
	) {

		return java.util.Arrays
			.stream( type.getDeclaredMethods() )
			.map( java.lang.reflect.Method::getName )
			.collect( Collectors.toSet() );

	}

}
