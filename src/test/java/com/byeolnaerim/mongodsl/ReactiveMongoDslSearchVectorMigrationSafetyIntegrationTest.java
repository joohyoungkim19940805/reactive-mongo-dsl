package com.byeolnaerim.mongodsl;


import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.byeolnaerim.mongodsl.search.SearchMatchCriteria;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.SearchIndexDefinition;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.VectorSearchIndexFields;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactiveMongoDslSearchVectorMigrationSafetyIntegrationTest {

	private static final String COLLECTION_NAME = "search_vector_live";

	private static final String SEARCH_INDEX_NAME = "rmdsl_search_text";

	private static final String VECTOR_INDEX_NAME = "rmdsl_vector_embedding";

	private static final String CONTENT_FIELD = "content";

	private static final String CATEGORY_FIELD = "category";

	private static final String EMBEDDING_FIELD = "embedding";

	private static final String PRIMARY_FIXTURE_ID = "rmdsl-mongodb-search-vector";

	private static final List<String> FIXTURE_IDS = List
		.of(
			PRIMARY_FIXTURE_ID,
			"rmdsl-mongodb-driver",
			"rmdsl-reactor"
		);

	private static final List<Document> FIXTURES = List
		.of(
			new Document( "_id", PRIMARY_FIXTURE_ID )
				.append( CONTENT_FIELD, "MongoDB reactive integration search fixture" )
				.append( CATEGORY_FIELD, "database" )
				.append( EMBEDDING_FIELD, List.of( 1.0D, 0.0D, 0.0D ) ),
			new Document( "_id", "rmdsl-mongodb-driver" )
				.append( CONTENT_FIELD, "MongoDB Java driver reactive streams fixture" )
				.append( CATEGORY_FIELD, "database" )
				.append( EMBEDDING_FIELD, List.of( 0.8D, 0.2D, 0.0D ) ),
			new Document( "_id", "rmdsl-reactor" )
				.append( CONTENT_FIELD, "Project Reactor asynchronous pipeline fixture" )
				.append( CATEGORY_FIELD, "reactive" )
				.append( EMBEDDING_FIELD, List.of( 0.0D, 1.0D, 0.0D ) )
		);

	private static final Duration QUERY_TIMEOUT = Duration.ofSeconds( 30 );

	private static final Duration INDEX_READY_TIMEOUT = Duration.ofMinutes( 10 );

	private static final Duration INDEX_POLL_INTERVAL = Duration.ofSeconds( 2 );

	private MongoClient mongoClient;

	private MongoDatabase mongoDatabase;

	private MongoCollection<Document> collection;

	private ReactiveMongoDsl<TestMongo> mongoDsl;

	@BeforeAll
	void connectAndInitializeSearchIndexes() {

		TestEnvironment environment = TestEnvironment.load();

		if (! environment.isComplete()) {

			if (Boolean.getBoolean( "mongo.integration.required" )) {
				throw new IllegalStateException(
					"TEST_CLUSTER_NAME, TEST_USERNAME, TEST_PASSWORD, and TEST_URL are required for mongoMigrationTest"
				);

			}

			org.junit.jupiter.api.Assumptions.assumeTrue( false, "MongoDB TEST_* environment variables are not configured" );

		}

		mongoClient = MongoClients.create( environment.connectionString() );
		mongoDatabase = mongoClient.getDatabase( environment.searchVectorDatabaseName() );

		if (! Boolean.TRUE
			.equals(
				Flux.from( mongoDatabase.listCollectionNames() ).any( COLLECTION_NAME::equals ).block( QUERY_TIMEOUT )
			)) {
			Mono.from( mongoDatabase.createCollection( COLLECTION_NAME ) ).block( QUERY_TIMEOUT );

		}

		collection = mongoDatabase.getCollection( COLLECTION_NAME );

		List<Document> currentIndexes = listSearchIndexes();
		Set<String> currentIndexNames = currentIndexes
			.stream()
			.map( index -> index.getString( "name" ) )
			.filter( Objects::nonNull )
			.collect( Collectors.toSet() );

		List<Document> fixtureDocuments = Objects
			.requireNonNull(
				Flux.from( collection.find( Filters.in( "_id", FIXTURE_IDS ) ) ).collectList().block( QUERY_TIMEOUT ),
				"fixture documents"
			);
		long totalDocumentCount = Objects
			.requireNonNull(
				Mono.from( collection.countDocuments() ).block( QUERY_TIMEOUT ),
				"total document count"
			);

		if (totalDocumentCount != fixtureDocuments.size()) {
			throw new IllegalStateException(
				"The dedicated Search/Vector test collection contains documents outside the three fixed fixtures. " + "The test refuses to index or delete unexpected data. Use a fresh TEST_SEARCH_VECTOR_DATABASE."
			);

		}

		if (fixtureDocuments.size() != FIXTURES.size() || ! fixtureDocuments.containsAll( FIXTURES )) {

			if (! currentIndexNames.isEmpty()) {
				throw new IllegalStateException(
					"Search/vector indexes already exist but the persistent test fixtures are incomplete or changed. " + "The test intentionally refuses to write after indexing. Drop the dedicated test collection/indexes " + "or point TEST_SEARCH_VECTOR_DATABASE at a fresh database and run again."
				);

			}

			if (! fixtureDocuments.isEmpty()) {
				Mono.from( collection.deleteMany( Filters.in( "_id", FIXTURE_IDS ) ) ).block( QUERY_TIMEOUT );

			}

			Mono.from( collection.insertMany( FIXTURES ) ).block( QUERY_TIMEOUT );

		}

		List<SearchIndexModel> missingIndexes = new ArrayList<>();

		if (! currentIndexNames.contains( SEARCH_INDEX_NAME )) {
			missingIndexes
				.add(
					new SearchIndexModel(
						SEARCH_INDEX_NAME,
						new Document(
							"mappings",
							new Document( "dynamic", false )
								.append(
									"fields",
									new Document( CONTENT_FIELD, new Document( "type", "string" ) )
								)
						)
					)
				);

		}

		if (! currentIndexNames.contains( VECTOR_INDEX_NAME )) {
			missingIndexes
				.add(
					new SearchIndexModel(
						VECTOR_INDEX_NAME,
						SearchIndexDefinition
							.vectorSearch(
								VectorSearchIndexFields
									.vectorField( EMBEDDING_FIELD )
									.numDimensions( 3 )
									.similarity( "cosine" ),
								VectorSearchIndexFields.filterField( CATEGORY_FIELD )
							)
					)
				);

		}

		if (! missingIndexes.isEmpty()) {
			Flux.from( collection.createSearchIndexes( missingIndexes ) ).collectList().block( INDEX_READY_TIMEOUT );

		}

		awaitIndexReady( SEARCH_INDEX_NAME );
		awaitIndexReady( VECTOR_INDEX_NAME );

		DriverMongoExecutionContext context = new DriverMongoExecutionContext(
			mongoClient,
			mongoDatabase,
			type -> COLLECTION_NAME
		);
		mongoDsl = new ReactiveMongoDsl<>( key -> context );

	}

	@AfterAll
	void disconnect() {

		if (mongoClient != null) {
			mongoClient.close();

		}

	}

	@Test
	void atlasSearchExecutesAgainstReadySearchIndex() {

		List<Document> results = Objects
			.requireNonNull(
				mongoDsl
					.executeEntity( Document.class, TestMongo.LIVE )
					.search( SEARCH_INDEX_NAME )
					.text(
						text -> text
							.path( CONTENT_FIELD )
							.query( "mongodb integration" )
							.matchCriteria( SearchMatchCriteria.ALL )
					)
					.addFieldsScore()
					.findAll()
					.execute()
					.collectList()
					.block( QUERY_TIMEOUT ),
				"Atlas Search results"
			);

		assertFalse( results.isEmpty() );
		assertEquals( PRIMARY_FIXTURE_ID, results.getFirst().getString( "_id" ) );
		assertNotNull( results.getFirst().get( "score", Number.class ) );
		assertTrue( results.getFirst().get( "score", Number.class ).doubleValue() > 0.0D );

	}

	@Test
	void vectorSearchExecutesAgainstReadyVectorIndex() {

		List<Document> results = Objects
			.requireNonNull(
				mongoDsl
					.executeEntity( Document.class, TestMongo.LIVE )
					.vectorSearch( VECTOR_INDEX_NAME )
					.path( EMBEDDING_FIELD )
					.queryVector( new double[] {
						1.0D, 0.0D, 0.0D
					} )
					.exact()
					.limit( 2 )
					.filterFields( pair( CATEGORY_FIELD, "database" ) )
					.addFieldsVectorSearchScore()
					.findAll()
					.execute()
					.collectList()
					.block( QUERY_TIMEOUT ),
				"Vector Search results"
			);

		assertFalse( results.isEmpty() );
		assertEquals( PRIMARY_FIXTURE_ID, results.getFirst().getString( "_id" ) );
		assertNotNull( results.getFirst().get( "vectorSearchScore", Number.class ) );
		assertTrue( results.getFirst().get( "vectorSearchScore", Number.class ).doubleValue() > 0.0D );

	}

	private List<Document> listSearchIndexes() {

		return Objects
			.requireNonNull(
				Flux.from( collection.listSearchIndexes() ).collectList().block( QUERY_TIMEOUT ),
				"search indexes"
			);

	}

	private void awaitIndexReady(
		String indexName
	) {

		long deadline = System.nanoTime() + INDEX_READY_TIMEOUT.toNanos();

		while (System.nanoTime() < deadline) {
			Document index = Flux
				.from( collection.listSearchIndexes() )
				.filter( value -> indexName.equals( value.getString( "name" ) ) )
				.next()
				.block( QUERY_TIMEOUT );

			if (index != null) {
				String status = index.getString( "status" );

				if ("FAILED".equals( status ) || "STALE".equals( status ) || "DELETING".equals( status ) || "DOES_NOT_EXIST".equals( status )) {
					throw new IllegalStateException(
						"Search index '" + indexName + "' entered status " + status + ": " + index.toJson()
					);

				}

				if ("READY".equals( status ) && Boolean.TRUE.equals( index.getBoolean( "queryable" ) )) { return; }

			}

			Mono.delay( INDEX_POLL_INTERVAL ).block();

		}

		throw new IllegalStateException(
			"Timed out waiting for Search index '" + indexName + "' to become READY and queryable"
		);

	}

	private enum TestMongo {
		LIVE
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

			if (value.startsWith( "mongodb://" ) || value.startsWith( "mongodb+srv://" )) { return value; }

			String suffix = value.startsWith( "@" ) ? value : "@" + value;
			return "mongodb+srv://" + encode( username ) + ":" + encode( password ) + suffix;

		}

		String searchVectorDatabaseName() {

			String configured = System.getenv( "TEST_SEARCH_VECTOR_DATABASE" );

			if (configured != null && ! configured.isBlank()) { return configured.trim(); }

			String sanitized = clusterName.replaceAll( "[^A-Za-z0-9_]", "_" );

			if (sanitized.length() > 24) {
				sanitized = sanitized.substring( 0, 24 );

			}

			return "rmdsl_search_vector_" + sanitized;

		}

		private static String encode(
			String value
		) {

			return URLEncoder.encode( value, StandardCharsets.UTF_8 ).replace( "+", "%20" );

		}

	}

}
