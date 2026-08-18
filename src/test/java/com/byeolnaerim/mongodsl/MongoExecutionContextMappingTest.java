package com.byeolnaerim.mongodsl;

import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;
import com.byeolnaerim.mongodsl.criteria.FieldsPairBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoIdFieldResolver;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
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
	void fieldsPairUsesPhysicalMongoFieldNamesExceptIdConvention() {
		assertEquals(new Document("_id", "abc"), criteria(pair("id", "abc", Condition.eq)));
		assertEquals(new Document("_id", new ObjectId("507f1f77bcf86cd799439011")), criteria(pair("id", "507f1f77bcf86cd799439011", Condition.eq)));
		assertEquals(new Document("property._id", "abc"), criteria(pair("property.id", "abc", Condition.eq)));
		assertEquals(
			new Document("retryCount", 3),
			criteria(pair("retryCount", 3, Condition.eq))
		);
		assertEquals(
			new Document("retry_count", 3),
			criteria(pair("retry_count", 3, Condition.eq))
		);
		assertEquals(
			new Document("meta.referenceId", "ref-a"),
			criteria(pair("meta.referenceId", "ref-a", Condition.eq))
		);
	}

	@Test
	void lookupRawStagesAndPhysicalPathsRemainUnchanged() {
		LookupSpec spec = LookupSpec.builder()
			.as("children")
			.localField("id")
			.foreignField("owner.id")
			.bindConditionFields("leftJoinKey", Condition.eq, "rightJoinKey")
			.rawStage(Aggregates.match(Filters.eq("childStatus", "ACTIVE")))
			.sort(Sorts.descending("createdAt"))
			.limit(2)
			.outerStage(Aggregates.match(Filters.eq("children.visible", true)))
			.build();

		assertEquals("children", spec.getAs());
		assertEquals("_id", spec.getLocalField());
		assertEquals("owner._id", spec.getForeignField());
		assertEquals("$leftJoinKey", spec.getLetDoc().get("v0"));
		assertTrue(spec.getPipelineDocs().stream().map(MongoBsonSupport::toDocument).anyMatch(stage -> stage.containsKey("$match")));
		assertTrue(spec.getPipelineDocs().stream().map(MongoBsonSupport::toDocument).anyMatch(stage -> stage.containsKey("$sort")));
		assertTrue(spec.getPipelineDocs().stream().map(MongoBsonSupport::toDocument).anyMatch(stage -> stage.containsKey("$limit")));
		assertEquals(
			new Document("children.visible", true),
			(Document) MongoBsonSupport.toDocument(spec.getOuterStages().getFirst()).get("$match")
		);
	}

	@Test
	void executionContextProvidesDefaultDriverPojoCodecReadWrite() {
		MongoExecutionContext context = new MongoExecutionContext() {
			@Override
			public Mono<MongoDatabase> getDatabase() { return Mono.empty(); }

			@Override
			public Mono<ClientSession> startSession() { return Mono.empty(); }

			@Override
			public String getCollectionName(Class<?> entityClass) { return entityClass.getSimpleName(); }

			@Override
			public Object getId(Object entity) {
				Object id = MongoIdFieldResolver.getIdValue(entity);
				return id instanceof String stringId && ObjectId.isValid(stringId) ? new ObjectId(stringId) : id;
			}

			@Override
			public void setId(Object entity, Object id) { MongoIdFieldResolver.setIdValue(entity, id); }

			@Override
			public Object getNative() { return null; }
		};

		DriverMappedEntity entity = new DriverMappedEntity();
		entity.setId("507f1f77bcf86cd799439011");
		entity.setAccountName("default-codec");
		entity.setStatus("READY");

		Document written = context.write(entity);
		assertInstanceOf(ObjectId.class, written.get("_id"));
		assertEquals("default-codec", written.getString("account_name"));

		DriverMappedEntity read = context.read(DriverMappedEntity.class, written);
		assertEquals(entity.getId(), read.getId());
		assertEquals(entity.getAccountName(), read.getAccountName());
	}

	@Test
	void driverContextKeepsEntityCodecResponsibilitySeparateFromQueryFields() {
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
		MongoClient client = proxy(MongoClient.class, (method, args) -> defaultValue(method.getReturnType()));
		DriverMongoExecutionContext context = new DriverMongoExecutionContext(client, database);

		DriverMappedEntity entity = new DriverMappedEntity();
		entity.setId("507f1f77bcf86cd799439011");
		entity.setAccountName("alpha");
		entity.setStatus("READY");

		Document written = context.write(entity);
		assertInstanceOf(ObjectId.class, written.get("_id"));
		assertEquals("alpha", written.getString("account_name"));
		assertFalse(written.containsKey("accountName"));
		assertEquals(new ObjectId(entity.getId()), context.getId(entity));

		DriverMappedEntity read = context.read(DriverMappedEntity.class, written);
		assertEquals(entity.getId(), read.getId());
		assertEquals("alpha", read.getAccountName());
		assertEquals("READY", read.getStatus());
	}

	@Test
	void fieldsPairRenderingKeepsLegacyConditionSemantics() {
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
				new Document(
					"$elemMatch",
					new Document("status", "READY").append("amount", new Document("$gt", 0L))
				)
			),
			criteria(pair("items", List.of(pair("status", "READY"), "ignored", pair("amount", 0L, Condition.gt)), Condition.elemMatch))
		);
		assertEquals(
			new Document(
				"items",
				new Document(
					"$elemMatch",
					new Document("score", new Document("$gte", 80).append("$lte", 100))
				)
			),
			criteria(
				pair(
					"items",
					List.of(pair("score", 80, Condition.gte), pair("score", 100, Condition.lte)),
					Condition.elemMatch
				)
			)
		);
	}

	@Test
	void fieldsPairLogicalCombinationKeepsOnlyRequiredLogicalOperators() {
		assertEquals(
			new Document("status", "READY").append("amount", new Document("$gt", 0L)),
			MongoBsonSupport.toDocument(
				FieldsPairBsonSupport.combine(
					List.of(
						FieldsPairBsonSupport.createSingleCriteria(pair("status", "READY")),
						FieldsPairBsonSupport.createSingleCriteria(pair("amount", 0L, Condition.gt))
					),
					"AND"
				)
			)
		);
		assertEquals(
			new Document("retryCount", new Document("$gte", 1).append("$lte", 3)),
			MongoBsonSupport.toDocument(
				FieldsPairBsonSupport.combine(
					List.of(
						FieldsPairBsonSupport.createSingleCriteria(pair("retryCount", 1, Condition.gte)),
						FieldsPairBsonSupport.createSingleCriteria(pair("retryCount", 3, Condition.lte))
					),
					"AND"
				)
			)
		);
		assertEquals(
			new Document(
				"$and",
				List.of(new Document("status", "READY"), new Document("status", "DONE"))
			),
			MongoBsonSupport.toDocument(
				FieldsPairBsonSupport.combine(
					List.of(
						FieldsPairBsonSupport.createSingleCriteria(pair("status", "READY")),
						FieldsPairBsonSupport.createSingleCriteria(pair("status", "DONE"))
					),
					"AND"
				)
			)
		);
		assertEquals(
			new Document("$nor", List.of(new Document("status", "READY"))),
			MongoBsonSupport.toDocument(
				FieldsPairBsonSupport.combine(
					List.of(FieldsPairBsonSupport.createSingleCriteria(pair("status", "READY"))),
					"NOR"
				)
			)
		);
	}

	private static Document criteria(com.byeolnaerim.mongodsl.criteria.FieldsPair<?, ?> pair) {
		return MongoBsonSupport.toDocument(FieldsPairBsonSupport.createSingleCriteria(pair));
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
