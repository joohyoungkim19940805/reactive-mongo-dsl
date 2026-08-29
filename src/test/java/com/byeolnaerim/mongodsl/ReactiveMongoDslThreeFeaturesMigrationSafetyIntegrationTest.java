package com.byeolnaerim.mongodsl;


import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.paging.CursorCacheOptions;
import com.byeolnaerim.mongodsl.paging.CursorSkipExceededAction;
import com.byeolnaerim.mongodsl.paging.CursorSkipLimitExceededException;
import com.byeolnaerim.mongodsl.paging.CursorTokenState;
import com.byeolnaerim.mongodsl.result.CursorPage;
import com.byeolnaerim.mongodsl.result.PageResult;
import com.byeolnaerim.mongodsl.result.ResultTuple;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.byeolnaerim.mongodsl.state.InMemoryReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.state.MongoReactiveMongoDslStateStore;
import com.byeolnaerim.mongodsl.state.MongoReactiveMongoDslStateStoreOptions;
import com.byeolnaerim.mongodsl.support.CursorInvalidationDiagnostics;
import com.byeolnaerim.mongodsl.support.DiagnosticReactiveMongoDslStateStore;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


// 실제 MongoDB change stream/atomic update/cursor invalidation/reservation까지 새 기능 세 가지를 끝까지 검증한다.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactiveMongoDslThreeFeaturesMigrationSafetyIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds( 15 );

	private static final String PARENT = "sync_parent";

	private static final String CHILD = "sync_child";

	private static final String LEAF = "sync_leaf";

	private static final String PROFILE = "sync_profile";

	private static final String TAG = "sync_tag";

	private static final String CURSOR = "cursor_live";

	private static final String LOOKUP_LEFT = "cursor_lookup_left";

	private static final String LOOKUP_RIGHT = "cursor_lookup_right";

	private static final String UNIFIED_PARENT = "unified_state_parent";

	private static final String UNIFIED_CHILD = "unified_state_child";

	private static final String UNIFIED_STATE = "__reactive_mongo_dsl_state_test";

	private static final List<String> COLLECTIONS = List.of( PARENT, CHILD, LEAF, PROFILE, TAG, CURSOR, LOOKUP_LEFT, LOOKUP_RIGHT, UNIFIED_PARENT, UNIFIED_CHILD, UNIFIED_STATE );

	private MongoClient mongoClient;

	private MongoDatabase mongoDatabase;

	private DriverMongoExecutionContext context;

	private ReactiveMongoDsl<TestMongo> mongoDsl;

	private ReactiveMongoDsl<TestMongo> cursorDsl;

	private InMemoryReactiveMongoDslStateStore cursorStore;

	private DiagnosticReactiveMongoDslStateStore cursorDiagnosticStore;

	private CursorInvalidationDiagnostics<TestMongo> cursorInvalidationDiagnostics;

	private String distributedScopeKey;

	@BeforeAll
	void connect() {

		TestEnvironment environment = TestEnvironment.load();

		if (! environment.isComplete()) {
			if (Boolean.getBoolean( "mongo.integration.required" ))
				throw new IllegalStateException( "TEST_CLUSTER_NAME, TEST_USERNAME, TEST_PASSWORD, and TEST_URL are required for mongoMigrationTest" );
			org.junit.jupiter.api.Assumptions.assumeTrue( false, "MongoDB TEST_* environment variables are not configured" );

		}

		mongoClient = MongoClients.create( environment.connectionString() );
		mongoDatabase = mongoClient.getDatabase( environment.databaseName() );
		distributedScopeKey = "three-features:" + mongoDatabase.getName();
		context = new DriverMongoExecutionContext(
			mongoClient,
			mongoDatabase,
			this::collectionName,
			entity -> entity instanceof Document document ? document.get( "_id" ) : null,
			distributedScopeKey
		);

		Flux
			.fromIterable( COLLECTIONS )
			.concatMap( collection -> Mono.from( mongoDatabase.createCollection( collection ) ) )
			.then()
			.block( TIMEOUT );

		EmbeddedSyncConfig<TestMongo> embeddedSync = new EmbeddedSyncConfig<TestMongo>()
			.forKeys( TestMongo.MAIN )
			.from( ChildEntity.class )
			.into( ParentEntity.class, "children" )
			.linkBy()
			.fromField( "parentId" )
			.intoField( "id" )
			.end()
			.build()
			.forKeys( TestMongo.MAIN )
			.from( LeafEntity.class )
			.into( ChildEntity.class, "leaf" )
			.linkBy()
			.fromField( "childId" )
			.intoField( "id" )
			.end()
			.build()
			.forKeys( TestMongo.MAIN )
			.from( ProfileEntity.class )
			.into( ParentEntity.class, "profile" )
			.linkBy()
			.fromField( "parentId" )
			.intoField( "id" )
			.end()
			.build()
			.forKeys( TestMongo.MAIN )
			.from( TagEntity.class )
			.into( ParentEntity.class, "tagsByCode" )
			.linkBy()
			.fromField( "parentId" )
			.intoField( "id" )
			.end()
			.mapKey( "code" )
			.build();

		mongoDsl = new ReactiveMongoDsl<>( ignored -> context, embeddedSync );
		mongoDsl.embeddedSyncInitialization().block( TIMEOUT );

		cursorStore = new InMemoryReactiveMongoDslStateStore(
			new CursorCacheOptions(
				Duration.ofSeconds( 10 ),
				1,
				Duration.ofMinutes( 1 ),
				1_000,
				64,
				0L,
				Duration.ofMillis( 20 ),
				128
			)
		);
		cursorDiagnosticStore = new DiagnosticReactiveMongoDslStateStore( cursorStore );
		cursorDsl = new ReactiveMongoDsl<>( ignored -> context, cursorDiagnosticStore );
		cursorInvalidationDiagnostics = new CursorInvalidationDiagnostics<>( mongoDatabase, cursorDsl, TestMongo.MAIN, cursorDiagnosticStore, TIMEOUT );
		Mono.delay( Duration.ofMillis( 500 ) ).block();

	}

	@BeforeEach
	void clean() {

		Flux
			.fromIterable( COLLECTIONS )
			.concatMap( collection -> Mono.from( mongoDatabase.getCollection( collection ).deleteMany( new Document() ) ) )
			.then()
			.block( TIMEOUT );
		Mono.delay( Duration.ofMillis( 200 ) ).block();

	}

	@AfterAll
	void disconnect() {

		if (cursorDsl != null)
			cursorDsl.close();
		if (mongoDsl != null)
			mongoDsl.close();
		if (mongoDatabase != null)
			Mono.from( mongoDatabase.drop() ).onErrorResume( ignored -> Mono.empty() ).block( TIMEOUT );
		if (mongoClient != null)
			mongoClient.close();

	}

	// 실제 MongoDB Change Stream으로 collection/map/single embedded 관계의 추가·이동·삭제와 multi-hop 동기화가 최종 상태로
	// 수렴하는지 검증한다.
	@Test
	void embeddedSynchronizationConvergesAcrossCollectionMapSingleMoveDeleteAndMultiHopRelations() {

		ObjectId parent1 = new ObjectId();
		ObjectId parent2 = new ObjectId();
		ObjectId child1 = new ObjectId();
		ObjectId child2 = new ObjectId();
		ObjectId child3 = new ObjectId();
		MongoCollection<Document> parents = collection( PARENT );
		MongoCollection<Document> children = collection( CHILD );
		MongoCollection<Document> leaves = collection( LEAF );
		MongoCollection<Document> profiles = collection( PROFILE );
		MongoCollection<Document> tags = collection( TAG );

		Mono
			.from(
				parents
					.insertMany(
						List
							.of(
								new Document( "_id", parent1 ).append( "name", "p1" ).append( "children", new ArrayList<>() ),
								new Document( "_id", parent2 ).append( "name", "p2" ).append( "children", new ArrayList<>() )
							)
					)
			)
			.block( TIMEOUT );

		mongoDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, CHILD )
			.saveAllBulk(
				List
					.of(
						new Document( "_id", child1 ).append( "parentId", parent1 ).append( "name", "c1" ),
						new Document( "_id", child2 ).append( "parentId", parent1 ).append( "name", "c2" ),
						new Document( "_id", child3 ).append( "parentId", parent1 ).append( "name", "c3" )
					)
			)
			.collectList()
			.block( TIMEOUT );

		Document p1 = awaitDocument( PARENT, parent1, document -> embeddedList( document, "children" ).size() == 3 );
		assertEquals( 3, embeddedList( p1, "children" ).size() );

		Mono.from( children.updateOne( Filters.eq( "_id", child1 ), Updates.set( "name", "c1-updated" ) ) ).block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> {
			List<Document> values = embeddedList( document, "children" );
			return values.size() == 3 && values.stream().anyMatch( child -> child1.equals( child.getObjectId( "_id" ) ) && "c1-updated".equals( child.getString( "name" ) ) );

		} );
		assertEquals( 3, embeddedList( p1, "children" ).size(), "one child update must not collapse the sibling array" );

		ObjectId leafId = new ObjectId();
		Mono.from( leaves.insertOne( new Document( "_id", leafId ).append( "childId", child1 ).append( "value", "leaf-v1" ) ) ).block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> embeddedList( document, "children" ).stream().anyMatch( child -> {
			Document leaf = child.get( "leaf", Document.class );
			return child1.equals( child.getObjectId( "_id" ) ) && leaf != null && "leaf-v1".equals( leaf.getString( "value" ) );

		} ) );
		assertNotNull( p1 );

		Mono.from( children.updateOne( Filters.eq( "_id", child1 ), Updates.set( "parentId", parent2 ) ) ).block( TIMEOUT );
		awaitDocument( PARENT, parent1, document -> embeddedList( document, "children" ).stream().noneMatch( child -> child1.equals( child.getObjectId( "_id" ) ) ) );
		Document p2 = awaitDocument( PARENT, parent2, document -> embeddedList( document, "children" ).stream().anyMatch( child -> child1.equals( child.getObjectId( "_id" ) ) ) );
		assertEquals( 1, embeddedList( p2, "children" ).size() );

		Mono.from( children.deleteOne( Filters.eq( "_id", child1 ) ) ).block( TIMEOUT );
		p2 = awaitDocument( PARENT, parent2, document -> embeddedList( document, "children" ).isEmpty() );
		assertTrue( embeddedList( p2, "children" ).isEmpty() );

		ObjectId profileId = new ObjectId();
		Mono.from( profiles.insertOne( new Document( "_id", profileId ).append( "parentId", parent1 ).append( "label", "profile-v1" ) ) ).block( TIMEOUT );
		awaitDocument( PARENT, parent1, document -> document.get( "profile", Document.class ) != null );
		Mono.from( profiles.updateOne( Filters.eq( "_id", profileId ), Updates.set( "label", "profile-v2" ) ) ).block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> {
			Document profile = document.get( "profile", Document.class );
			return profile != null && "profile-v2".equals( profile.getString( "label" ) );

		} );
		assertEquals( "profile-v2", p1.get( "profile", Document.class ).getString( "label" ) );
		Mono.from( profiles.deleteOne( Filters.eq( "_id", profileId ) ) ).block( TIMEOUT );
		awaitDocument( PARENT, parent1, document -> ! document.containsKey( "profile" ) );

		ObjectId tag1 = new ObjectId();
		ObjectId tag2 = new ObjectId();
		Mono
			.from(
				tags
					.insertMany(
						List
							.of(
								new Document( "_id", tag1 ).append( "parentId", parent1 ).append( "code", "one" ).append( "value", 1 ),
								new Document( "_id", tag2 ).append( "parentId", parent1 ).append( "code", "two" ).append( "value", 2 )
							)
					)
			)
			.block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> {
			Document map = document.get( "tagsByCode", Document.class );
			return map != null && map.size() == 2 && map.containsKey( "one" ) && map.containsKey( "two" );

		} );
		assertEquals( 2, p1.get( "tagsByCode", Document.class ).size() );

		Mono.from( tags.updateOne( Filters.eq( "_id", tag1 ), Updates.combine( Updates.set( "code", "uno" ), Updates.set( "value", 10 ) ) ) ).block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> {
			Document map = document.get( "tagsByCode", Document.class );
			return map != null && ! map.containsKey( "one" ) && map.containsKey( "uno" ) && map.containsKey( "two" );

		} );
		assertEquals( 10, p1.get( "tagsByCode", Document.class ).get( "uno", Document.class ).getInteger( "value" ) );

		Mono.from( tags.deleteOne( Filters.eq( "_id", tag2 ) ) ).block( TIMEOUT );
		p1 = awaitDocument( PARENT, parent1, document -> {
			Document map = document.get( "tagsByCode", Document.class );
			return map != null && map.size() == 1 && map.containsKey( "uno" );

		} );
		assertEquals( 1, p1.get( "tagsByCode", Document.class ).size() );

	}

	// pageNumber 없이 store-backed opaque token만으로 다음 keyset page를 이동하고 각 요청이 skip 없이 bounded limit으로 이어지는지 검증한다.
	@Test
	void opaqueCursorTokenPagesWithoutPageNumber() {

		List<Document> fixtures = new ArrayList<>();
		for (int i = 0; i < 12; i++)
			fixtures.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( fixtures ) ).block( TIMEOUT );

		var builder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, CURSOR )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) );

		CursorPage<Document> first = builder.paging().cursor( 5 ).execute().block( TIMEOUT );
		assertNotNull( first );
		assertEquals( List.of( 0, 1, 2, 3, 4 ), first.data().stream().map( row -> row.getInteger( "rank" ) ).toList() );
		assertTrue( first.hasNext() );

		CursorPage<Document> second = builder.paging().cursor( 5 ).after( first.nextCursor() ).execute().block( TIMEOUT );
		assertNotNull( second );
		assertEquals( List.of( 5, 6, 7, 8, 9 ), second.data().stream().map( row -> row.getInteger( "rank" ) ).toList() );
		assertTrue( second.hasNext() );

		CursorPage<Document> third = builder.paging().cursor( 5 ).after( second.nextCursor() ).execute().block( TIMEOUT );
		assertNotNull( third );
		assertEquals( List.of( 10, 11 ), third.data().stream().map( row -> row.getInteger( "rank" ) ).toList() );
		assertFalse( third.hasNext() );

	}

	// 순수 keyset token은 page-number anchor처럼 namespace version에 묶이지 않아 앞쪽 데이터가 변경되어도 기존 sort 위치부터 현재 데이터 기준으로 계속 진행하는지 검증한다.
	@Test
	void opaqueCursorTokenRemainsUsableAcrossCollectionWrites() {

		List<Document> fixtures = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			fixtures.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( fixtures ) ).block( TIMEOUT );

		var builder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, CURSOR )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) );

		CursorPage<Document> first = builder.paging().cursor( 3 ).execute().block( TIMEOUT );
		assertNotNull( first );
		assertEquals( List.of( 0, 1, 2 ), first.data().stream().map( row -> row.getInteger( "rank" ) ).toList() );
		assertTrue( first.hasNext() );

		Mono.from( collection( CURSOR ).insertOne( new Document( "_id", new ObjectId() ).append( "rank", -1 ) ) ).block( TIMEOUT );

		CursorPage<Document> second = builder.paging().cursor( 3 ).after( first.nextCursor() ).execute().block( TIMEOUT );
		assertNotNull( second );
		assertEquals( List.of( 3, 4, 5 ), second.data().stream().map( row -> row.getInteger( "rank" ) ).toList() );

	}

	// page-number cursor에 가까운 anchor가 없을 때 설정된 최대 상대 skip을 넘는 deep page 요청을 business DB 실행 전에 거부하는지 검증한다.
	@Test
	void pageNumberCursorRejectsUnboundedDeepSkipWithoutNearbyAnchor() {

		CursorSkipLimitExceededException error = assertThrows(
			CursorSkipLimitExceededException.class,
			() -> cursorDsl
				.executeCustomClass( Document.class, TestMongo.MAIN, CURSOR )
				.fields()
				.end()
				.findAll()
				.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
				.paging()
				.pageNumberCursor( 99_999, 20 )
				.execute()
				.collectList()
				.block( TIMEOUT )
		);

		assertTrue( error.getMessage().contains( "maxRelativeSkip" ) );
		assertEquals( 99_999, error.targetPageNumber() );
		assertEquals( 0, error.anchorPageNumber() );
		assertEquals( 20, error.pageSize() );

	}

	// page-number cursor의 query별 skipPolicy가 한도를 넘은 page-number 요청을 business DB query 없이 빈 결과로 종료할 수 있는지 검증한다.
	@Test
	void pageNumberCursorCanReturnEmptyWhenRelativeSkipLimitIsExceeded() {

		List<Document> rows = new ArrayList<>();
		for (int i = 0; i < 25; i++)
			rows.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( rows ) ).block( TIMEOUT );

		List<Document> result = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, CURSOR )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging()
			.pageNumberCursor( 1, 20 )
			.skipPolicy()
			.maxRelativeSkip( 0L )
			.onExceeded( CursorSkipExceededAction.RETURN_EMPTY )
			.end()
			.execute()
			.collectList()
			.block( TIMEOUT );

		assertNotNull( result );
		assertTrue( result.isEmpty() );

	}

	// page-number cursor의 query별 skipPolicy가 한도를 넘더라도 EXECUTE_ANYWAY를 선택하면 기존 offset 의미로 결과를 계속 조회하는지 검증한다.
	@Test
	void pageNumberCursorCanExecuteAnywayWhenRelativeSkipLimitIsExceeded() {

		List<Document> rows = new ArrayList<>();
		for (int i = 0; i < 25; i++)
			rows.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( rows ) ).block( TIMEOUT );

		List<Document> result = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, CURSOR )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging()
			.pageNumberCursor( 1, 20 )
			.skipPolicy()
			.maxRelativeSkip( 0L )
			.onExceeded( CursorSkipExceededAction.EXECUTE_ANYWAY )
			.end()
			.execute()
			.collectList()
			.block( TIMEOUT );

		assertNotNull( result );
		assertEquals( List.of( 20, 21, 22, 23, 24 ), result.stream().map( row -> row.getInteger( "rank" ) ).toList() );

	}

	// lookup page-number cursor도 left page-number cursor builder의 skipPolicy를 따라 한도 초과 시 business aggregation을 빈 결과로 종료하는지 검증한다.
	@Test
	void lookupPageNumberCursorUsesTheSamePerQuerySkipPolicy() {

		List<Document> left = new ArrayList<>();
		List<Document> right = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			left.add( new Document( "_id", new ObjectId() ).append( "rank", i ).append( "joinKey", "policy-" + i ) );
			right.add( new Document( "_id", new ObjectId() ).append( "joinKey", "policy-" + i ).append( "enabled", true ) );

		}
		Mono.from( collection( LOOKUP_LEFT ).insertMany( left ) ).block( TIMEOUT );
		Mono.from( collection( LOOKUP_RIGHT ).insertMany( right ) ).block( TIMEOUT );

		var leftBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging()
			.pageNumberCursor( 1, 20 )
			.skipPolicy()
			.maxRelativeSkip( 0L )
			.onExceeded( CursorSkipExceededAction.RETURN_EMPTY )
			.end();

		var rightBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_RIGHT )
			.fields( pair( "enabled", true ) )
			.end()
			.findAll();

		LookupSpec spec = LookupSpec.builder().localField( "joinKey" ).foreignField( "joinKey" ).build();
		List<ResultTuple<Document, List<Document>>> result = leftBuilder
			.executeLookup( rightBuilder, spec )
			.collectList()
			.block( TIMEOUT );

		assertNotNull( result );
		assertTrue( result.isEmpty() );

	}

	// lookup도 pageNumber 없이 opaque token으로 다음 left keyset을 이동하면서 joined 결과를 같은 순서로 유지하는지 검증한다.
	@Test
	void lookupOpaqueCursorTokenPagesWithoutPageNumber() {

		List<Document> left = new ArrayList<>();
		List<Document> right = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			left.add( new Document( "_id", new ObjectId() ).append( "rank", i ).append( "joinKey", "token-" + i ) );
			right.add( new Document( "_id", new ObjectId() ).append( "joinKey", "token-" + i ).append( "enabled", true ) );

		}
		Mono.from( collection( LOOKUP_LEFT ).insertMany( left ) ).block( TIMEOUT );
		Mono.from( collection( LOOKUP_RIGHT ).insertMany( right ) ).block( TIMEOUT );

		var leftBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) );
		var rightBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_RIGHT )
			.fields( pair( "enabled", true ) )
			.end()
			.findAll();
		LookupSpec spec = LookupSpec
			.builder()
			.as( "joined" )
			.bindConditionFields( "joinKey", Condition.eq, "joinKey" )
			.build();

		CursorPage<ResultTuple<Document, List<Document>>> first = leftBuilder.paging().cursor( 5 ).executeLookup( rightBuilder, spec ).block( TIMEOUT );
		assertNotNull( first );
		assertEquals( List.of( 0, 1, 2, 3, 4 ), first.data().stream().map( tuple -> tuple.getLeft().getInteger( "rank" ) ).toList() );
		assertTrue( first.hasNext() );

		CursorPage<ResultTuple<Document, List<Document>>> second = leftBuilder.paging().cursor( 5 ).after( first.nextCursor() ).executeLookup( rightBuilder, spec ).block( TIMEOUT );
		assertNotNull( second );
		assertEquals( List.of( 5, 6, 7, 8, 9 ), second.data().stream().map( tuple -> tuple.getLeft().getInteger( "rank" ) ).toList() );

	}

	// 외부 MongoDB 변경을 shared Change Stream이 감지해 기존 cursor page anchor를 무효화하고 stale page 반환을 방지하는지 검증한다.
	@Test
	void cursorPageAnchorIsInvalidatedBySharedChangeStreamBeforeItCanReturnAStalePage() {

		List<Document> fixtures = new ArrayList<>();
		for (int i = 0; i < 100; i++)
			fixtures.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( fixtures ) ).block( TIMEOUT );

		List<Integer> first = cursorPage( 5, 10 );
		assertEquals( List.of( 50, 51, 52, 53, 54, 55, 56, 57, 58, 59 ), first );

		String namespaceKey = distributedScopeKey + ":" + mongoDatabase.getName() + ":" + CURSOR;
		long versionBefore = cursorStore.namespaceVersion( namespaceKey ).block( TIMEOUT );
		ObjectId insertedId = new ObjectId();
		var probe = cursorInvalidationDiagnostics.begin( CURSOR, insertedId, namespaceKey, versionBefore );
		Mono.from( collection( CURSOR ).insertOne( new Document( "_id", insertedId ).append( "rank", -1 ) ) ).block( TIMEOUT );
		cursorInvalidationDiagnostics.await( probe );

		List<Integer> second = cursorPage( 5, 10 );
		assertEquals( List.of( 49, 50, 51, 52, 53, 54, 55, 56, 57, 58 ), second );

	}


	// lookup cursor가 의존하는 joined collection 변경 시 namespace version과 anchor가 무효화되어 변경된 left 결과 페이지를 다시
	// 계산하는지 검증한다.
	@Test
	void lookupCursorInvalidatesItsAnchorWhenAJoinedCollectionChangesTheFilteredLeftResultSet() {

		List<Document> left = new ArrayList<>();
		List<Document> right = new ArrayList<>();

		for (int i = 0; i < 30; i++) {
			left.add( new Document( "_id", new ObjectId() ).append( "rank", i ).append( "joinKey", "join-" + i ) );
			if (i > 0)
				right.add( new Document( "_id", new ObjectId() ).append( "joinKey", "join-" + i ).append( "enabled", true ) );

		}

		Mono.from( collection( LOOKUP_LEFT ).insertMany( left ) ).block( TIMEOUT );
		Mono.from( collection( LOOKUP_RIGHT ).insertMany( right ) ).block( TIMEOUT );

		PageResult<ResultTuple<Document, List<Document>>> first = lookupCursorPage( 2, 5 );
		assertEquals( 29L, first.getTotalCount() );
		assertEquals( List.of( 11, 12, 13, 14, 15 ), lookupRanks( first ) );

		String rightNamespaceKey = distributedScopeKey + ":" + mongoDatabase.getName() + ":" + LOOKUP_RIGHT;
		long versionBefore = cursorStore.namespaceVersion( rightNamespaceKey ).block( TIMEOUT );
		ObjectId insertedRightId = new ObjectId();
		var probe = cursorInvalidationDiagnostics.begin( LOOKUP_RIGHT, insertedRightId, rightNamespaceKey, versionBefore );
		Mono
			.from(
				collection( LOOKUP_RIGHT )
					.insertOne(
						new Document( "_id", insertedRightId ).append( "joinKey", "join-0" ).append( "enabled", true )
					)
			)
			.block( TIMEOUT );
		cursorInvalidationDiagnostics.await( probe );

		PageResult<ResultTuple<Document, List<Document>>> second = lookupCursorPage( 2, 5 );
		assertEquals( 30L, second.getTotalCount() );
		assertEquals( List.of( 10, 11, 12, 13, 14 ), lookupRanks( second ) );

	}


	// 다른 collection의 대량 Change Stream 이벤트가 쌓여도 대상 collection의 cursor invalidation이 backlog 뒤에서 장시간 굶지
	// 않는지 검증한다.
	@Test
	void cursorInvalidationDoesNotStarveBehindBulkEventsFromAnotherCollection() {

		List<Document> noisy = new ArrayList<>();
		for (int i = 0; i < 500; i++)
			noisy.add( new Document( "_id", new ObjectId() ).append( "rank", i ) );
		Mono.from( collection( CURSOR ).insertMany( noisy ) ).block( TIMEOUT );
		cursorPage( 0, 10 );

		Mono
			.from(
				collection( LOOKUP_LEFT )
					.insertOne(
						new Document( "_id", new ObjectId() ).append( "rank", 0 ).append( "joinKey", "late-target" )
					)
			)
			.block( TIMEOUT );
		PageResult<ResultTuple<Document, List<Document>>> empty = lookupCursorPage( 0, 5 );
		assertEquals( 0L, empty.getTotalCount() );

		String rightNamespaceKey = distributedScopeKey + ":" + mongoDatabase.getName() + ":" + LOOKUP_RIGHT;
		long versionBefore = cursorStore.namespaceVersion( rightNamespaceKey ).block( TIMEOUT );
		Mono.from( collection( CURSOR ).deleteMany( new Document() ) ).block( TIMEOUT );

		ObjectId insertedRightId = new ObjectId();
		var probe = cursorInvalidationDiagnostics.begin( LOOKUP_RIGHT, insertedRightId, rightNamespaceKey, versionBefore );
		Mono
			.from(
				collection( LOOKUP_RIGHT )
					.insertOne(
						new Document( "_id", insertedRightId ).append( "joinKey", "late-target" ).append( "enabled", true )
					)
			)
			.block( TIMEOUT );
		cursorInvalidationDiagnostics.await( probe );

		PageResult<ResultTuple<Document, List<Document>>> refreshed = lookupCursorPage( 0, 5 );
		assertEquals( 1L, refreshed.getTotalCount() );
		assertEquals( List.of( 0 ), lookupRanks( refreshed ) );

	}

	// lookup 좌우 builder가 모두 Document.class여도 내부 projection alias 충돌 없이 left/right 결과를 분리해 매핑하는지 검증한다.
	@Test
	void lookupProjectionKeepsLeftAndRightSeparatedWhenBothBuildersUseDocumentClass() {

		Mono
			.from(
				collection( LOOKUP_LEFT )
					.insertOne(
						new Document( "_id", new ObjectId() ).append( "rank", 7 ).append( "joinKey", "same-type" )
					)
			)
			.block( TIMEOUT );
		Mono
			.from(
				collection( LOOKUP_RIGHT )
					.insertOne(
						new Document( "_id", new ObjectId() ).append( "joinKey", "same-type" ).append( "enabled", true )
					)
			)
			.block( TIMEOUT );

		LookupSpec lookupSpec = LookupSpec
			.builder()
			.as( "joined" )
			.bindConditionFields( "joinKey", Condition.eq, "joinKey" )
			.build();

		var rightAllBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_RIGHT )
			.fields( pair( "enabled", true ) )
			.end()
			.findAll();

		List<ResultTuple<Document, List<Document>>> rows = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.executeLookup( rightAllBuilder, lookupSpec )
			.collectList()
			.block( TIMEOUT );

		assertNotNull( rows );
		assertEquals( 1, rows.size() );
		assertEquals( 7, rows.get( 0 ).getLeft().getInteger( "rank" ) );
		assertEquals( 1, rows.get( 0 ).getRight().size() );
		assertEquals( "same-type", rows.get( 0 ).getRight().get( 0 ).getString( "joinKey" ) );

		PageResult<ResultTuple<Document, List<Document>>> page = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging( 0, 10 )
			.executeLookupAndCount( rightAllBuilder, lookupSpec )
			.block( TIMEOUT );

		assertNotNull( page );
		assertEquals( 1L, page.getTotalCount() );
		assertEquals( 7, page.getData().get( 0 ).getLeft().getInteger( "rank" ) );
		assertEquals( 1, page.getData().get( 0 ).getRight().size() );

		var rightOneBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_RIGHT )
			.fields( pair( "enabled", true ) )
			.end()
			.find();

		ResultTuple<Document, Document> single = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields( pair( "rank", 7 ) )
			.end()
			.find()
			.executeLookup( rightOneBuilder, lookupSpec )
			.block( TIMEOUT );

		assertNotNull( single );
		assertEquals( 7, single.getLeft().getInteger( "rank" ) );
		assertNotNull( single.getRight() );
		assertEquals( "same-type", single.getRight().getString( "joinKey" ) );

	}

	// reservation Change Stream이 최초 finite query snapshot을 내보낸 뒤 외부 Mongo write를 감지해 query를 재실행하고 새
	// snapshot을 전달하는지 검증한다.
	@Test
	void reservationChangeStreamReexecutesTheFiniteQueryAfterAnExternalMongoWrite() throws Exception {

		List<List<Document>> snapshots = new CopyOnWriteArrayList<>();
		CountDownLatch firstSnapshot = new CountDownLatch( 1 );
		CountDownLatch secondSnapshot = new CountDownLatch( 1 );
		var subscription = cursorDsl
			.executeEntity( Document.class, TestMongo.MAIN )
			.fields( pair( "kind", "reservation" ) )
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.reservationChangeStream()
			.coalesce( Duration.ofMillis( 20 ) )
			.execute()
			.take( 2 )
			.subscribe( snapshot -> {
				snapshots.add( snapshot );
				if (snapshots.size() == 1)
					firstSnapshot.countDown();
				else if (snapshots.size() == 2)
					secondSnapshot.countDown();

			} );

		try {
			assertTrue( firstSnapshot.await( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS ), "initial reservation snapshot was not emitted" );
			assertEquals( 1, snapshots.size() );
			assertTrue( snapshots.get( 0 ).isEmpty() );

			Mono
				.from(
					collection( CURSOR )
						.insertOne(
							new Document( "_id", new ObjectId() ).append( "kind", "reservation" ).append( "rank", 1 )
						)
				)
				.block( TIMEOUT );

			assertTrue( secondSnapshot.await( TIMEOUT.toMillis(), TimeUnit.MILLISECONDS ), "reservation query was not re-executed after the external write" );
			assertEquals( 2, snapshots.size() );
			assertEquals( 1, snapshots.get( 1 ).size() );
			assertEquals( "reservation", snapshots.get( 1 ).get( 0 ).getString( "kind" ) );

		} finally {
			subscription.dispose();

		}

	}

	// 동일 watched MongoDB의 단일 Mongo state store로 cursor/checkpoint/embedded lease를 함께 사용해도 내부 state
	// write가 Change Stream self-feedback을 만들지 않는지 검증한다.
	@Test
	void oneMongoStateStoreCanBackAllThreeFeaturesInTheSameWatchedDatabaseWithoutSelfFeedback() {

		CursorCacheOptions cursorOptions = new CursorCacheOptions(
			Duration.ofSeconds( 10 ),
			1,
			Duration.ofMinutes( 1 ),
			1_000,
			64,
			0L,
			Duration.ofMillis( 20 ),
			128
		);
		MongoReactiveMongoDslStateStoreOptions options = new MongoReactiveMongoDslStateStoreOptions(
			UNIFIED_STATE,
			cursorOptions,
			true
		);
		EmbeddedSyncConfig<TestMongo> embeddedSync = new EmbeddedSyncConfig<TestMongo>()
			.forKeys( TestMongo.MAIN )
			.from( UnifiedChildEntity.class )
			.into( UnifiedParentEntity.class, "children" )
			.linkBy()
			.fromField( "parentId" )
			.intoField( "id" )
			.end()
			.build();

		MongoReactiveMongoDslStateStore stateStore = new MongoReactiveMongoDslStateStore( context, options );

		try (ReactiveMongoDsl<TestMongo> dsl = new ReactiveMongoDsl<>( ignored -> context, embeddedSync, stateStore )) {
			dsl.embeddedSyncInitialization().block( TIMEOUT );
			List<String> observedCollections = new CopyOnWriteArrayList<>();
			var subscription = dsl.changeStreams().watch( TestMongo.MAIN ).subscribe( event -> {
				if (event.getNamespace() != null && event.getNamespace().getCollectionName() != null)
					observedCollections.add( event.getNamespace().getCollectionName() );

			} );

			try {
				Mono.delay( Duration.ofMillis( 250 ) ).block();

				assertTrue( stateStore.floor( "mongo-query", 5, 1_000L ).block( TIMEOUT ).isEmpty() );
				stateStore.put( "mongo-query", new com.byeolnaerim.mongodsl.paging.CursorAnchor( 4, new Document( "rank", 40 ) ) ).block( TIMEOUT );
				assertEquals( 4, stateStore.floor( "mongo-query", 5, 1_000L ).block( TIMEOUT ).orElseThrow().pageNumber() );

				CursorTokenState tokenState = new CursorTokenState( "mongo-query", 20, new Document( "rank", 40 ) );
				stateStore.putToken( "opaque-token", tokenState, Duration.ofMinutes( 1 ) ).block( TIMEOUT );
				CursorTokenState resolvedToken = stateStore.resolveToken( "opaque-token" ).block( TIMEOUT ).orElseThrow();
				assertEquals( tokenState.queryKey(), resolvedToken.queryKey() );
				assertEquals( tokenState.pageSize(), resolvedToken.pageSize() );
				assertEquals( tokenState.sortValues(), resolvedToken.sortValues() );

				assertEquals( 0L, stateStore.namespaceVersion( "manual:namespace" ).block( TIMEOUT ) );
				stateStore.invalidateNamespace( "manual:namespace" ).block( TIMEOUT );
				assertEquals( 1L, stateStore.namespaceVersion( "manual:namespace" ).block( TIMEOUT ) );
				stateStore.invalidateNamespace( "manual:namespace", new BsonTimestamp( 100, 2 ) ).block( TIMEOUT );
				stateStore.invalidateNamespace( "manual:namespace", new BsonTimestamp( 100, 2 ) ).block( TIMEOUT );
				stateStore.invalidateNamespace( "manual:namespace", new BsonTimestamp( 100, 1 ) ).block( TIMEOUT );
				assertEquals( 2L, stateStore.namespaceVersion( "manual:namespace" ).block( TIMEOUT ) );
				stateStore.invalidateNamespace( "manual:namespace", new BsonTimestamp( 100, 3 ) ).block( TIMEOUT );
				assertEquals( 3L, stateStore.namespaceVersion( "manual:namespace" ).block( TIMEOUT ) );

				assertTrue( stateStore.tryAcquire( "manual:lease", "node-a", Duration.ofSeconds( 2 ) ).block( TIMEOUT ) );
				assertTrue( stateStore.renew( "manual:lease", "node-a", Duration.ofSeconds( 2 ) ).block( TIMEOUT ) );
				stateStore.release( "manual:lease", "node-a" ).block( TIMEOUT );

				ObjectId parentId = new ObjectId();
				ObjectId childId = new ObjectId();
				Mono
					.from(
						collection( UNIFIED_PARENT )
							.insertOne(
								new Document( "_id", parentId ).append( "children", new ArrayList<>() )
							)
					)
					.block( TIMEOUT );
				Mono
					.from(
						collection( UNIFIED_CHILD )
							.insertOne(
								new Document( "_id", childId ).append( "parentId", parentId ).append( "name", "child" )
							)
					)
					.block( TIMEOUT );

				Document parent = awaitDocument( UNIFIED_PARENT, parentId, document -> embeddedList( document, "children" ).size() == 1 );
				assertEquals( childId, embeddedList( parent, "children" ).get( 0 ).getObjectId( "_id" ) );

				await(
					() -> stateStore
						.load(
							new com.byeolnaerim.mongodsl.change.ChangeStreamScope( context.getSessionScope(), mongoDatabase.getName(), distributedScopeKey )
						)
						.blockOptional( TIMEOUT )
						.isPresent()
				);
				Mono.delay( Duration.ofMillis( 300 ) ).block();

				assertTrue( observedCollections.contains( UNIFIED_CHILD ) );
				assertFalse( observedCollections.contains( UNIFIED_STATE ), "state-store writes must be excluded from the shared database Change Stream" );

			} finally {
				subscription.dispose();

			}

		}

	}


	private PageResult<ResultTuple<Document, List<Document>>> lookupCursorPage(
		int pageNumber, int pageSize
	) {

		var rightBuilder = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_RIGHT )
			.fields( pair( "enabled", true ) )
			.end()
			.findAll();

		PageResult<ResultTuple<Document, List<Document>>> page = cursorDsl
			.executeCustomClass( Document.class, TestMongo.MAIN, LOOKUP_LEFT )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging()
			.pageNumberCursor( pageNumber, pageSize )
			.executeLookupAndCount(
				rightBuilder,
				LookupSpec
					.builder()
					.as( "joined" )
					.bindConditionFields( "joinKey", Condition.eq, "joinKey" )
					.outerStage( Aggregates.match( Filters.exists( "joined.0", true ) ) )
					.build()
			)
			.block( TIMEOUT );

		assertNotNull( page );
		assertTrue( page.getData().stream().allMatch( tuple -> tuple.getRight().size() == 1 ) );
		return page;

	}

	private List<Integer> lookupRanks(
		PageResult<ResultTuple<Document, List<Document>>> page
	) {

		return page.getData().stream().map( tuple -> tuple.getLeft().getInteger( "rank" ) ).toList();

	}

	private List<Integer> cursorPage(
		int pageNumber, int pageSize
	) {

		return cursorDsl
			.executeEntity( Document.class, TestMongo.MAIN )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.driver( Sorts.ascending( "rank" ) ) )
			.paging()
			.pageNumberCursor( pageNumber, pageSize )
			.execute()
			.map( document -> document.getInteger( "rank" ) )
			.collectList()
			.block( TIMEOUT );

	}

	private MongoCollection<Document> collection(
		String collectionName
	) {

		return mongoDatabase.getCollection( collectionName );

	}

	private Document awaitDocument(
		String collectionName, ObjectId id, Predicate<Document> condition
	) {

		final Document[] result = new Document[1];
		await( () -> {
			Document current = Mono.from( collection( collectionName ).find( Filters.eq( "_id", id ) ).first() ).block( TIMEOUT );

			if (current != null && condition.test( current )) {
				result[0] = current;
				return true;

			}

			return false;

		} );
		return result[0];

	}

	private void await(
		java.util.function.BooleanSupplier condition
	) {

		long deadline = System.nanoTime() + TIMEOUT.toNanos();

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return;

			try {
				Thread.sleep( 50L );

			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new AssertionError( error );

			}

		}

		throw new AssertionError( "Timed out waiting for MongoDB change-stream convergence" );

	}

	@SuppressWarnings("unchecked")
	private static List<Document> embeddedList(
		Document document, String field
	) {

		List<Document> values = (List<Document>) document.get( field, List.class );
		return values == null ? List.of() : values;

	}

	private String collectionName(
		Class<?> type
	) {

		if (type == ParentEntity.class)
			return PARENT;
		if (type == ChildEntity.class)
			return CHILD;
		if (type == LeafEntity.class)
			return LEAF;
		if (type == ProfileEntity.class)
			return PROFILE;
		if (type == TagEntity.class)
			return TAG;
		if (type == UnifiedParentEntity.class)
			return UNIFIED_PARENT;
		if (type == UnifiedChildEntity.class)
			return UNIFIED_CHILD;
		if (type == Document.class)
			return CURSOR;
		throw new IllegalArgumentException( "Unknown integration-test entity: " + type.getName() );

	}

	private enum TestMongo {
		MAIN
	}

	private static final class ParentEntity {

		private ObjectId id;

		private List<ChildEntity> children;

		private ProfileEntity profile;

		private Map<String, TagEntity> tagsByCode;

	}

	private static final class ChildEntity {

		private ObjectId id;

		private ObjectId parentId;

		private LeafEntity leaf;

	}

	private static final class LeafEntity {

		private ObjectId id;

		private ObjectId childId;

	}

	private static final class ProfileEntity {

		private ObjectId id;

		private ObjectId parentId;

	}

	private static final class TagEntity {

		private ObjectId id;

		private ObjectId parentId;

		private String code;

	}

	private static final class UnifiedParentEntity {

		private ObjectId id;

		private List<UnifiedChildEntity> children;

	}

	private static final class UnifiedChildEntity {

		private ObjectId id;

		private ObjectId parentId;

	}

	private record TestEnvironment(String clusterName, String username, String password, String url) {

		static TestEnvironment load() {

			return new TestEnvironment(
				System.getenv( "TEST_CLUSTER_NAME" ),
				System.getenv( "TEST_USERNAME" ),
				System.getenv( "TEST_PASSWORD" ),
				System.getenv( "TEST_URL" )
			);

		}

		boolean isComplete() {

			return clusterName != null && ! clusterName.isBlank() && username != null && ! username.isBlank() && password != null && ! password.isBlank() && url != null && ! url.isBlank();

		}

		String connectionString() {

			String value = url.trim();
			if (value.startsWith( "mongodb://" ) || value.startsWith( "mongodb+srv://" ))
				return value;
			String suffix = value.startsWith( "@" ) ? value : "@" + value;
			return "mongodb+srv://" + encode( username ) + ":" + encode( password ) + suffix;

		}

		String databaseName() {

			String sanitized = clusterName.replaceAll( "[^A-Za-z0-9_]", "_" );
			if (sanitized.length() > 20)
				sanitized = sanitized.substring( 0, 20 );
			return "rmdsl_3f_" + sanitized + "_" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );

		}

		private static String encode(
			String value
		) {

			return URLEncoder.encode( value, StandardCharsets.UTF_8 ).replace( "+", "%20" );

		}

	}

}
