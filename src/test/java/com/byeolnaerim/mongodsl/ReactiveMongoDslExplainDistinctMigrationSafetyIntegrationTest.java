package com.byeolnaerim.mongodsl;

import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.mongodb.ExplainVerbosity;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

// preview와 분리해 실제 MongoDB Driver 실행이 필요한 explain/distinct 동작만 회귀 검증한다.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactiveMongoDslExplainDistinctMigrationSafetyIntegrationTest {

	private static final String COLLECTION = "preview_explain_distinct_entity";

	private MongoClient mongoClient;
	private MongoDatabase mongoDatabase;
	private ReactiveMongoDsl<String> mongoDsl;

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
		mongoDatabase = mongoClient.getDatabase(environment.databaseName());
		DriverMongoExecutionContext executionContext = new DriverMongoExecutionContext(
			mongoClient,
			mongoDatabase,
			type -> COLLECTION,
			ignored -> null
		);
		mongoDsl = new ReactiveMongoDsl<>(ignored -> executionContext);

		Mono.from(mongoDatabase.createCollection(COLLECTION)).block();
	}

	@BeforeEach
	void cleanCollection() {
		Mono.from(mongoDatabase.getCollection(COLLECTION).deleteMany(new Document())).block();
	}

	@AfterAll
	void disconnect() {
		if (mongoDatabase != null)
			Mono.from(mongoDatabase.drop()).onErrorResume(ignored -> Mono.empty()).block();
		if (mongoClient != null)
			mongoClient.close();
	}

	// findAll().explain(QUERY_PLANNER)가 실제 MongoDB Driver explain을 호출해 실행 계획 Document를 반환하는지 검증한다.
	@Test
	void findExplainReturnsMongoExecutionPlan() {
		Mono
			.from(mongoDatabase.getCollection(COLLECTION).insertOne(new Document("status", "READY")))
			.block();

		Document explain = mongoDsl
			.executeEntity(TestEntity.class, "test")
			.fields(pair("status", "READY"))
			.end()
			.findAll()
			.explain(ExplainVerbosity.QUERY_PLANNER)
			.block();

		assertNotNull(explain);
		assertFalse(explain.isEmpty());
	}

	// distinct()가 현재 criteria를 filter로 사용하고 Driver distinct 결과를 Flux로 반환하는지 검증한다.
	@Test
	void distinctExecutesThroughDriverWithCurrentCriteria() {
		Mono
			.from(
				mongoDatabase
					.getCollection(COLLECTION)
					.insertMany(
						List.of(
							new Document("case_year", 2026).append("status", "READY"),
							new Document("case_year", 2026).append("status", "DONE"),
							new Document("case_year", 2025).append("status", "IGNORED")
						)
					)
			)
			.block();

		List<String> statuses = mongoDsl
			.executeEntity(TestEntity.class, "test")
			.fields(pair("case_year", 2026))
			.end()
			.distinct("status", String.class)
			.execute()
			.collectList()
			.block();

		assertNotNull(statuses);
		assertEquals(Set.of("READY", "DONE"), Set.copyOf(statuses));
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

	private static final class TestEntity {}

}
