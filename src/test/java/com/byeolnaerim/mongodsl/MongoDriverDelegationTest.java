package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import com.byeolnaerim.mongodsl.search.AtlasSearchOperator;
import com.byeolnaerim.mongodsl.search.SearchHighlightSpec;
import com.byeolnaerim.mongodsl.search.SearchMatchCriteria;
import com.byeolnaerim.mongodsl.search.SearchOperators;
import com.byeolnaerim.mongodsl.search.SearchPaths;
import com.byeolnaerim.mongodsl.search.SearchScoreSpec;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.search.FuzzySearchOptions;
import com.mongodb.client.model.search.SearchHighlight;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.SearchScore;
import com.mongodb.client.model.search.VectorSearchOptions;
import com.mongodb.client.model.search.VectorSearchQuery;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Mono;


// 실제 DB에 접속하지 않고 DSL이 MongoDB Driver와 동일한 BSON/stage를 생성하는지 검증한다.
// 따라서 이 클래스의 Search/Vector 테스트는 execute()가 아니라 생성된 Driver 표현을 직접 비교한다.
class MongoDriverDelegationTest {

	// text 편의 DSL이 MongoDB Driver의 text operator와 동일한 BSON을 생성하는지 검증한다.
	@Test
	void textConvenienceOperatorMatchesMongoDriverRendering() {

		Bson expected = SearchOperator
			.text( SearchPath.fieldPath( "title" ), "mongodb" )
			.fuzzy(
				FuzzySearchOptions
					.fuzzySearchOptions()
					.maxEdits( 1 )
					.prefixLength( 0 )
					.maxExpansions( 20 )
			)
			.score( SearchScore.boost( 2F ) );

		Bson actual = SearchOperators
			.text()
			.path( "title" )
			.query( "mongodb" )
			.fuzzy( 1, 0, 20 )
			.score( SearchScoreSpec.boost( 2D ) )
			.toSearchOperator();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// 경로 기반 boost score 편의 API가 Driver SearchScore와 동일하게 위임되는지 검증한다.
	@Test
	void simpleScoreConvenienceDelegatesToMongoDriverScore() {

		assertEquals(
			MongoBsonSupport.toDocument( SearchScore.boost( SearchPath.fieldPath( "popularity" ) ).undefined( 1F ) ),
			MongoBsonSupport.toDocument( SearchScoreSpec.boostByPath( "popularity", 1D ).toSearchScore() )
		);

	}

	// Search path에 Enum을 넘겼을 때 Enum.toString() 값을 실제 MongoDB 필드명으로 사용하는지 검증한다.
	@Test
	void enumSearchPathUsesToStringAsPhysicalFieldName() {

		Bson expected = SearchOperator.text( SearchPath.fieldPath( "search_title" ), "mongodb" );
		Bson actual = SearchOperators
			.text()
			.path( SearchField.TITLE )
			.query( "mongodb" )
			.toSearchOperator();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// 숫자 range 편의 DSL이 Driver의 numberRange와 동일한 BSON을 생성하는지 검증한다.
	@Test
	void numericRangeConvenienceOperatorMatchesMongoDriverRendering() {

		Bson expected = SearchOperator
			.numberRange( SearchPath.fieldPath( "price" ) )
			.gteLt( 10, 20 );

		Bson actual = SearchOperators
			.range()
			.path( "price" )
			.gte( 10 )
			.lt( 20 )
			.toSearchOperator();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// Driver typed API에 없는 text.matchCriteria 기능을 DSL이 서버 문법 그대로 보존하는지 검증한다.
	@Test
	void textMatchCriteriaKeepsMongoSearchFeatureMissingFromDriverTypedApi() {

		Bson actual = SearchOperators
			.text()
			.path( "title" )
			.query( "mongodb reactive" )
			.matchCriteria( SearchMatchCriteria.ALL )
			.toSearchOperator();

		assertEquals(
			new Document(
				"text",
				new Document( "query", "mongodb reactive" )
					.append( "path", "title" )
					.append( "matchCriteria", "all" )
			),
			MongoBsonSupport.toDocument( actual )
		);

	}

	// Driver typed API가 직접 지원하지 않는 문자열 range를 DSL이 서버 문법으로 유지하는지 검증한다.
	@Test
	void stringRangeKeepsMongoSearchFeatureMissingFromDriverTypedApi() {

		Bson actual = SearchOperators
			.range()
			.path( "code" )
			.gte( "A" )
			.lt( "M" )
			.toSearchOperator();

		assertEquals(
			new Document(
				"range",
				new Document( "path", "code" )
					.append( "gte", "A" )
					.append( "lt", "M" )
			),
			MongoBsonSupport.toDocument( actual )
		);

	}

	// SearchHighlightSpec 편의 빌더가 Driver SearchHighlight와 동일한 BSON을 생성하는지 검증한다.
	@Test
	void highlightConvenienceSpecMatchesMongoDriverRendering() {

		Bson expected = SearchHighlight
			.paths( SearchPath.fieldPath( "title" ) )
			.maxCharsToExamine( 1000 )
			.maxNumPassages( 3 );

		Bson actual = SearchHighlightSpec
			.builder()
			.path( "title" )
			.maxCharsToExamine( 1000 )
			.maxNumPassages( 3 )
			.build()
			.toSearchHighlight();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// Search builder가 DSL wrapper뿐 아니라 Driver SearchHighlight를 직접 받아 동일한 search stage를 만드는지 검증한다.
	@Test
	void searchBuilderAcceptsDriverNativeHighlightDirectly() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.highlight( SearchHighlight.paths( SearchPath.fieldPath( "title" ) ) );

		Method buildSearchStage = builder.getClass().getDeclaredMethod( "buildSearchStage", boolean.class );
		buildSearchStage.setAccessible( true );
		Bson actual = (Bson) buildSearchStage.invoke( builder, false );
		Bson expected = Aggregates
			.search(
				SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ),
				com.mongodb.client.model.search.SearchOptions
					.searchOptions()
					.index( "search-index" )
					.highlight( SearchHighlight.paths( SearchPath.fieldPath( "title" ) ) )
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// 기존 AtlasSearchOperator 확장 구현이 Driver SearchOperator로 계속 브리지되는지 호환성을 검증한다.
	@Test
	void legacyCustomSearchOperatorStillBridgesToDriverOperator() {

		AtlasSearchOperator custom = new AtlasSearchOperator() {

			@Override
			public String operatorName() {

				return "queryString";

			}

			@Override
			public Document toDocument() {

				return new Document(
					"queryString",
					new Document( "defaultPath", "title" ).append( "query", "mongodb" )
				);

			}

		};

		assertEquals( custom.toDocument(), MongoBsonSupport.toDocument( custom.toSearchOperator() ) );

	}

	// Search builder의 operator/options가 Driver Aggregates.search와 동일한 stage로 조립되는지 검증한다.
	@Test
	void searchStageUsesDriverSearchStageAndOptions() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.driverOptions( options -> options.returnStoredSource( true ) );

		Method buildSearchStage = builder.getClass().getDeclaredMethod( "buildSearchStage", boolean.class );
		buildSearchStage.setAccessible( true );
		Bson actual = (Bson) buildSearchStage.invoke( builder, false );
		Bson expected = Aggregates
			.search(
				SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ),
				com.mongodb.client.model.search.SearchOptions
					.searchOptions()
					.index( "search-index" )
					.returnStoredSource( true )
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// compound 편의 API에서도 Enum path의 toString() 물리 필드명이 유지되는지 검증한다.
	@Test
	void compoundConvenienceEnumPathUsesToStringAsPhysicalFieldName() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.compound( compound -> compound.mustText( SearchField.TITLE, text -> text.query( "mongodb" ) ) );

		Method buildSearchStage = builder.getClass().getDeclaredMethod( "buildSearchStage", boolean.class );
		buildSearchStage.setAccessible( true );
		Bson actual = (Bson) buildSearchStage.invoke( builder, false );
		Bson expected = Aggregates
			.search(
				SearchOperator
					.compound()
					.must(
						List.of( SearchOperator.text( SearchPath.fieldPath( "search_title" ), "mongodb" ) )
					),
				com.mongodb.client.model.search.SearchOptions.searchOptions().index( "search-index" )
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// Search field sort는 Driver Sorts를 사용하면서 searchScore 정렬 편의 기능도 함께 보존하는지 검증한다.
	@Test
	void searchSortUsesDriverSortForFieldsAndKeepsScoreConvenience() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.sorts( spec -> spec.asc( SearchField.CREATED_AT ) )
			.scoreDesc();

		Method buildSearchStage = builder.getClass().getDeclaredMethod( "buildSearchStage", boolean.class );
		buildSearchStage.setAccessible( true );
		Bson actual = (Bson) buildSearchStage.invoke( builder, false );
		Bson expected = Aggregates
			.search(
				SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ),
				com.mongodb.client.model.search.SearchOptions
					.searchOptions()
					.index( "search-index" )
					.option(
						"sort",
						new Document( "created_at", 1 )
							.append( "score", new Document( "$meta", "searchScore" ) )
					)
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// score 정렬과 SortSpec을 섞었을 때 호출 순서가 최종 Search sort 우선순위에 그대로 반영되는지 검증한다.
	@Test
	void searchScoreAndSortSpecPreserveCallPriority() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.scoreDesc()
			.sorts( spec -> spec.asc( SearchField.CREATED_AT ).desc( "id" ) );

		Method buildSearchStage = builder.getClass().getDeclaredMethod( "buildSearchStage", boolean.class );
		buildSearchStage.setAccessible( true );
		Bson actual = (Bson) buildSearchStage.invoke( builder, false );
		Bson expected = Aggregates
			.search(
				SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ),
				com.mongodb.client.model.search.SearchOptions
					.searchOptions()
					.index( "search-index" )
					.option(
						"sort",
						new Document( "score", new Document( "$meta", "searchScore" ) )
							.append( "created_at", 1 )
							.append( "_id", -1 )
					)
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// findAll의 SortSpec이 지정 순서를 보존하고 id/Enum 필드명 정규화 규칙을 공통으로 사용하는지 검증한다.
	@Test
	void findAllSortSpecPreservesOrderAndUsesSharedFieldNormalization() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.findAll()
			.sorts(
				spec -> spec
					.desc( SearchField.CREATED_AT )
					.asc( "id" )
			);

		java.lang.reflect.Field sortField = builder.getClass().getDeclaredField( "sort" );
		sortField.setAccessible( true );

		assertEquals(
			MongoBsonSupport
				.toDocument(
					Sorts.orderBy( Sorts.descending( "created_at" ), Sorts.ascending( "_id" ) )
				),
			MongoBsonSupport.toDocument( (Bson) sortField.get( builder ) )
		);

	}

	// readPreference/isAllowDiskUse 같은 공통 옵션 호출 후에도 동일 concrete builder가 반환되어 전용 DSL 체이닝이 유지되는지 검증한다.
	@Test
	void commonQueryOptionsKeepConcreteBuilderFluency() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );

		var findAll = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.findAll();

		assertSame(
			findAll,
			findAll
				.readPreference( ReadPreference.secondaryPreferred() )
				.isAllowDiskUse( true )
				.sorts( spec -> spec.asc( "id" ) )
		);

		var find = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.find();

		assertSame(
			find,
			find
				.readPreference( ReadPreference.secondaryPreferred() )
				.isAllowDiskUse( true )
				.sorts( spec -> spec.desc( "id" ) )
		);

		var count = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.count();

		assertSame(
			count,
			count
				.readPreference( ReadPreference.secondaryPreferred() )
				.isAllowDiskUse( true )
		);

		var exists = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.exists();

		assertSame(
			exists,
			exists
				.readPreference( ReadPreference.secondaryPreferred() )
				.isAllowDiskUse( true )
		);

	}

	// SortSpec sub-DSL에서 Driver Bson sort를 순서대로 추가하고 end()로 원래 query builder에 복귀하는지 검증한다.
	@Test
	void sortSpecCanWrapDriverSortsAndReturnToParentBuilder() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.findAll();

		assertSame(
			builder,
			builder
				.sorts()
				.driver( Sorts.ascending( "status" ) )
				.driver( Sorts.descending( "createdAt" ) )
				.end()
		);

		java.lang.reflect.Field sortField = builder.getClass().getDeclaredField( "sort" );
		sortField.setAccessible( true );

		assertEquals(
			MongoBsonSupport
				.toDocument(
					Sorts.orderBy( Sorts.ascending( "status" ), Sorts.descending( "createdAt" ) )
				),
			MongoBsonSupport.toDocument( (Bson) sortField.get( builder ) )
		);

	}

	// Lookup pipeline도 query와 동일한 SortSpec DSL을 사용해 정렬 순서를 보존하는지 검증한다.
	@Test
	void lookupSortAcceptsSameOrderedSortSpec() {

		LookupSpec spec = LookupSpec
			.builder()
			.sorts( sort -> sort.desc( SearchField.CREATED_AT ).asc( "id" ) )
			.build();

		assertEquals(
			List
				.of(
					MongoBsonSupport
						.toDocument(
							Aggregates
								.sort(
									Sorts.orderBy( Sorts.descending( "created_at" ), Sorts.ascending( "_id" ) )
								)
						)
				),
			spec.getPipelineDocs().stream().map( MongoBsonSupport::toDocument ).toList()
		);

	}

	// 일반 문자열의 *를 자동 wildcard로 해석하지 않고 SearchPaths.wildcard를 통한 명시적 wildcard만 허용하는지 검증한다.
	@Test
	void wildcardSearchPathMustBeExplicit() {

		assertThrows(
			IllegalArgumentException.class,
			() -> SearchOperators.text().path( "title*" )
		);

		Bson expected = SearchOperator.text( SearchPath.wildcardPath( "title*" ), "mongodb" );
		Bson actual = SearchOperators
			.text()
			.path( SearchPaths.wildcard( "title*" ) )
			.query( "mongodb" )
			.toSearchOperator();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// DSL 문자열 path의 id는 _id로 정규화하되 사용자가 직접 넘긴 Driver SearchPath는 변경하지 않는지 검증한다.
	@Test
	void searchConveniencePathNormalizesIdButDriverPathIsPreserved() {

		Bson convenience = SearchOperators
			.exists()
			.path( "id" )
			.toSearchOperator();
		Bson driverNative = SearchOperators
			.exists()
			.path( SearchPath.fieldPath( "id" ) )
			.toSearchOperator();

		assertEquals(
			MongoBsonSupport.toDocument( SearchOperator.exists( SearchPath.fieldPath( "_id" ) ) ),
			MongoBsonSupport.toDocument( convenience )
		);
		assertEquals(
			MongoBsonSupport.toDocument( SearchOperator.exists( SearchPath.fieldPath( "id" ) ) ),
			MongoBsonSupport.toDocument( driverNative )
		);

	}

	// 수동 query vector의 Vector Search DSL이 Driver Aggregates.vectorSearch와 동일한 stage를 생성하는지 검증한다.
	@Test
	void vectorStageUsesDriverVectorSearchStage() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "embedding" )
			.queryVector( new double[] {
				0.1D, 0.2D, 0.3D
			} )
			.limit( 10 )
			.approximate( 100 );

		Method buildVectorSearchStage = builder.getClass().getDeclaredMethod( "buildVectorSearchStage", Optional.class );
		buildVectorSearchStage.setAccessible( true );
		Bson actual = (Bson) buildVectorSearchStage.invoke( builder, Optional.empty() );
		Bson expected = Aggregates
			.vectorSearch(
				SearchPath.fieldPath( "embedding" ),
				List.of( 0.1D, 0.2D, 0.3D ),
				"vector-index",
				10L,
				VectorSearchOptions.approximateVectorSearchOptions( 100 )
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// text query/model을 사용하는 automated embedding Vector Search가 Driver VectorSearchQuery 형태와 동일한지 검증한다.
	@Test
	void automatedEmbeddingVectorQueryUsesDriverQueryShape() throws Exception {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		var builder = dsl
			.executeEntity( TestEntity.class, "test" )
			.vectorSearch( "vector-index" )
			.path( "content" )
			.query( "mongodb reactive driver" )
			.model( "voyage-4-large" )
			.limit( 5 )
			.exact();

		Method buildVectorSearchStage = builder.getClass().getDeclaredMethod( "buildVectorSearchStage", Optional.class );
		buildVectorSearchStage.setAccessible( true );
		Bson actual = (Bson) buildVectorSearchStage.invoke( builder, Optional.empty() );
		Bson expected = Aggregates
			.vectorSearch(
				SearchPath.fieldPath( "content" ),
				VectorSearchQuery.textQuery( "mongodb reactive driver" ).model( "voyage-4-large" ),
				"vector-index",
				5L,
				VectorSearchOptions.exactVectorSearchOptions()
			);

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}

	// DSL operator에 Driver SearchScore를 직접 전달해도 변형 없이 최종 operator에 반영되는지 검증한다.
	@Test
	void driverNativeScoreCanPassThroughConvenienceOperator() {

		Bson expected = SearchOperator
			.text( SearchPath.fieldPath( "title" ), "mongodb" )
			.score( SearchScore.constant( 3F ) );
		Bson actual = SearchOperators
			.text()
			.path( "title" )
			.query( "mongodb" )
			.score( SearchScore.constant( 3F ) )
			.toSearchOperator();

		assertEquals( MongoBsonSupport.toDocument( expected ), MongoBsonSupport.toDocument( actual ) );

	}


	private static MongoExecutionContext context() {

		return new MongoExecutionContext() {

			@Override
			public Mono<MongoDatabase> getDatabase() { return Mono.empty(); }

			@Override
			public Mono<ClientSession> startSession() {

				return Mono.empty();

			}

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

	private enum SearchField {

		TITLE("search_title"), CREATED_AT("created_at");

		private final String value;

		SearchField(
					String value
		) {

			this.value = value;

		}

		@Override
		public String toString() {

			return this.value;

		}

	}

	private static final class TestEntity {}

}
