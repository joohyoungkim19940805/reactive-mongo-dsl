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


class MongoDriverDelegationTest {

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

	@Test
	void simpleScoreConvenienceDelegatesToMongoDriverScore() {

		assertEquals(
			MongoBsonSupport.toDocument( SearchScore.boost( SearchPath.fieldPath( "popularity" ) ).undefined( 1F ) ),
			MongoBsonSupport.toDocument( SearchScoreSpec.boostByPath( "popularity", 1D ).toSearchScore() )
		);

	}

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
