package com.byeolnaerim.mongodsl;

import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;
import com.byeolnaerim.mongodsl.criteria.MongoCriteriaSupport;
import com.byeolnaerim.mongodsl.query.MongoCriteria;
import com.byeolnaerim.mongodsl.search.SearchOperators;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.lang.reflect.Proxy;
import java.util.List;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MongoExecutionContextMappingTest {

	@Test
	void defaultContextHooksMapFiltersUpdatesAndAggregationExpressions() {
		MongoExecutionContext context = new MappingOnlyContext();

		Document filter = new Document(
			"$and",
			List.of(
				new Document("id", "507f1f77bcf86cd799439011"),
				new Document("accountName", new Document("$in", List.of("a", "b"))),
				new Document("nested", new Document("$elemMatch", new Document("accountName", "c"))),
				new Document("nested.id", "507f1f77bcf86cd799439012")
			)
		);
		Document mappedFilter = context.mapQuery(MappedEntity.class, filter);
		@SuppressWarnings("unchecked")
		List<Document> and = (List<Document>) mappedFilter.get("$and");
		assertTrue(and.get(0).containsKey("_id"));
		assertTrue(and.get(1).containsKey("account_name"));
		Document elemMatch = (Document) ((Document) and.get(2).get("nested")).get("$elemMatch");
		assertTrue(elemMatch.containsKey("account_name"));
		assertTrue(and.get(3).containsKey("nested._id"));

		Document update = context.mapUpdate(
			MappedEntity.class,
			new Document("$set", new Document("accountName", "after").append("nested.accountName", "nested-after"))
				.append("$inc", new Document("retryCount", 1))
		);
		assertEquals("after", ((Document) update.get("$set")).getString("account_name"));
		assertEquals("nested-after", ((Document) update.get("$set")).getString("nested.account_name"));
		assertEquals(1, ((Document) update.get("$inc")).getInteger("retryCount"));

		List<Document> pipeline = context.mapAggregationPipeline(
			MappedEntity.class,
			List.of(
				new Document("$match", new Document("id", "507f1f77bcf86cd799439011")),
				new Document("$sort", new Document("accountName", -1)),
				new Document(
					"$set",
					new Document("accountName", "$accountName")
						.append("copy", "$accountName")
				)
			)
		);
		assertTrue(((Document) pipeline.get(0).get("$match")).containsKey("_id"));
		assertTrue(((Document) pipeline.get(1).get("$sort")).containsKey("account_name"));
		Document set = (Document) pipeline.get(2).get("$set");
		assertEquals("$account_name", set.getString("account_name"));
		assertEquals("$account_name", set.getString("copy"));
	}

	@Test
	void atlasSearchAndVectorSearchStagesRemainNativeDocumentsDuringMapping() {
		MongoExecutionContext context = new MappingOnlyContext();
		Document searchBody = new Document("index", "production_search")
			.append("compound", new Document("must", List.of(
				SearchOperators.<String>text().path("accountName").query("alpha").toDocument()
			)));
		Document vectorBody = new Document("index", "production_vector")
			.append("path", "embedding")
			.append("queryVector", List.of(0.1, 0.2, 0.3))
			.append("numCandidates", 10)
			.append("limit", 3);

		List<Document> mapped = context.mapAggregationPipeline(
			MappedEntity.class,
			List.of(
				new Document("$search", searchBody),
				new Document("$vectorSearch", vectorBody),
				new Document("$match", new Document("accountName", "alpha"))
			)
		);

		assertEquals(searchBody, mapped.get(0).get("$search"));
		assertEquals(vectorBody, mapped.get(1).get("$vectorSearch"));
		assertTrue(((Document) mapped.get(2).get("$match")).containsKey("account_name"));
	}

	@Test
	void driverContextMapsFallbackStringIdToObjectIdAndBsonPropertyWithoutDatabaseRoundTrip() {
		CodecRegistry codecRegistry = CodecRegistries.fromRegistries(
			MongoClientSettings.getDefaultCodecRegistry(),
			CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
		);
		MongoDatabase database = proxy(
			MongoDatabase.class,
			(method, args) -> {
				if (method.getName().equals("getCodecRegistry"))
					return codecRegistry;
				if (method.getName().equals("getName"))
					return "unit-test";
				return defaultValue(method.getReturnType());
			}
		);
		MongoClient client = proxy(
			MongoClient.class,
			(method, args) -> defaultValue(method.getReturnType())
		);
		DriverMongoExecutionContext context = new DriverMongoExecutionContext(client, database);

		DriverMappedEntity entity = new DriverMappedEntity();
		entity.setId("507f1f77bcf86cd799439011");
		entity.setAccountName("alpha");
		entity.setStatus("READY");

		Document written = context.write(entity);
		assertInstanceOf(ObjectId.class, written.get("_id"));
		assertEquals(new ObjectId(entity.getId()), written.getObjectId("_id"));
		assertEquals("alpha", written.getString("account_name"));
		assertFalse(written.containsKey("id"));
		assertFalse(written.containsKey("accountName"));
		assertEquals(new ObjectId(entity.getId()), context.getId(entity));

		Document mappedQuery = context.mapQuery(
			DriverMappedEntity.class,
			new Document("$or", List.of(
				new Document("id", entity.getId()),
				new Document("id", new Document("$in", List.of(entity.getId(), "business-id")))
			))
		);
		@SuppressWarnings("unchecked")
		List<Document> or = (List<Document>) mappedQuery.get("$or");
		assertEquals(new ObjectId(entity.getId()), or.get(0).get("_id"));
		@SuppressWarnings("unchecked")
		List<Object> in = (List<Object>) ((Document) or.get(1).get("_id")).get("$in");
		assertInstanceOf(ObjectId.class, in.get(0));
		assertEquals("business-id", in.get(1));

		List<Document> mappedPipeline = context.mapAggregationPipeline(
			DriverMappedEntity.class,
			List.of(
				new Document("$match", new Document("id", entity.getId())),
				new Document(
					"$facet",
					new Document("data", List.of(new Document("$match", new Document("id", entity.getId()))))
				)
			)
		);
		assertEquals(new ObjectId(entity.getId()), ((Document) mappedPipeline.get(0).get("$match")).get("_id"));
		@SuppressWarnings("unchecked")
		List<Document> facetData = (List<Document>) ((Document) mappedPipeline.get(1).get("$facet")).get("data");
		assertEquals(new ObjectId(entity.getId()), ((Document) facetData.get(0).get("$match")).get("_id"));

		DriverMappedEntity read = context.read(DriverMappedEntity.class, written);
		assertEquals(entity.getId(), read.getId());
		assertEquals("alpha", read.getAccountName());
		assertEquals("READY", read.getStatus());
	}

	@Test
	void criteriaRenderingKeepsLegacyConditionSemantics() {
		assertEquals(new Document("status", "READY"), criteria(pair("status", "READY", Condition.eq)));
		assertEquals(
			new Document("retryCount", new Document("$gte", 1).append("$lte", 3)),
			criteria(pair("retryCount", List.of(1, 3), Condition.between))
		);
		assertEquals(
			new Document("status", new Document("$in", List.of("READY", "DONE"))),
			criteria(pair("status", List.of("READY", "DONE"), Condition.in))
		);
		Document like = criteria(pair("accountName", "alpha", Condition.like));
		assertTrue(like.get("accountName") instanceof Document);
		assertEquals("alpha", ((Document) like.get("accountName")).getString("$regex"));
		assertEquals("i", ((Document) like.get("accountName")).getString("$options"));
		assertEquals(new Document("status", new Document("$ne", "READY")), criteria(pair("status", "READY", Condition.notEq)));
		assertEquals(new Document("retryCount", new Document("$gt", 1)), criteria(pair("retryCount", 1, Condition.gt)));
		assertEquals(new Document("retryCount", new Document("$gte", 1)), criteria(pair("retryCount", 1, Condition.gte)));
		assertEquals(new Document("retryCount", new Document("$lt", 3)), criteria(pair("retryCount", 3, Condition.lt)));
		assertEquals(new Document("retryCount", new Document("$lte", 3)), criteria(pair("retryCount", 3, Condition.lte)));
		assertEquals(
			new Document("status", new Document("$nin", List.of("DELETED", "BLOCKED"))),
			criteria(pair("status", List.of("DELETED", "BLOCKED"), Condition.notIn))
		);
		assertEquals(new Document("status", new Document("$regex", "^READY$")), criteria(pair("status", "^READY$", Condition.regex)));
		assertEquals(new Document("status", new Document("$exists", true)), criteria(pair("status", true, Condition.exists)));
		assertEquals(new Document("deletedAt", null), criteria(pair("deletedAt", Condition.isNull)));
		assertEquals(new Document("deletedAt", new Document("$ne", null)), criteria(pair("deletedAt", Condition.isNotNull)));
		assertEquals(
			new Document("tags", new Document("$all", List.of("production", "migration"))),
			criteria(pair("tags", List.of("production", "migration"), Condition.all))
		);
		assertEquals(
			new Document("location", new Document("$near", List.of(127.0, 37.0)).append("$maxDistance", 5000.0)),
			criteria(pair("location", new Double[] {127.0, 37.0, 5000.0}, Condition.near))
		);
		Document nearSphere = criteria(pair("location", new Double[] {127.0, 37.0, 5000.0, 100.0}, Condition.nearSphere));
		Document nearSphereOperators = (Document) nearSphere.get("location");
		assertEquals(List.of(127.0, 37.0), nearSphereOperators.get("$nearSphere"));
		assertEquals(5000.0 / 6_378_137.0, nearSphereOperators.getDouble("$maxDistance"));
		assertEquals(100.0 / 6_378_137.0, nearSphereOperators.getDouble("$minDistance"));
		assertEquals(
			new Document(
				"items",
				new Document("$elemMatch", new Document("$and", List.of(new Document("status", "READY"), new Document("amount", new Document("$gt", 0L)))))
			),
			criteria(pair("items", List.of(pair("status", "READY"), pair("amount", 0L, Condition.gt)), Condition.elemMatch))
		);
	}

	private static Document criteria(com.byeolnaerim.mongodsl.criteria.FieldsPair<?, ?> pair) {
		MongoCriteria criteria = MongoCriteriaSupport.createSingleCriteria(pair);
		return criteria.getCriteriaObject();
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Invocation invocation) {
		return (T) Proxy.newProxyInstance(
			type.getClassLoader(),
			new Class<?>[] { type },
			(proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) {
					return switch (method.getName()) {
						case "toString" -> type.getSimpleName() + "Proxy";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> null;
					};
				}
				return invocation.invoke(method, args);
			}
		);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive())
			return null;
		if (type == boolean.class)
			return false;
		if (type == byte.class)
			return (byte) 0;
		if (type == short.class)
			return (short) 0;
		if (type == int.class)
			return 0;
		if (type == long.class)
			return 0L;
		if (type == float.class)
			return 0F;
		if (type == double.class)
			return 0D;
		if (type == char.class)
			return '\0';
		return null;
	}

	@FunctionalInterface
	private interface Invocation {
		Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
	}

	private static final class MappingOnlyContext implements MongoExecutionContext {

		@Override
		public Mono<MongoDatabase> getDatabase() {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<ClientSession> startSession() {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public String getCollectionName(Class<?> entityClass) {
			return "unused";
		}

		@Override
		public Document write(Object source) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T read(Class<T> targetType, Document source) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object getId(Object entity) {
			return null;
		}

		@Override
		public String getMappedFieldName(Class<?> entityClass, String fieldName) {
			return switch (fieldName) {
				case "id" -> "_id";
				case "accountName" -> "account_name";
				case "nested.id" -> "nested._id";
				case "nested.accountName" -> "nested.account_name";
				default -> fieldName;
			};
		}

		@Override
		public Object getNative() {
			return this;
		}
	}

	private static final class MappedEntity {}

	public static class DriverMappedEntity {

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
