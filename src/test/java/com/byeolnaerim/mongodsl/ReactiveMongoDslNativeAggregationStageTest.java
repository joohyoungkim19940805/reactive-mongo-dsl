package com.byeolnaerim.mongodsl;

import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FusionPipeline;
import com.mongodb.client.model.ScoreNormalization;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchOptions;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.VectorSearchNestedOptions;
import com.mongodb.client.model.search.VectorSearchOptions;
import com.mongodb.client.model.search.VectorSearchScoreMode;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

// 실제 DB 없이 MongoDB Driver native Bson stage가 DSL pipeline에 그대로 보존되는지 검증한다.
class ReactiveMongoDslNativeAggregationStageTest {

	// 일반 aggregation()이 Driver Bson/Document를 호출 순서 그대로 root pipeline으로 유지하는지 검증한다.
	@Test
	void rootAggregationPreservesDriverStagesInOrder() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Bson score = Aggregates.score( "$rating" );
		Document projection = new Document( "$project", new Document( "rating", 1 ) );

		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.aggregation()
			.stage( score )
			.stages( projection )
			.preview()
			.block();

		assertNotNull( preview );
		assertEquals( "aggregate", preview.getString( "operation" ) );
		assertEquals(
			List.of( MongoBsonSupport.toDocument( score ), projection ),
			preview.getList( "pipeline", Document.class )
		);

	}

	// 5.10의 $scoreFusion을 DSL이 재구현하지 않고 첫 root stage Bson으로 그대로 수용하는지 검증한다.
	@Test
	void rootAggregationAcceptsMongoDriverScoreFusionAsFirstStage() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Bson scoreFusion = Aggregates
			.scoreFusion(
				List.of(
					FusionPipeline
						.of(
							"text",
							Aggregates
								.search(
									SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ),
									SearchOptions.searchOptions().index( "search-index" )
								)
						),
					FusionPipeline
						.of(
							"vector",
							Aggregates
								.vectorSearch(
									SearchPath.fieldPath( "embedding" ),
									List.of( 0.1D, 0.2D, 0.3D ),
									"vector-index",
									10L,
									VectorSearchOptions.exactVectorSearchOptions()
								)
						)
				),
				ScoreNormalization.SIGMOID
			);

		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.aggregation()
			.stage( scoreFusion )
			.preview()
			.block();

		assertNotNull( preview );
		List<Document> pipeline = preview.getList( "pipeline", Document.class );
		assertEquals( 1, pipeline.size() );
		assertEquals( MongoBsonSupport.toDocument( scoreFusion ), pipeline.getFirst() );
		assertTrue( pipeline.getFirst().containsKey( "$scoreFusion" ) );

	}

	// Search/Vector의 stage()가 각 mandatory root stage 직후에 Driver stage를 삽입하는지 검증한다.
	@Test
	void searchAndVectorStagesAreInsertedImmediatelyAfterMandatoryRootStage() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Bson score = Aggregates.score( "$rating" );

		Document searchPreview = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.stage( score )
			.findAll()
			.preview()
			.block();

		assertNotNull( searchPreview );
		List<Document> searchPipeline = searchPreview.getList( "pipeline", Document.class );
		assertEquals( 2, searchPipeline.size() );
		assertTrue( searchPipeline.get( 0 ).containsKey( "$search" ) );
		assertEquals( MongoBsonSupport.toDocument( score ), searchPipeline.get( 1 ) );

		Document vectorPreview = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "embedding" )
			.queryVector( new double[] {
				0.1D, 0.2D, 0.3D
			} )
			.limit( 3 )
			.exact()
			.stage( score )
			.findAll()
			.preview()
			.block();

		assertNotNull( vectorPreview );
		List<Document> vectorPipeline = vectorPreview.getList( "pipeline", Document.class );
		assertEquals( 2, vectorPipeline.size() );
		assertTrue( vectorPipeline.get( 0 ).containsKey( "$vectorSearch" ) );
		assertEquals( MongoBsonSupport.toDocument( score ), vectorPipeline.get( 1 ) );

	}

	// 5.10 nested vector search의 핵심 옵션은 기존 FieldsPair DSL과 자연스럽게 연결되는 convenience API로 제공한다.
	@Test
	void vectorConvenienceApiBuildsMongoDriver510NestedVectorOptions() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "chunks.embedding" )
			.queryVector( new double[] {
				0.1D, 0.2D, 0.3D
			} )
			.limit( 3 )
			.exact()
			.parentFilterFields( pair( "tenantId", "tenant-a" ) )
			.nestedScoreMode( VectorSearchScoreMode.AVG )
			.findAll()
			.preview()
			.block();

		assertNotNull( preview );
		Document vectorStage = preview
			.getList( "pipeline", Document.class )
			.getFirst()
			.get( "$vectorSearch", Document.class );

		assertEquals( new Document( "tenantId", "tenant-a" ), vectorStage.get( "parentFilter", Document.class ) );
		assertEquals( new Document( "scoreMode", "avg" ), vectorStage.get( "nestedOptions", Document.class ) );

	}

	// driverOptions()는 convenience API가 아직 모르는 현재/미래 Driver 옵션을 위한 escape hatch로 계속 유지한다.
	@Test
	void vectorDriverOptionsExposeMongoDriver510NestedVectorOptionsDirectly() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "chunks.embedding" )
			.queryVector( new double[] {
				0.1D, 0.2D, 0.3D
			} )
			.limit( 3 )
			.exact()
			.driverOptions(
				options -> options
					.parentFilter( Filters.eq( "tenantId", "tenant-a" ) )
					.nestedOptions(
						VectorSearchNestedOptions
							.vectorSearchNestedOptions()
							.scoreMode( VectorSearchScoreMode.AVG )
					)
			)
			.findAll()
			.preview()
			.block();

		assertNotNull( preview );
		Document vectorStage = preview
			.getList( "pipeline", Document.class )
			.getFirst()
			.get( "$vectorSearch", Document.class );

		assertEquals( new Document( "tenantId", "tenant-a" ), vectorStage.get( "parentFilter", Document.class ) );
		assertEquals( new Document( "scoreMode", "avg" ), vectorStage.get( "nestedOptions", Document.class ) );

	}

	// 새 escape hatch를 사용하지 않는 기존 alpha.4 Search/Vector 호출은 기존 pipeline shape를 유지하는지 검증한다.
	@Test
	void existingSearchAndVectorCallsKeepLegacyPipelineShapeWhenNoStageIsAdded() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );

		Document searchPreview = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.findAll()
			.preview()
			.block();

		Document vectorPreview = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "embedding" )
			.queryVector( new double[] {
				0.1D, 0.2D, 0.3D
			} )
			.limit( 3 )
			.exact()
			.findAll()
			.preview()
			.block();

		assertNotNull( searchPreview );
		assertNotNull( vectorPreview );
		assertEquals( 1, searchPreview.getList( "pipeline", Document.class ).size() );
		assertEquals( 1, vectorPreview.getList( "pipeline", Document.class ).size() );

	}

	// stage API는 null stage를 조용히 무시하지 않고 즉시 거부하는지 검증한다.
	@Test
	void nativeStageEntryPointsRejectNull() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );

		assertThrows(
			NullPointerException.class,
			() -> dsl.executeEntity( TestEntity.class, "test" ).aggregation().stage( null )
		);
		assertThrows(
			NullPointerException.class,
			() -> dsl
				.executeEntity( TestEntity.class, "test" )
				.search( "search-index" )
				.stage( null )
		);
		assertThrows(
			NullPointerException.class,
			() -> dsl
				.executeEntity( TestEntity.class, "test" )
				.vectorSearch( "vector-index" )
				.stage( null )
		);
		assertThrows(
			NullPointerException.class,
			() -> dsl
				.executeEntity( TestEntity.class, "test" )
				.vectorSearch( "vector-index" )
				.nestedScoreMode( null )
		);

	}

	private static MongoExecutionContext context() {

		return new MongoExecutionContext() {

			@Override
			public Mono<MongoDatabase> getDatabase() { return Mono.empty(); }

			@Override
			public Mono<ClientSession> startSession() { return Mono.empty(); }

			@Override
			public String getCollectionName(
				Class<?> entityClass
			) {

				return entityClass.getSimpleName();

			}

			@Override
			public Object getId(
				Object entity
			) {

				return null;

			}

			@Override
			public Object getNative() { return null; }

		};

	}

	private static final class TestEntity {}

}
