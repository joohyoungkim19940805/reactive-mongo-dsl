package com.byeolnaerim.mongodsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

// 실제 DB에 접속하지 않고 preview()가 현재 DSL/Driver 표현을 진단용 Document로 반환하는지 검증한다.
class ReactiveMongoDslPreviewTest {

	// findAll().preview()가 DB를 resolve하지 않고 현재 find DSL 상태를 그대로 렌더링하는지 검증한다.
	@Test
	void findPreviewRendersCurrentDslStateWithoutDatabaseAccess() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.findAll()
			.sorts( sort -> sort.desc( SearchField.CREATED_AT ) )
			.paging( 1, 5 )
			.preview()
			.block();

		assertNotNull( preview );
		assertEquals( "find", preview.getString( "operation" ) );
		assertEquals( "TestEntity", preview.getString( "collection" ) );
		assertEquals( new Document(), preview.get( "filter", Document.class ) );
		assertEquals( new Document( "created_at", -1 ), preview.get( "sort", Document.class ) );
		assertEquals( 5L, preview.getLong( "skip" ) );
		assertEquals( 5, preview.getInteger( "limit" ) );

	}

	// Search/Vector preview()가 실행 없이 실제 Driver aggregation stage 목록을 pipeline으로 노출하는지 검증한다.
	@Test
	void searchAndVectorPreviewRenderAggregationPipelinesWithoutDatabaseAccess() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );

		Document searchPreview = dsl
			.executeEntity( TestEntity.class, "test" )
			.search( "search-index" )
			.operator( SearchOperator.text( SearchPath.fieldPath( "title" ), "mongodb" ) )
			.findAll()
			.preview()
			.block();

		assertNotNull( searchPreview );
		assertEquals( "aggregate", searchPreview.getString( "operation" ) );
		assertEquals( "TestEntity", searchPreview.getString( "collection" ) );
		assertTrue( searchPreview.getList( "pipeline", Document.class ).get( 0 ).containsKey( "$search" ) );

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

		assertNotNull( vectorPreview );
		assertEquals( "aggregate", vectorPreview.getString( "operation" ) );
		assertTrue( vectorPreview.getList( "pipeline", Document.class ).get( 0 ).containsKey( "$vectorSearch" ) );

	}

	// distinct preview()가 DB 실행 없이 필드명 정규화와 현재 criteria filter를 그대로 보여주는지 검증한다.
	@Test
	void distinctPreviewNormalizesFieldNameWithoutDatabaseAccess() {

		ReactiveMongoDsl<String> dsl = new ReactiveMongoDsl<>( ignored -> context() );
		Document preview = dsl
			.executeEntity( TestEntity.class, "test" )
			.fields()
			.end()
			.distinct( SearchField.TITLE, String.class )
			.preview()
			.block();

		assertNotNull( preview );
		assertEquals( "distinct", preview.getString( "operation" ) );
		assertEquals( "TestEntity", preview.getString( "collection" ) );
		assertEquals( "search_title", preview.getString( "field" ) );
		assertEquals( new Document(), preview.get( "filter", Document.class ) );
		assertEquals( String.class.getName(), preview.getString( "resultClass" ) );

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
