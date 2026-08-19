package com.byeolnaerim.mongodsl;

import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.result.ResultTuple;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 실제 MongoDB에 접속해 기존 DSL의 CRUD, bulk, backup/history, aggregation, lookup, transaction 동작을 회귀 검증한다.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactiveMongoDslMigrationSafetyIntegrationTest {

	private static final String ENTITY_COLLECTION = "production_like_entity";
	private static final String CHILD_COLLECTION = "production_like_child";
	private static final String DRIVER_COLLECTION = "driver_pojo";
	private static final List<String> COLLECTIONS = List.of(
		ENTITY_COLLECTION,
		ENTITY_COLLECTION + "_remove",
		ENTITY_COLLECTION + "_history",
		CHILD_COLLECTION,
		DRIVER_COLLECTION
	);

	private MongoClient mongoClient;
	private MongoDatabase mongoDatabase;
	private ReactiveMongoDsl<TestMongo> mongoDsl;
	private ProductionLikeMongoExecutionContext leftContext;
	private ProductionLikeMongoExecutionContext rightContext;
	private DriverMongoExecutionContext driverContext;
	private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

	// 실제 테스트 MongoDB에 연결하고 codec/context/DSL 및 테스트용 collection을 초기화한다.
	@BeforeAll
	void connect() {
		TestEnvironment environment = TestEnvironment.load();
		if (!environment.isComplete()) {
			if (Boolean.getBoolean("mongo.integration.required")) {
				throw new IllegalStateException(
					"TEST_CLUSTER_NAME, TEST_USERNAME, TEST_PASSWORD, and TEST_URL are required for mongoMigrationTest"
				);
			}
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "MongoDB TEST_* environment variables are not configured");
		}

		mongoClient = MongoClients.create(environment.connectionString());
		CodecRegistry codecRegistry = CodecRegistries.fromRegistries(
			MongoClientSettings.getDefaultCodecRegistry(),
			CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
		);
		mongoDatabase = mongoClient
			.getDatabase(environment.databaseName())
			.withCodecRegistry(codecRegistry);

		leftContext = ProductionLikeMongoExecutionContext.left(mongoClient, mongoDatabase);
		rightContext = ProductionLikeMongoExecutionContext.right(mongoClient, mongoDatabase);
		driverContext = new DriverMongoExecutionContext(
			mongoClient,
			mongoDatabase,
			type -> DRIVER_COLLECTION,
			entity -> {
				if (entity instanceof DriverPojo pojo)
					return pojo.getId();
				return null;
			}
		);
		mongoDsl = new ReactiveMongoDsl<>(key -> switch (key) {
			case LEFT -> leftContext;
			case RIGHT -> rightContext;
			case DRIVER -> driverContext;
		}, objectMapper);

		Flux
			.fromIterable(COLLECTIONS)
			.concatMap(collection -> Mono.from(mongoDatabase.createCollection(collection)))
			.then()
			.block();
	}

	// 각 테스트가 서로 영향을 주지 않도록 테스트용 collection의 데이터를 매번 비운다.
	@BeforeEach
	void cleanCollections() {
		Flux
			.fromIterable(COLLECTIONS)
			.concatMap(collection -> Mono.from(mongoDatabase.getCollection(collection).deleteMany(new Document())))
			.then()
			.block();
	}

	// 통합 테스트가 끝나면 테스트 DB를 제거하고 MongoClient를 종료한다.
	@AfterAll
	void disconnect() {
		if (mongoDatabase != null)
			Mono.from(mongoDatabase.drop()).onErrorResume(ignored -> Mono.empty()).block();
		if (mongoClient != null)
			mongoClient.close();
	}

	// save/find/count/exists 전 과정을 실제 DB에서 실행해 String id↔ObjectId 및 물리 필드명 매핑이 round-trip 되는지 검증한다.
	@Test
	void saveFindCountExistsAndSpringLikeIdMappingRoundTrip() {
		ProductionLikeEntity entity = entity("account-a", "join-a", 2026, 101, "READY", 1000L);

		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();

		assertNotNull(entity.getId());
		assertTrue(ObjectId.isValid(entity.getId()));

		Document raw = rawEntity(entity.getId());
		assertInstanceOf(ObjectId.class, raw.get("_id"));
		assertFalse(raw.containsKey("id"));
		assertEquals("account-a", raw.getString("account_name"));
		assertEquals("join-a", raw.getString("left_join_key"));

		ProductionLikeEntity found = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("id", new ObjectId(entity.getId())), pair("account_name", "account-a"))
			.end()
			.find()
			.execute()
			.block();

		assertNotNull(found);
		assertEquals(entity.getId(), found.getId());
		assertEquals("account-a", found.getAccountName());
		assertEquals(
			1L,
			mongoDsl
				.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
				.fields(pair("_id", new ObjectId(entity.getId())))
				.end()
				.count()
				.execute()
				.block()
		);
		assertTrue(
			mongoDsl
				.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
				.fields(pair("_id", new ObjectId(entity.getId())))
				.end()
				.exists()
				.execute()
				.block()
		);
	}

	// 중첩 객체의 id와 물리 필드명이 실제 저장/조회에서도 올바른 dot path로 유지되는지 검증한다.
	@Test
	void nestedProductionPathsPreserveEmbeddedIdAndFieldMapping() {
		ProductionLikeEntity entity = entity("nested-path", "join-nested", 2026, 111, "READY", 1000L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();

		assertNotNull(entity.getAuction());
		assertTrue(ObjectId.isValid(entity.getAuction().getId()));

		Document raw = rawEntity(entity.getId());
		Document auction = raw.get("auction", Document.class);
		assertNotNull(auction);
		assertEquals(new ObjectId(entity.getAuction().getId()), auction.getObjectId("_id"));
		assertEquals("auction-111", auction.getString("auction_title"));

		ProductionLikeEntity found = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(
				pair("auction._id", new ObjectId(entity.getAuction().getId())),
				pair("auction.auction_title", "auction-111")
			)
			.end()
			.find()
			.execute()
			.block();

		assertNotNull(found);
		assertEquals(entity.getId(), found.getId());
		assertEquals(entity.getAuction().getId(), found.getAuction().getId());
	}

	// 자동 생성된 String id entity를 다시 save할 때 중복 insert가 아니라 기존 문서를 replace하는지 검증한다.
	@Test
	void repeatedSaveWithGeneratedStringIdReplacesInsteadOfInsertingDuplicate() {
		ProductionLikeEntity entity = entity("account-a", "join-a", 2026, 102, "READY", 1000L);
		var builder = mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT);

		builder.save(entity).block();
		entity.setStatus("UPDATED");
		entity.setAmount(2500L);
		builder.save(entity).block();

		assertEquals(1L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
		Document raw = rawEntity(entity.getId());
		assertEquals("UPDATED", raw.getString("status"));
		assertEquals(2500L, raw.get("amount", Number.class).longValue());
	}

	// saveAllBulk가 여러 entity에 id를 생성하고 실제 저장 후 동일 값으로 다시 읽을 수 있는지 검증한다.
	@Test
	void saveAllBulkAssignsGeneratedIdsAndRoundTrips() {
		List<ProductionLikeEntity> entities = List.of(
			entity("bulk-a", "join-a", 2026, 201, "READY", 100L),
			entity("bulk-b", "join-b", 2026, 202, "READY", 200L),
			entity("bulk-c", "join-c", 2026, 203, "READY", 300L)
		);

		List<ProductionLikeEntity> saved = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.saveAllBulk(entities)
			.collectList()
			.block();

		assertEquals(3, saved.size());
		assertTrue(saved.stream().allMatch(value -> value.getId() != null && ObjectId.isValid(value.getId())));
		assertEquals(3L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
	}

	// id 기준 bulk upsert가 신규 insert와 기존 update를 기존 의미대로 처리하는지 검증한다.
	@Test
	void bulkUpsertByIdMatchesLegacyInsertAndUpdateSemantics() {
		ProductionLikeEntity existing = entity("existing", "join-a", 2026, 301, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(existing).block();

		ProductionLikeEntity updated = entity("existing-updated", "join-a", 2026, 301, "DONE", 500L);
		updated.setId(existing.getId());
		ProductionLikeEntity inserted = entity("inserted", "join-b", 2026, 302, "READY", 200L);

		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.saveAllBulkUpsert(List.of(updated, inserted))
			.block();

		assertEquals(2L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
		Document raw = rawEntity(existing.getId());
		assertEquals("existing-updated", raw.getString("account_name"));
		assertEquals("DONE", raw.getString("status"));
	}

	// business key 기준 bulk upsert가 Java 필드명이 아니라 매핑된 MongoDB 물리 필드명을 사용하는지 검증한다.
	@Test
	void bulkUpsertByBusinessKeyUsesMappedMongoFields() {
		ProductionLikeEntity original = entity("business-key", "join-a", 2026, 401, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(original).block();

		ProductionLikeEntity sameKey = entity("business-key", "join-a", 2026, 401, "DONE", 999L);
		ProductionLikeEntity anotherKey = entity("business-key-2", "join-b", 2026, 402, "READY", 200L);

		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.saveAllBulkUpsertByKey(List.of(sameKey, anotherKey), "caseNo", "caseYear", "court", "account_name")
			.block();

		assertEquals(2L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
		Document updated = Mono
			.from(
				mongoDatabase
					.getCollection(ENTITY_COLLECTION)
					.find(new Document("account_name", "business-key").append("caseNo", 401))
					.first()
			)
			.block();
		assertNotNull(updated);
		assertEquals("DONE", updated.getString("status"));
		assertEquals(999L, updated.get("amount", Number.class).longValue());
	}

	// 단건 deleteWithBackup이 backup snapshot을 남긴 뒤 원본 문서를 제거하는지 검증한다.
	@Test
	void deleteWithBackupCreatesSnapshotAndRemovesSource() {
		ProductionLikeEntity entity = entity("remove", "join-a", 2026, 501, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();

		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).delete(entity, true).block();

		assertNull(rawEntity(entity.getId()));
		Document backup = Mono
			.from(
				mongoDatabase
					.getCollection(ENTITY_COLLECTION + "_remove")
					.find(new Document("_id", new ObjectId(entity.getId())))
					.first()
			)
			.block();
		assertNotNull(backup);
		assertEquals("remove", backup.getString("account_name"));
	}

	// 단건 backup 실패 시에도 기존 delete-first 순서/실패 의미가 유지되는지 검증한다.
	@Test
	void singleDeleteBackupFailureKeepsLegacyDeleteFirstSemantics() {
		ProductionLikeEntity entity = entity("delete-before-backup-failure", "join-a", 2026, 502, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();
		Document raw = rawEntity(entity.getId());
		Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION + "_remove").insertOne(new Document(raw))).block();

		StepVerifier
			.create(mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).delete(entity, true))
			.expectError()
			.verify();

		assertNull(rawEntity(entity.getId()));
	}

	// bulk deleteWithBackup이 삭제 대상 전체의 backup snapshot을 보존하고 원본을 제거하는지 검증한다.
	@Test
	void deleteBulkWithBackupPreservesAllSnapshots() {
		List<ProductionLikeEntity> entities = new ArrayList<>(List.of(
			entity("bulk-remove-a", "join-a", 2026, 511, "READY", 100L),
			entity("bulk-remove-b", "join-b", 2026, 512, "READY", 200L)
		));
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).saveAllBulk(entities).collectList().block();

		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).deleteBulk(entities, true).block();

		assertEquals(0L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
		assertEquals(2L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION + "_remove").countDocuments()).block());
	}

	// bulk backup 실패 시 기존 backup-first 의미에 따라 원본 삭제가 진행되지 않는지 검증한다.
	@Test
	void bulkDeleteBackupFailureKeepsLegacyBackupFirstSemantics() {
		List<ProductionLikeEntity> entities = new ArrayList<>(List.of(
			entity("bulk-keep-a", "join-a", 2026, 521, "READY", 100L),
			entity("bulk-keep-b", "join-b", 2026, 522, "READY", 200L)
		));
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).saveAllBulk(entities).collectList().block();
		Mono
			.from(
				mongoDatabase
					.getCollection(ENTITY_COLLECTION + "_remove")
					.insertOne(new Document(rawEntity(entities.getFirst().getId())))
			)
			.block();

		StepVerifier
			.create(mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).deleteBulk(entities, true))
			.expectError()
			.verify();

		assertEquals(2L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
	}

	// createHistory가 원본을 깊은 복사하고 history 문서에 독립적인 id를 생성하는지 검증한다.
	@Test
	void createHistoryDeepClonesAndGeneratesIndependentId() {
		ProductionLikeEntity entity = entity("history-before", "join-a", 2026, 601, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();
		ObjectId sourceId = new ObjectId(entity.getId());

		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.createHistory(entity, "history", objectMapper)
			.block();
		entity.setAccountName("history-after");

		Document history = Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION + "_history").find().first()).block();
		assertNotNull(history);
		assertInstanceOf(ObjectId.class, history.get("_id"));
		assertNotEquals(sourceId, history.getObjectId("_id"));
		assertEquals("history-before", history.getString("account_name"));
	}

	// atomic document update와 pipeline update가 매핑된 실제 MongoDB 필드명을 사용해 적용되는지 검증한다.
	@Test
	void atomicDocumentAndPipelineUpdatesUsePhysicalMongoFields() {
		ProductionLikeEntity entity = entity("atomic-before", "join-a", 2026, 701, "READY", 100L);
		entity.setRetryCount(1);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(entity).block();

		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("_id", new ObjectId(entity.getId())))
			.end()
			.atomicUpdate()
			.first()
			.document()
			.set("account_name", "atomic-document")
			.inc("retryCount", 1)
			.execute()
			.block();

		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("_id", new ObjectId(entity.getId())))
			.end()
			.atomicUpdate()
			.first()
			.pipeline()
			.set("account_name", "atomic-pipeline")
			.inc("retryCount", 2)
			.execute()
			.block();

		Document raw = rawEntity(entity.getId());
		assertEquals("atomic-pipeline", raw.getString("account_name"));
		assertFalse(raw.containsKey("accountName"));
		assertEquals(4, raw.get("retryCount", Number.class).intValue());
	}

	// findAll/aggregation 경로에서 물리 필드 기준 sort, paging, projection 결과가 일관되게 유지되는지 검증한다.
	@Test
	void findAllAndAggregationPreservePhysicalSortPagingAndProjection() {
		List<ProductionLikeEntity> entities = List.of(
			entity("alpha", "join-a", 2026, 801, "READY", 100L),
			entity("charlie", "join-b", 2026, 802, "READY", 200L),
			entity("bravo", "join-c", 2026, 803, "READY", 300L)
		);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).saveAllBulk(entities).collectList().block();

		List<ProductionLikeEntity> normal = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields()
			.end()
			.findAll()
			.sorts(spec -> spec.driver(Sorts.descending("account_name")))
			.paging(0, 2)
			.excludes("status")
			.execute()
			.collectList()
			.block();

		List<ProductionLikeEntity> aggregation = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields()
			.end()
			.findAll()
			.sorts(spec -> spec.driver(Sorts.descending("account_name")))
			.paging(0, 2)
			.excludes("status")
			.executeAggregationStream()
			.collectList()
			.block();

		assertEquals(List.of("charlie", "bravo"), normal.stream().map(ProductionLikeEntity::getAccountName).toList());
		assertEquals(List.of("charlie", "bravo"), aggregation.stream().map(ProductionLikeEntity::getAccountName).toList());
		assertTrue(normal.stream().allMatch(value -> value.getStatus() == null));
		assertTrue(aggregation.stream().allMatch(value -> value.getStatus() == null));
	}

	// aggregation paging 결과의 data/totalCount 구조가 실제 운영용 page contract와 동일한지 검증한다.
	@Test
	void aggregationPageResultMatchesProductionPagingAndCountShape() {
		List<ProductionLikeEntity> entities = List.of(
			entity("page-alpha", "join-a", 2026, 811, "READY", 100L),
			entity("page-charlie", "join-b", 2026, 812, "READY", 200L),
			entity("page-bravo", "join-c", 2026, 813, "READY", 300L)
		);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).saveAllBulk(entities).collectList().block();

		var page = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("status", "READY"))
			.end()
			.findAll()
			.paging()
			.pageSize(2)
			.pageNumber(0)
			.and()
			.sorts(spec -> spec.driver(Sorts.descending("account_name")))
			.executeAggregation()
			.block();

		assertNotNull(page);
		assertEquals(3L, page.getTotalCount());
		assertEquals(List.of("page-charlie", "page-bravo"), page.getData().stream().map(ProductionLikeEntity::getAccountName).toList());
	}


	// lookup이 좌/우 매핑 필드를 정확히 사용하고 오른쪽 결과는 오른쪽 execution context로 decode하는지 검증한다.
	@Test
	void lookupMapsLeftAndRightFieldsAndUsesRightExecutionContextForMapping() {
		ProductionLikeEntity left = entity("lookup-left", "join-lookup", 2026, 901, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(left).block();
		mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.saveAllBulk(List.of(
				child("join-lookup", "ACTIVE", "selected"),
				child("join-lookup", "INACTIVE", "ignored"),
				child("other", "ACTIVE", "other")
			))
			.collectList()
			.block();

		var leftBuilder = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("_id", new ObjectId(left.getId())))
			.end();
		var rightBuilder = mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.fields(pair("child_status", "ACTIVE"))
			.end()
			.findAll();

		List<ResultTuple<ProductionLikeEntity, List<ProductionLikeChild>>> result = leftBuilder
			.findAll()
			.executeLookup(
				rightBuilder,
				LookupSpec
					.builder()
					.as("childHit")
					.bindConditionFields("left_join_key", Condition.eq, "right_join_key")
					.limit(10)
					.build()
			)
			.collectList()
			.block();

		assertEquals(1, result.size());
		assertEquals(1, result.getFirst().getRight().size());
		assertEquals("selected", result.getFirst().getRight().getFirst().getValue());
		assertEquals("ACTIVE", result.getFirst().getRight().getFirst().getStatus());
	}

	// lookup 결과를 unwind하면 MongoDB가 오른쪽 값을 배열이 아닌 단일 Document로 반환해도 singleton List로 안전하게 매핑되는지 회귀 검증한다.
	@Test
	void lookupUnwindMapsSingleDocumentBackToSingletonRightList() {
		ProductionLikeEntity left = entity("lookup-unwind", "join-unwind", 2026, 906, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(left).block();
		mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.saveAllBulk(List.of(
				child("join-unwind", "ACTIVE", "selected-a"),
				child("join-unwind", "ACTIVE", "selected-b"),
				child("join-unwind", "INACTIVE", "ignored")
			))
			.collectList()
			.block();

		var leftBuilder = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("_id", new ObjectId(left.getId())))
			.end();
		var rightBuilder = mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.fields(pair("child_status", "ACTIVE"))
			.end()
			.findAll();

		List<ResultTuple<ProductionLikeEntity, List<ProductionLikeChild>>> result = leftBuilder
			.findAll()
			.executeLookup(
				rightBuilder,
				LookupSpec
					.builder()
					.as("childHit")
					.bindConditionFields("left_join_key", Condition.eq, "right_join_key")
					.unwind(false)
					.build()
			)
			.collectList()
			.block();

		assertEquals(2, result.size());
		assertTrue(result.stream().allMatch(tuple -> tuple.getRight().size() == 1));
		assertEquals(
			List.of("selected-a", "selected-b"),
			result.stream().map(tuple -> tuple.getRight().getFirst().getValue()).sorted().toList()
		);
	}

	// preserveNullAndEmptyArrays=true인 unwind에서 매칭 결과가 없어도 left row를 유지하고 오른쪽 값을 빈 List로 매핑하는지 검증한다.
	@Test
	void lookupUnwindPreservesLeftRowAndMapsMissingRightValueToEmptyList() {
		ProductionLikeEntity left = entity("lookup-unwind-empty", "join-unwind-empty", 2026, 907, "READY", 100L);
		mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT).save(left).block();

		var leftBuilder = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("_id", new ObjectId(left.getId())))
			.end();
		var rightBuilder = mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.fields(pair("child_status", "ACTIVE"))
			.end()
			.findAll();

		List<ResultTuple<ProductionLikeEntity, List<ProductionLikeChild>>> result = leftBuilder
			.findAll()
			.executeLookup(
				rightBuilder,
				LookupSpec
					.builder()
					.as("childHit")
					.bindConditionFields("left_join_key", Condition.eq, "right_join_key")
					.unwind(true)
					.build()
			)
			.collectList()
			.block();

		assertEquals(1, result.size());
		assertEquals(left.getId(), result.getFirst().getLeft().getId());
		assertTrue(result.getFirst().getRight().isEmpty());
	}

	// lookup+count가 facet paging의 total/data 구조와 오른쪽 entity 매핑을 함께 보존하는지 검증한다.
	@Test
	void lookupAndCountMatchesProductionFacetPagingAndRightMapping() {
		ProductionLikeEntity first = entity("lookup-count-a", "join-count-a", 2026, 911, "READY", 100L);
		ProductionLikeEntity second = entity("lookup-count-b", "join-count-b", 2026, 912, "READY", 200L);
		ProductionLikeEntity ignored = entity("lookup-count-c", "join-count-c", 2026, 913, "DONE", 300L);
		mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.saveAllBulk(List.of(first, second, ignored))
			.collectList()
			.block();

		mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.saveAllBulk(List.of(
				child("join-count-a", "ACTIVE", "selected-a"),
				child("join-count-b", "ACTIVE", "selected-b"),
				child("join-count-b", "INACTIVE", "ignored-b"),
				child("join-count-c", "ACTIVE", "ignored-left")
			))
			.collectList()
			.block();

		var leftBuilder = mongoDsl
			.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT)
			.fields(pair("status", "READY"))
			.end();
		var rightBuilder = mongoDsl
			.executeEntity(ProductionLikeChild.class, TestMongo.RIGHT)
			.fields(pair("child_status", "ACTIVE"))
			.end()
			.findAll();

		var page = leftBuilder
			.findAll()
			.paging(0, 1)
			.executeLookupAndCount(
				rightBuilder,
				LookupSpec
					.builder()
					.as("childHit")
					.bindConditionFields("left_join_key", Condition.eq, "right_join_key")
					.limit(10)
					.build()
			)
			.block();

		assertNotNull(page);
		assertEquals(2L, page.getTotalCount());
		assertEquals(1, page.getData().size());
		assertEquals(1, page.getData().getFirst().getRight().size());
		assertEquals("ACTIVE", page.getData().getFirst().getRight().getFirst().getStatus());
		assertTrue(
			List.of("selected-a", "selected-b")
				.contains(page.getData().getFirst().getRight().getFirst().getValue())
		);
	}

	// 트랜잭션 안의 순차 write가 모두 성공하면 commit되어 실제 DB에 반영되는지 검증한다.
	@Test
	void transactionCommitsSequentialWrites() {
		ProductionLikeEntity first = entity("tx-commit-a", "join-a", 2026, 1001, "READY", 100L);
		ProductionLikeEntity second = entity("tx-commit-b", "join-b", 2026, 1002, "READY", 200L);
		var builder = mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT);

		String result = mongoDsl
			.getTxJob(TestMongo.LEFT, () -> builder.save(first).then(builder.save(second)).thenReturn("committed"))
			.block();

		assertEquals("committed", result);
		assertEquals(2L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
	}

	// 트랜잭션 중 오류가 발생하면 앞서 수행한 write까지 전부 rollback되는지 검증한다.
	@Test
	void transactionRollsBackAllWritesOnError() {
		ProductionLikeEntity first = entity("tx-rollback-a", "join-a", 2026, 1011, "READY", 100L);
		ProductionLikeEntity second = entity("tx-rollback-b", "join-b", 2026, 1012, "READY", 200L);
		var builder = mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT);

		StepVerifier
			.create(
				mongoDsl.getTxJob(
					TestMongo.LEFT,
					() -> builder
						.save(first)
						.then(builder.save(second))
						.then(Mono.error(new IllegalStateException("rollback")))
				)
			)
			.expectErrorMatches(error -> error instanceof IllegalStateException && "rollback".equals(error.getMessage()))
			.verify();

		assertEquals(0L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
	}

	// 트랜잭션 작업 publisher가 empty로 끝나더라도 완료된 write는 정상 commit되는지 검증한다.
	@Test
	void emptyTransactionalPublisherStillCommitsCompletedWrites() {
		ProductionLikeEntity entity = entity("tx-empty", "join-a", 2026, 1021, "READY", 100L);
		var builder = mongoDsl.executeEntity(ProductionLikeEntity.class, TestMongo.LEFT);

		StepVerifier
			.create(mongoDsl.getTxJob(TestMongo.LEFT, () -> builder.save(entity).then(Mono.empty())))
			.verifyComplete();

		assertEquals(1L, Mono.from(mongoDatabase.getCollection(ENTITY_COLLECTION).countDocuments()).block());
	}

	// MongoTemplateResolver/MongoExecutionContext 추상화 뒤에서도 구현체의 native 객체를 타입으로 다시 얻을 수 있는지 검증한다.
	@Test
	void nativeObjectRemainsAvailableThroughResolverAbstraction() {
		assertSame(leftContext.nativeMarker(), mongoDsl.getMongoTemplate(TestMongo.LEFT).getNative(NativeMarker.class));
	}

	// Driver context가 생성한 String ObjectId를 조회 조건과 재-save에 사용해도 중복 문서 없이 동일 entity를 갱신하는지 검증한다.
	@Test
	void driverContextGeneratedStringIdCanBeQueriedAndSavedAgainWithoutDuplicate() {
		DriverPojo pojo = new DriverPojo();
		pojo.setAccountName("driver-a");
		pojo.setStatus("READY");

		var builder = mongoDsl.executeEntity(DriverPojo.class, TestMongo.DRIVER);
		builder.save(pojo).block();

		assertNotNull(pojo.getId());
		assertTrue(ObjectId.isValid(pojo.getId()));
		Document inserted = Mono
			.from(
				mongoDatabase
					.getCollection(DRIVER_COLLECTION)
					.find(new Document("_id", new ObjectId(pojo.getId())))
					.first()
			)
			.block();
		assertNotNull(inserted);
		assertEquals("driver-a", inserted.getString("account_name"));

		DriverPojo found = mongoDsl
			.executeEntity(DriverPojo.class, TestMongo.DRIVER)
			.fields(pair("_id", new ObjectId(pojo.getId())))
			.end()
			.find()
			.execute()
			.block();
		assertNotNull(found);
		assertEquals(pojo.getId(), found.getId());

		pojo.setStatus("UPDATED");
		builder.save(pojo).block();
		assertEquals(1L, Mono.from(mongoDatabase.getCollection(DRIVER_COLLECTION).countDocuments()).block());
		Document updated = Mono
			.from(
				mongoDatabase
					.getCollection(DRIVER_COLLECTION)
					.find(new Document("_id", new ObjectId(pojo.getId())))
					.first()
			)
			.block();
		assertEquals("UPDATED", updated.getString("status"));
	}

	private Document rawEntity(String id) {
		return Mono
			.from(
				mongoDatabase
					.getCollection(ENTITY_COLLECTION)
					.find(new Document("_id", new ObjectId(id)))
					.first()
			)
			.block();
	}

	private static ProductionLikeEntity entity(
		String accountName, String joinKey, int caseYear, int caseNo, String status, long amount
	) {
		ProductionLikeEntity entity = new ProductionLikeEntity();
		entity.setAccountName(accountName);
		entity.setJoinKey(joinKey);
		entity.setCourt("SEOUL");
		entity.setCaseYear(caseYear);
		entity.setCaseNo(caseNo);
		entity.setStatus(status);
		entity.setAmount(amount);
		entity.setRetryCount(0);
		entity.setCreatedAt(Instant.parse("2026-08-11T00:00:00Z"));
		entity.setTags(List.of("production", "migration"));
		ProductionLikeAuction auction = new ProductionLikeAuction();
		auction.setId(new ObjectId().toHexString());
		auction.setTitle("auction-" + caseNo);
		entity.setAuction(auction);
		return entity;
	}

	private static ProductionLikeChild child(String joinKey, String status, String value) {
		ProductionLikeChild child = new ProductionLikeChild();
		child.setJoinKey(joinKey);
		child.setStatus(status);
		child.setValue(value);
		return child;
	}

	private enum TestMongo {
		LEFT,
		RIGHT,
		DRIVER
	}

	private record TestEnvironment(String clusterName, String username, String password, String url) {

		static TestEnvironment load() {
			return new TestEnvironment(
				System.getenv("TEST_CLUSTER_NAME"),
				System.getenv("TEST_USERNAME"),
				System.getenv("TEST_PASSWORD"),
				System.getenv("TEST_URL")
			);
		}

		boolean isComplete() {
			return clusterName != null && !clusterName.isBlank()
				&& username != null && !username.isBlank()
				&& password != null && !password.isBlank()
				&& url != null && !url.isBlank();
		}

		String connectionString() {
			String value = url.trim();
			if (value.startsWith("mongodb://") || value.startsWith("mongodb+srv://"))
				return value;
			String suffix = value.startsWith("@") ? value : "@" + value;
			return "mongodb+srv://" + encode(username) + ":" + encode(password) + suffix;
		}

		String databaseName() {
			String sanitized = clusterName.replaceAll("[^A-Za-z0-9_]", "_");
			if (sanitized.length() > 20)
				sanitized = sanitized.substring(0, 20);
			return "rmdsl_v1_" + sanitized + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		}

		private static String encode(String value) {
			return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
		}
	}

	private static final class NativeMarker {
		private final String name;

		private NativeMarker(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static final class ProductionLikeMongoExecutionContext implements MongoExecutionContext {

		private final MongoClient mongoClient;
		private final MongoDatabase mongoDatabase;
		private final boolean left;
		private final NativeMarker nativeMarker;

		private ProductionLikeMongoExecutionContext(
			MongoClient mongoClient, MongoDatabase mongoDatabase, boolean left
		) {
			this.mongoClient = mongoClient;
			this.mongoDatabase = mongoDatabase;
			this.left = left;
			this.nativeMarker = new NativeMarker(left ? "left-native" : "right-native");
		}

		static ProductionLikeMongoExecutionContext left(MongoClient mongoClient, MongoDatabase mongoDatabase) {
			return new ProductionLikeMongoExecutionContext(mongoClient, mongoDatabase, true);
		}

		static ProductionLikeMongoExecutionContext right(MongoClient mongoClient, MongoDatabase mongoDatabase) {
			return new ProductionLikeMongoExecutionContext(mongoClient, mongoDatabase, false);
		}

		NativeMarker nativeMarker() {
			return nativeMarker;
		}

		@Override
		public Mono<MongoDatabase> getDatabase() {
			return Mono.just(mongoDatabase);
		}

		@Override
		public Mono<ClientSession> startSession() {
			return Mono.from(mongoClient.startSession());
		}

		@Override
		public String getCollectionName(Class<?> entityClass) {
			if (entityClass == ProductionLikeEntity.class)
				return ENTITY_COLLECTION;
			if (entityClass == ProductionLikeChild.class)
				return CHILD_COLLECTION;
			throw new IllegalArgumentException("Unsupported production-like type: " + entityClass.getName());
		}

		@Override
		public Document write(Object source) {
			if (source instanceof ProductionLikeEntity entity) {
				assertSupported(ProductionLikeEntity.class);
				Document document = new Document();
				if (entity.getId() != null)
					document.put("_id", toMongoId(entity.getId()));
				putIfNotNull(document, "account_name", entity.getAccountName());
				putIfNotNull(document, "left_join_key", entity.getJoinKey());
				putIfNotNull(document, "court", entity.getCourt());
				putIfNotNull(document, "caseYear", entity.getCaseYear());
				putIfNotNull(document, "caseNo", entity.getCaseNo());
				putIfNotNull(document, "status", entity.getStatus());
				putIfNotNull(document, "amount", entity.getAmount());
				putIfNotNull(document, "retryCount", entity.getRetryCount());
				putIfNotNull(document, "createdAt", entity.getCreatedAt() == null ? null : Date.from(entity.getCreatedAt()));
				putIfNotNull(document, "tags", entity.getTags());
				if (entity.getAuction() != null) {
					Document auction = new Document();
					putIfNotNull(auction, "_id", toMongoId(entity.getAuction().getId()));
					putIfNotNull(auction, "auction_title", entity.getAuction().getTitle());
					document.put("auction", auction);
				}
				return document;
			}
			if (source instanceof ProductionLikeChild child) {
				assertSupported(ProductionLikeChild.class);
				Document document = new Document();
				if (child.getId() != null)
					document.put("_id", toMongoId(child.getId()));
				putIfNotNull(document, "right_join_key", child.getJoinKey());
				putIfNotNull(document, "child_status", child.getStatus());
				putIfNotNull(document, "value", child.getValue());
				return document;
			}
			throw new IllegalArgumentException("Unsupported production-like source: " + source.getClass().getName());
		}

		@Override
		public <T> T read(Class<T> targetType, Document source) {
			assertSupported(targetType);
			if (targetType == ProductionLikeEntity.class) {
				ProductionLikeEntity entity = new ProductionLikeEntity();
				entity.setId(fromMongoId(source.get("_id")));
				entity.setAccountName(source.getString("account_name"));
				entity.setJoinKey(source.getString("left_join_key"));
				entity.setCourt(source.getString("court"));
				entity.setCaseYear(number(source, "caseYear", Integer::valueOf));
				entity.setCaseNo(number(source, "caseNo", Integer::valueOf));
				entity.setStatus(source.getString("status"));
				entity.setAmount(number(source, "amount", Long::valueOf));
				entity.setRetryCount(number(source, "retryCount", Integer::valueOf));
				Date createdAt = source.getDate("createdAt");
				entity.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
				@SuppressWarnings("unchecked")
				List<String> tags = (List<String>) source.get("tags");
				entity.setTags(tags);
				Document auctionDocument = source.get("auction", Document.class);
				if (auctionDocument != null) {
					ProductionLikeAuction auction = new ProductionLikeAuction();
					auction.setId(fromMongoId(auctionDocument.get("_id")));
					auction.setTitle(auctionDocument.getString("auction_title"));
					entity.setAuction(auction);
				}
				return targetType.cast(entity);
			}
			if (targetType == ProductionLikeChild.class) {
				ProductionLikeChild child = new ProductionLikeChild();
				child.setId(fromMongoId(source.get("_id")));
				child.setJoinKey(source.getString("right_join_key"));
				child.setStatus(source.getString("child_status"));
				child.setValue(source.getString("value"));
				return targetType.cast(child);
			}
			throw new IllegalArgumentException("Unsupported production-like target: " + targetType.getName());
		}

		@Override
		public Object getId(Object entity) {
			if (entity instanceof ProductionLikeEntity value) {
				assertSupported(ProductionLikeEntity.class);
				return toMongoId(value.getId());
			}
			if (entity instanceof ProductionLikeChild value) {
				assertSupported(ProductionLikeChild.class);
				return toMongoId(value.getId());
			}
			return null;
		}

		@Override
		public void setId(Object entity, Object id) {
			if (entity instanceof ProductionLikeEntity value) {
				assertSupported(ProductionLikeEntity.class);
				value.setId(fromMongoId(id));
			} else if (entity instanceof ProductionLikeChild value) {
				assertSupported(ProductionLikeChild.class);
				value.setId(fromMongoId(id));
			}
		}

		@Override
		public Object getNative() {
			return nativeMarker;
		}

		private void assertSupported(Class<?> entityClass) {
			if (left && entityClass != ProductionLikeEntity.class)
				throw new IllegalArgumentException("Left context cannot map " + entityClass.getName());
			if (!left && entityClass != ProductionLikeChild.class)
				throw new IllegalArgumentException("Right context cannot map " + entityClass.getName());
		}

		private static Object toMongoId(String id) {
			if (id == null)
				return null;
			return ObjectId.isValid(id) ? new ObjectId(id) : id;
		}

		private static String fromMongoId(Object id) {
			if (id == null)
				return null;
			return id instanceof ObjectId objectId ? objectId.toHexString() : id.toString();
		}

		private static void putIfNotNull(Document document, String key, Object value) {
			if (value != null)
				document.put(key, value);
		}

		private static <T> T number(Document source, String key, java.util.function.Function<String, T> mapper) {
			Number value = source.get(key, Number.class);
			return value == null ? null : mapper.apply(value.toString());
		}
	}

	public static class ProductionLikeEntity {

		private String id;
		private String accountName;
		private String joinKey;
		private String court;
		private Integer caseYear;
		private Integer caseNo;
		private String status;
		private Long amount;
		private Integer retryCount;
		private Instant createdAt;
		private List<String> tags;
		private ProductionLikeAuction auction;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getAccountName() { return accountName; }
		public void setAccountName(String accountName) { this.accountName = accountName; }
		public String getJoinKey() { return joinKey; }
		public void setJoinKey(String joinKey) { this.joinKey = joinKey; }
		public String getCourt() { return court; }
		public void setCourt(String court) { this.court = court; }
		public Integer getCaseYear() { return caseYear; }
		public void setCaseYear(Integer caseYear) { this.caseYear = caseYear; }
		public Integer getCaseNo() { return caseNo; }
		public void setCaseNo(Integer caseNo) { this.caseNo = caseNo; }
		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }
		public Long getAmount() { return amount; }
		public void setAmount(Long amount) { this.amount = amount; }
		public Integer getRetryCount() { return retryCount; }
		public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
		public Instant getCreatedAt() { return createdAt; }
		public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
		public List<String> getTags() { return tags; }
		public void setTags(List<String> tags) { this.tags = tags; }
		public ProductionLikeAuction getAuction() { return auction; }
		public void setAuction(ProductionLikeAuction auction) { this.auction = auction; }
	}

	public static class ProductionLikeAuction {

		private String id;
		private String title;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getTitle() { return title; }
		public void setTitle(String title) { this.title = title; }
	}

	public static class ProductionLikeChild {

		private String id;
		private String joinKey;
		private String status;
		private String value;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getJoinKey() { return joinKey; }
		public void setJoinKey(String joinKey) { this.joinKey = joinKey; }
		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }
		public String getValue() { return value; }
		public void setValue(String value) { this.value = value; }
	}

	public static class DriverPojo {

		private String id;

		@BsonProperty("account_name")
		private String accountName;

		private String status;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getAccountName() { return accountName; }
		public void setAccountName(String accountName) { this.accountName = accountName; }
		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }
	}
}
