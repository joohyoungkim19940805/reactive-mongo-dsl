package com.starbearing.mongodsl;


import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;  
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.geo.Point;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.ReactiveBulkOperations;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.FacetOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.ExecuteBuilder;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.CountAggregation;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.CountExecute;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.ExistsAggregation;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.ExistsExecute;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAggregation;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAllAggregation;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindAllExecute;
import com.starbearing.mongodsl.ReactiveMongoDsl.AbstractQueryBuilder.QueryBuilderAccesser.FindExecute;
import com.starbearing.mongodsl.criteria.FieldsPair;
import com.starbearing.mongodsl.criteria.MongoCriteriaSupport;
import com.starbearing.mongodsl.criteria.FieldsPair.Condition;
import com.starbearing.mongodsl.internal.MongoIdFieldResolver;
import com.starbearing.mongodsl.lookup.LookupSpec;
import com.starbearing.mongodsl.result.PageResult;
import com.starbearing.mongodsl.result.ResultTuple;
import com.starbearing.mongodsl.spi.MongoTemplateResolver;
import com.mongodb.ReadPreference;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


public class ReactiveMongoDsl<K> {

	private final MongoTemplateResolver<K> resolver;

	private final ObjectMapper objectMapper;

	private final static ConcurrentHashMap<Class<? extends ReactiveCrudRepository<?, ?>>, Class<?>> entityClassCache = new ConcurrentHashMap<>();


	public ReactiveMongoDsl(
								MongoTemplateResolver<K> resolver
	) {

		this( resolver, JsonMapper.builder().build() );

	}

	public ReactiveMongoDsl(
								MongoTemplateResolver<K> resolver,
								ObjectMapper objectMapper
	) {

		this.resolver = resolver;
		this.objectMapper = objectMapper;

	}



	public ReactiveMongoTemplate getMongoTemplate(
		K key
	) {

		return resolver.getTemplate( key );

	}

	public TransactionalOperator getTxOperator(
		K key
	) {

		return resolver.getTxOperator( key );

	}

	public <T> Mono<T> getTxJob(
		K key, Supplier<? extends Mono<? extends T>> supplier
	) {

		var op = resolver.getTxOperator( key );
		return Mono.defer( supplier ).as( op::transactional );

	}


	// 트렌젝션 사용 방식
	// .flatMap( tuple -> {
	// var account = tuple.getT1();
	// var body = tuple.getT2();
	// mongoQueryBuilder.getMongoTemplate( null );
	// TransactionalOperator transactionalOperator = TransactionalOperator.create(
	// mongoQueryBuilder.getTxManager( MongoTemplateName.FRONT ) );
	// var equipAndUnequip = Mono.defer( () -> {
	// var equipSave = mongoQueryBuilder
	// .executeEntity( UserUnitEntity.class, MongoTemplateName.FRONT )
	// .fields(
	// pair( "accountId", account.getId() ),
	// pair( "id", body.id() )
	// )
	// .end()
	// .find()
	// .execute()
	// .flatMap( e -> {
	// e.setParentUserUnitId( parentUserUnitId );
	// return mongoQueryBuilder
	// .executeEntity( UserUnitEntity.class, MongoTemplateName.FRONT )
	// .save( e );
	//
	// } );
	// var equipDelete = mongoQueryBuilder
	// .executeEntity( UserUnitEntity.class, MongoTemplateName.FRONT )
	// .fields(
	// pair( "accountId", account.getId() ),
	// pair( "prevId", body.id() )
	// )
	// .end()
	// .find()
	// .execute()
	// .flatMap( e -> {
	// e.setParentUserUnitId( null );
	// return mongoQueryBuilder
	// .executeEntity( UserUnitEntity.class, MongoTemplateName.FRONT )
	// .save( e );
	//
	// } );
	// return Mono.zip( equipSave, equipDelete );
	//
	// } );
	// return equipAndUnequip.as( transactionalOperator::transactional );
	//
	// } )

	public enum LogicalOperator {
		AND, OR, NOR
	}

	private static class CriteriaGroup {

		LogicalOperator operator;

		List<Criteria> criteriaList;

		CriteriaGroup(
						LogicalOperator operator
		) {

			this.operator = operator;
			this.criteriaList = new ArrayList<>();

		}

	}


	public abstract class AbstractQueryBuilder<E, T extends AbstractQueryBuilder<E, T>> {

		protected Class<? extends ReactiveCrudRepository<?, ?>> repositoryClass;

		protected ReactiveMongoTemplate reactiveMongoTemplate;

		// protected Mono<Query> queryMono;

		protected Mono<Class<E>> executeClassMono;

		protected String collectionName;

		protected FieldBuilder<E> fieldBuilder = new FieldBuilder<>( LogicalOperator.AND );

		protected AbstractQueryBuilder<E, T> executeBuilder;

		public Mono<E> save(
			E e
		) {

			return reactiveMongoTemplate.save( e );

		}

		public Mono<E> save(
			Mono<E> e
		) {

			return reactiveMongoTemplate.save( e );

		}

		public Flux<E> saveAll(
			Iterable<E> entities
		) {

			return saveAll(
				Flux
					.fromIterable( entities )
			);

		}

		public Flux<E> saveAll(
			Collection<E> entities
		) {

			return saveAll(
				Flux
					.fromIterable( entities )
			);

		}

		public Flux<E> saveAll(
			Flux<E> entityFlux
		) {

			return entityFlux.flatMap( entity -> reactiveMongoTemplate.save( entity ) );

		}


		/**
		 * Iterable<E>를 받아 대량 삽입(Bulk Insert)을 수행합니다.
		 * 
		 * @param entities
		 *            저장할 엔티티 컬렉션
		 * 
		 * @return 저장된 엔티티의 Flux
		 */
		public Flux<E> saveAllBulk(
			Iterable<E> entities
		) {

			return saveAllBulk( Flux.fromIterable( entities ) );

		}

		/**
		 * Collection<E>를 받아 대량 삽입(Bulk Insert)을 수행합니다.
		 * 
		 * @param entities
		 *            저장할 엔티티 컬렉션
		 * 
		 * @return 저장된 엔티티의 Flux
		 */
		public Flux<E> saveAllBulk(
			Collection<E> entities
		) {

			return saveAllBulk( Flux.fromIterable( entities ) );

		}

		/**
		 * Flux<E> 스트림을 받아 대량 삽입(Bulk Insert)을 수행하는 핵심 메서드입니다.
		 * 스트림의 모든 엔티티를 수집하여 단일 DB 요청으로 처리합니다.
		 * 
		 * @param entityFlux
		 *            저장할 엔티티의 Flux
		 * 
		 * @return 저장된 엔티티의 Flux
		 */
		public Flux<E> saveAllBulk(
			Flux<E> entityFlux
		) {

			return entityFlux
				.collectList()
				.flatMapMany( list -> {

					if (list.isEmpty()) { return Flux.empty(); }

					return reactiveMongoTemplate.insertAll( list );

				} );

		}

		/**
		 * 엔티티 한 개를 BulkOperations에 반영하는 공통 처리
		 */
		private void applyBulkForEntity(
			E entity, Field idField, ReactiveBulkOperations bulkOps
		)
			throws IllegalAccessException {

			Object id = idField.get( entity );

			if (id == null) {
				// 신규 레코드는 insert
				bulkOps.insert( entity );
				return;

			}

			// 기존 레코드는 upsert
			Query query = Query.query( Criteria.where( "_id" ).is( id ) );

			// Document로 변환 후 _id 제거
			org.bson.Document doc = new org.bson.Document();
			reactiveMongoTemplate.getConverter().write( entity, doc );
			doc.remove( "_id" );

			if (! doc.isEmpty()) {
				org.bson.Document updateDoc = new org.bson.Document( "$set", doc );
				Update update = new BasicUpdate( updateDoc );
				bulkOps.upsert( query, update );

			}

		}

		/**
		 * Iterable<E>를 받아 대량 저장(Bulk Upsert)을 수행합니다.
		 * 
		 * @param entities
		 *            저장할 엔티티 컬렉션
		 * 
		 * @return BulkWriteResult의 Mono
		 */
		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Iterable<E> entities
		) {

			Objects.requireNonNull( entities, "entities must not be null" );

			// 비어 있으면 바로 종료
			Iterator<E> it = entities.iterator();

			if (! it.hasNext()) { return Mono.empty(); }

			// 첫 번째 엔티티로부터 타입/ID 필드 정보 추출
			E first = it.next();
			Class<?> entityClass = first.getClass();
			Field idField = MongoIdFieldResolver.findIdField( entityClass );
			idField.setAccessible( true );

			ReactiveBulkOperations bulkOps = reactiveMongoTemplate
				.bulkOps(
					BulkOperations.BulkMode.UNORDERED,
					entityClass
				);

			try {
				// 첫 번째 엔티티 처리
				applyBulkForEntity( first, idField, bulkOps );

				// 나머지 엔티티 처리
				while (it.hasNext()) {
					E entity = it.next();
					applyBulkForEntity( entity, idField, bulkOps );

				}

			} catch (IllegalAccessException e) {
				return Mono
					.error(
						new RuntimeException( "Failed to access @Id field via reflection", e )
					);

			} finally {
				idField.setAccessible( false );

			}

			return bulkOps.execute();

		}

		/**
		 * Collection<E>를 받아 대량 저장(Bulk Upsert)을 수행합니다.
		 * 
		 * @param entities
		 *            저장할 엔티티 컬렉션
		 * 
		 * @return BulkWriteResult의 Mono
		 */
		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Collection<E> entities
		) {

			return saveAllBulkUpsert( (Iterable<E>) entities );

		}

		/**
		 * Flux<E> 스트림을 받아 대량 저장(Bulk Upsert)을 수행하는 핵심 메서드입니다.
		 * 스트림의 모든 엔티티에 대해 'upsert' 연산을 준비하고 단일 DB 요청으로 실행합니다.
		 * (주의: 이 메서드를 사용하려면 엔티티에 getId() 메서드가 있어야 합니다.)
		 * 
		 * @param entityFlux
		 *            저장할 엔티티의 Flux
		 * 
		 * @return BulkWriteResult의 Mono (처리 결과)
		 */
		public Mono<BulkWriteResult> saveAllBulkUpsert(
			Flux<E> entityFlux
		) {

			AtomicReference<ReactiveBulkOperations> bulkRef = new AtomicReference<>();
			AtomicReference<Field> idFieldRef = new AtomicReference<>();
			AtomicBoolean hasValue = new AtomicBoolean( false );

			return entityFlux
				.flatMap( entity -> {
					hasValue.set( true );

					ReactiveBulkOperations bulkOps = bulkRef.get();
					Field idField = idFieldRef.get();

					// 첫 요소에서 lazy init
					if (bulkOps == null) {
						Class<?> entityClass = entity.getClass();
						Field f = MongoIdFieldResolver.findIdField( entityClass );
						f.setAccessible( true );

						ReactiveBulkOperations newBulk = reactiveMongoTemplate
							.bulkOps( BulkOperations.BulkMode.UNORDERED, entityClass );

						bulkRef.set( newBulk );
						idFieldRef.set( f );

						bulkOps = newBulk;
						idField = f;

					}

					try {
						Object id = idField.get( entity );

						if (id == null) {
							// 신규 레코드 → insert
							bulkOps.insert( entity );
							return Mono.empty();

						}

						Query query = Query.query( Criteria.where( "_id" ).is( id ) );

						org.bson.Document doc = new org.bson.Document();
						reactiveMongoTemplate.getConverter().write( entity, doc );
						doc.remove( "_id" );

						if (! doc.isEmpty()) {
							org.bson.Document updateDoc = new org.bson.Document( "$set", doc );
							Update update = new BasicUpdate( updateDoc );
							bulkOps.upsert( query, update );

						}

						return Mono.empty();

					} catch (IllegalAccessException e) {
						return Mono
							.error(
								new RuntimeException( "Failed to access @Id field via reflection", e )
							);

					}

				} )
				// 모든 엔티티에 대해 bulk 작업 쌓기 끝난 뒤 execute
				.then(
					Mono.defer( () -> {

						if (! hasValue.get()) {
							// 비어있는 Flux 였으면 아무 작업도 안 함
							return Mono.empty();

						}

						ReactiveBulkOperations bulkOps = bulkRef.get();

						if (bulkOps == null) { return Mono.empty(); }

						return bulkOps.execute();

					} )
				)
				// 성공/실패/취소 어떤 경우든 @Id 필드 접근 권한 원복
				.doFinally( signalType -> {
					Field idField = idFieldRef.get();

					if (idField != null) {
						idField.setAccessible( false );

					}

				} );

		}

		public Mono<BulkWriteResult> saveAllBulkUpsertByKey(
			Flux<E> entityFlux, String... keyFieldName // 예: "caseKey" 또는 "court","year","caseNo"
		) {

			if (entityFlux == null)
				return Mono.error( new IllegalArgumentException( "entityFlux must not be null" ) );
			if (keyFieldName == null || keyFieldName.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must not be null/empty" ) );

			// blank 방지 + 정규화
			final String[] keys = Arrays
				.stream( keyFieldName )
				.filter( Objects::nonNull )
				.map( String::trim )
				.filter( s -> ! s.isBlank() )
				.toArray( String[]::new );

			if (keys.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must contain at least 1 non-blank field" ) );

			AtomicReference<ReactiveBulkOperations> bulkRef = new AtomicReference<>();
			AtomicReference<Field[]> keyFieldsRef = new AtomicReference<>();
			AtomicBoolean hasValue = new AtomicBoolean( false );

			return entityFlux
				// bulkOps에 작업 쌓기는 side-effect -> 순차로 안전하게
				.concatMap( entity -> {
					hasValue.set( true );

					ReactiveBulkOperations bulkOps = bulkRef.get();
					Field[] keyFields = keyFieldsRef.get();

					// 첫 요소에서 lazy init
					if (bulkOps == null) {
						Class<?> entityClass = entity.getClass();

						Field[] fs = new Field[keys.length];

						try {

							for (int i = 0; i < keys.length; i++) {
								Field f = entityClass.getDeclaredField( keys[i] );
								f.setAccessible( true );
								fs[i] = f;

							}

						} catch (NoSuchFieldException e) {
							return Mono
								.error(
									new IllegalArgumentException(
										"No field in " + entityClass.getName() + ": " + e.getMessage(),
										e
									)
								);

						}

						ReactiveBulkOperations newBulk = reactiveMongoTemplate.bulkOps( BulkOperations.BulkMode.UNORDERED, entityClass );

						bulkRef.set( newBulk );
						keyFieldsRef.set( fs );

						bulkOps = newBulk;
						keyFields = fs;

					}

					try {
						// keyDoc 구성 + null 체크
						Document keyDoc = new Document();

						for (int i = 0; i < keys.length; i++) {
							Object v = keyFields[i].get( entity );

							if (v == null) {
								// 정책: 키 하나라도 없으면 upsert 불가 -> insert(또는 skip/에러로 바꿔도 됨)
								bulkOps.insert( entity );
								return Mono.empty();

							}

							keyDoc.append( keys[i], v );

						}

						// Query: 단일키면 where, 복합키면 andOperator
						Query query;

						if (keys.length == 1) {
							query = Query.query( Criteria.where( keys[0] ).is( keyDoc.get( keys[0] ) ) );

						} else {
							Criteria[] cs = new Criteria[keys.length];

							for (int i = 0; i < keys.length; i++) {
								cs[i] = Criteria.where( keys[i] ).is( keyDoc.get( keys[i] ) );

							}

							query = Query.query( new Criteria().andOperator( cs ) );

						}

						// Update: 엔티티 -> doc 변환 후 _id 제거
						Document doc = new Document();
						reactiveMongoTemplate.getConverter().write( entity, doc );
						doc.remove( "_id" );

						Document updateDoc = new Document()
							.append( "$set", new Document( doc ) )
							.append( "$setOnInsert", new Document( keyDoc ) ); // 키 필드들 고정

						bulkOps.upsert( query, new BasicUpdate( updateDoc ) );
						return Mono.empty();

					} catch (IllegalAccessException e) {
						return Mono.error( new RuntimeException( "Failed to access key field(s)", e ) );

					}

				} )
				.then( Mono.defer( () -> {
					if (! hasValue.get())
						return Mono.empty();
					ReactiveBulkOperations bulkOps = bulkRef.get();
					if (bulkOps == null)
						return Mono.empty();
					return bulkOps.execute();

				} ) )
				.doFinally( st -> {
					Field[] fs = keyFieldsRef.get();

					if (fs != null) {

						for (Field f : fs) {
							if (f != null)
								f.setAccessible( false );

						}

					}

				} );

		}


		public Mono<BulkWriteResult> saveAllBulkUpsertByKey(
			Collection<E> entities, String... keyFieldName // 예: "caseKey" 또는 "court", "year", "caseNo"
		) {

			if (entities == null || entities.isEmpty())
				return Mono.empty();
			if (keyFieldName == null || keyFieldName.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must not be null/empty" ) );

			// blank 방지
			String[] keys = Arrays
				.stream( keyFieldName )
				.filter( Objects::nonNull )
				.map( String::trim )
				.filter( s -> ! s.isBlank() )
				.toArray( String[]::new );

			if (keys.length == 0)
				return Mono.error( new IllegalArgumentException( "keyFieldName must contain at least 1 non-blank field" ) );

			Class<?> entityClass = entities.iterator().next().getClass();

			// key Field들 준비
			final Field[] keyFields = new Field[keys.length];

			try {

				for (int i = 0; i < keys.length; i++) {
					Field f = entityClass.getDeclaredField( keys[i] );
					f.setAccessible( true );
					keyFields[i] = f;

				}

			} catch (NoSuchFieldException e) {
				// 어떤 키에서 터졌는지 메시지 보강
				return Mono.error( new IllegalArgumentException( "No field in " + entityClass.getName() + ": " + e.getMessage(), e ) );

			}

			ReactiveBulkOperations bulkOps = reactiveMongoTemplate.bulkOps( BulkOperations.BulkMode.UNORDERED, entityClass );

			try {

				for (E entity : entities) {

					// 1) key 값 수집 + null 체크
					Document keyDoc = new Document(); // {k1:v1, k2:v2...} (setOnInsert에도 재사용)
					boolean missingKey = false;

					for (int i = 0; i < keys.length; i++) {
						Object v = keyFields[i].get( entity );

						if (v == null) {
							missingKey = true;
							break;

						}

						keyDoc.append( keys[i], v );

					}

					if (missingKey) {
						// 정책: 키가 하나라도 없으면 upsert 기준이 없으니 insert(또는 skip/에러) 중 택1
						bulkOps.insert( entity );
						continue;

					}

					// 2) Query: AND 조건으로 결합 (복합키)
					Criteria[] cs = new Criteria[keys.length];

					for (int i = 0; i < keys.length; i++) {
						cs[i] = Criteria.where( keys[i] ).is( keyDoc.get( keys[i] ) );

					}

					Query query = Query.query( new Criteria().andOperator( cs ) );

					// 3) Update document 생성
					Document doc = new Document();
					reactiveMongoTemplate.getConverter().write( entity, doc );
					doc.remove( "_id" ); // _id는 기본 생성 유지

					for (String k : keys) {
						doc.remove( k );

					}

					// 업데이트는 $set, 키는 불변 가정이면 $setOnInsert로만
					Document updateDoc = new Document()
						.append( "$set", new Document( doc ) )
						.append( "$setOnInsert", new Document( keyDoc ) ); // key들 전부 넣기

					bulkOps.upsert( query, new BasicUpdate( updateDoc ) );

				}

			} catch (IllegalAccessException e) {
				return Mono.error( new RuntimeException( "Failed to access key field(s)", e ) );

			} finally {

				for (Field f : keyFields) {
					if (f != null)
						f.setAccessible( false );

				}

			}

			return bulkOps.execute();

		}

		private String resolveRemoveCollectionName(
			Class<?> clazz
		) {

			var doc = clazz
				.getDeclaredAnnotation(
					org.springframework.data.mongodb.core.mapping.Document.class
				);

			if (doc == null || doc.collection() == null || doc.collection().isBlank()) { return clazz.getSimpleName() + "_remove"; }

			return doc.collection() + "_remove";

		}

		public Mono<BulkWriteResult> deleteBulk(
			Iterable<E> entities
		) {

			return deleteBulk( Flux.fromIterable( entities ), false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Iterable<E> entities, boolean isBackup
		) {

			return deleteBulk( Flux.fromIterable( entities ), isBackup );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Collection<E> entities
		) {

			return deleteBulk( Flux.fromIterable( entities ), false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Collection<E> entities, boolean isBackup
		) {

			return deleteBulk( Flux.fromIterable( entities ), isBackup );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Flux<E> entityFlux
		) {

			return deleteBulk( entityFlux, false );

		}

		public Mono<BulkWriteResult> deleteBulk(
			Flux<E> entityFlux, boolean isBackup
		) {

			if (! isBackup) { return deleteBulkInternal( entityFlux ); }

			// backup이 필요한 경우엔 엔티티를 재사용해야 하므로 list로 한번 모음
			return entityFlux
				.collectList()
				.flatMap( list -> {

					if (list.isEmpty())
						return Mono.empty();

					Class<?> entityClass = list.get( 0 ).getClass();
					String backupCollectionName = resolveRemoveCollectionName( entityClass );

					// 백업 먼저 적재 -> 그 다음 bulk delete
					return reactiveMongoTemplate
						.insert( list, backupCollectionName )
						.then( deleteBulkInternal( Flux.fromIterable( list ) ) );

				} );

		}

		/**
		 * 실제 bulk delete 수행(backup 없이).
		 * saveAllBulkUpsert(Flux)와 동일한 lazy-init 패턴을 사용합니다.
		 */
		private Mono<BulkWriteResult> deleteBulkInternal(
			Flux<E> entityFlux
		) {

			AtomicReference<ReactiveBulkOperations> bulkRef = new AtomicReference<>();
			AtomicReference<Field> idFieldRef = new AtomicReference<>();
			AtomicBoolean hasValue = new AtomicBoolean( false );

			return entityFlux
				.flatMap( entity -> {

					hasValue.set( true );

					ReactiveBulkOperations bulkOps = bulkRef.get();
					Field idField = idFieldRef.get();

					// 첫 요소에서 lazy init
					if (bulkOps == null) {
						Class<?> entityClass = entity.getClass();

						Field f = MongoIdFieldResolver.findIdField( entityClass );
						f.setAccessible( true );

						ReactiveBulkOperations newBulk = reactiveMongoTemplate
							.bulkOps( BulkOperations.BulkMode.UNORDERED, entityClass );

						bulkRef.set( newBulk );
						idFieldRef.set( f );

						bulkOps = newBulk;
						idField = f;

					}

					try {
						Object id = idField.get( entity );

						// id 없으면 삭제 대상에서 제외
						if (id == null)
							return Mono.empty();

						Query q = Query.query( Criteria.where( "_id" ).is( id ) );
						bulkOps.remove( q );

						return Mono.empty();

					} catch (IllegalAccessException e) {
						return Mono.error( new RuntimeException( "Failed to access @Id field via reflection", e ) );

					}

				} )
				.then(
					Mono.defer( () -> {

						if (! hasValue.get())
							return Mono.empty();

						ReactiveBulkOperations bulkOps = bulkRef.get();
						if (bulkOps == null)
							return Mono.empty();

						return bulkOps.execute();

					} )
				)
				.doFinally( signalType -> {
					Field idField = idFieldRef.get();
					if (idField != null)
						idField.setAccessible( false );

				} );

		}

		public Mono<DeleteResult> delete(
			E e
		) {

			return this.delete( e, false );

		}

		public Mono<DeleteResult> delete(
			Mono<E> e
		) {

			return this.delete( e, false );

		}

		public Mono<DeleteResult> delete(
			E e, boolean isBackup
		) {

			return reactiveMongoTemplate
				.remove( e )
				.flatMap( dr -> {

					if (! isBackup) { return Mono.just( dr ); }

					return executeClassMono.flatMap( clazz -> {
						var doc = clazz
							.getDeclaredAnnotation(
								org.springframework.data.mongodb.core.mapping.Document.class
							);

						String collectionName;

						if (doc == null || doc.collection() == null || doc.collection().isBlank()) {
							collectionName = clazz.getSimpleName() + "_remove";

						} else {
							collectionName = doc.collection() + "_remove";

						}

						// 백업 insert 완료 후 원래 DeleteResult를 그대로 반환
						return reactiveMongoTemplate.insert( e, collectionName ).thenReturn( dr );

					} );

				} );

		}


		public Mono<DeleteResult> delete(
			Mono<E> eMono, boolean isBackup
		) {

			return eMono
				.flatMap(
					entity -> reactiveMongoTemplate
						.remove( entity )
						.flatMap( dr -> {
							if (! isBackup)
								return Mono.just( dr );

							return executeClassMono.flatMap( clazz -> {
								var doc = clazz.getDeclaredAnnotation( org.springframework.data.mongodb.core.mapping.Document.class );
								String collectionName;

								if (doc == null || doc.collection() == null || doc.collection().isBlank()) {
									collectionName = clazz.getSimpleName() + "_remove";

								} else {
									collectionName = doc.collection() + "_remove";

								}

								// 백업 insert 완료 후 원래 DeleteResult를 그대로 반환
								return reactiveMongoTemplate.insert( entity, collectionName ).thenReturn( dr );

							} );

						} )
				);

		}

		@SuppressWarnings("unchecked")
		private E deepClone(
			E e, ObjectMapper objectMapper
		) {

			try {
				String json = objectMapper.writeValueAsString( e );
				return (E) objectMapper.readValue( json, e.getClass() );

			} catch (Exception ex) {
				throw new RuntimeException( "Failed to clone entity for history", ex );

			}

		}

		public Mono<Void> createHistory(
			E e
		) {

			return createHistory( e, "history", objectMapper );

		}

		public Mono<Void> createHistory(
			E e, String prefix
		) {

			return createHistory( e, prefix, objectMapper );

		}

		public Mono<Void> createHistory(
			E e, ObjectMapper objectMapper
		) {

			return createHistory( e, "history", objectMapper );

		}

		public Mono<Void> createHistory(
			E e, String prefix, ObjectMapper objectMapper
		) {

			Class<?> entityClass = e.getClass();
			String _prefix = (prefix == null || prefix.isBlank())
				? "history"
				: (prefix.charAt( 0 ) == '_' ? prefix.substring( 1 ) : prefix);

			String base;

			if (! entityClass.isAnnotationPresent( org.springframework.data.mongodb.core.mapping.Document.class )) {
				base = entityClass.getSimpleName();

			} else {
				var doc = entityClass.getAnnotation( org.springframework.data.mongodb.core.mapping.Document.class );
				String cand = ! doc.collection().isBlank() ? doc.collection() : doc.value();
				base = cand.isBlank() ? entityClass.getSimpleName() : cand;

			}

			String backupCollectionName = base + "_" + _prefix;

			E snapshot = deepClone( e, objectMapper );

			MongoMappingContext ctx = (MongoMappingContext) reactiveMongoTemplate.getConverter().getMappingContext();
			MongoPersistentEntity<?> pe = ctx.getPersistentEntity( snapshot.getClass() );
			boolean idCleared = false;

			if (pe != null && pe.getIdProperty() != null) {
				PersistentPropertyAccessor<?> accessor = pe.getPropertyAccessor( snapshot );
				MongoPersistentProperty idProp = pe.getIdProperty();
				accessor.setProperty( idProp, null );
				idCleared = true;

			}

			if (! idCleared) {
				Class<?> c = snapshot.getClass();

				while (c != null && c != Object.class) {

					try {
						var f = c.getDeclaredField( "id" );
						f.setAccessible( true );
						f.set( snapshot, null );
						break;

					} catch (NoSuchFieldException ignore) {
						c = c.getSuperclass();

					} catch (IllegalAccessException ignore) {
						break;

					}

				}

			}

			return reactiveMongoTemplate.insert( snapshot, backupCollectionName ).then();

		}

		public FieldBuilder<E> fields(
			FieldsPair<?, ?>... fieldsPairs
		) {

			return fields( LogicalOperator.AND, fieldsPairs );

		}

		public FieldBuilder<E> fields(
			Collection<FieldsPair<?, ?>> fieldsPairs
		) {

			return fields( LogicalOperator.AND, fieldsPairs );

		}

		public FieldBuilder<E> fields() {

			return fields( LogicalOperator.AND );

		}


		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator, FieldsPair<?, ?>... fieldsPairs
		) {

			if (fieldsPairs == null || fieldsPairs.length == 0)
				return createFirstOperator( logicalOperator );
			return createFirstOperator( logicalOperator ).fields( fieldsPairs );

		}

		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator, Collection<FieldsPair<?, ?>> fieldsPairs
		) {

			if (fieldsPairs == null || fieldsPairs.isEmpty())
				return createFirstOperator( logicalOperator );
			return createFirstOperator( logicalOperator ).fields( fieldsPairs.stream().toArray( FieldsPair[]::new ) );

		}

		public FieldBuilder<E> fields(
			LogicalOperator logicalOperator
		) {

			return createFirstOperator( logicalOperator );

		}

		private FieldBuilder<E> createFirstOperator(
			LogicalOperator logicalOperator
		) {

			this.fieldBuilder = new FieldBuilder<>( logicalOperator );
			return this.fieldBuilder;

		}

		protected Mono<Class<E>> extractEntityClass(
			Class<? extends ReactiveCrudRepository<?, ?>> repositoryClass
		) {

			@SuppressWarnings("unchecked")
			Class<E> cachedClass = (Class<E>) entityClassCache.get( repositoryClass );

			if (cachedClass != null) { return Mono.just( cachedClass ); }

			@SuppressWarnings("unchecked")
			Mono<Class<E>> result = Mono.fromCallable( () -> {
				// 리포지토리 클래스가 ReactiveCrudRepository를 구현하고 있는지 확인
				Type[] genericInterfaces = repositoryClass.getGenericInterfaces();
				ParameterizedType reactiveCrudRepoType = null;

				for (Type type : genericInterfaces) {

					if (type instanceof ParameterizedType) {
						ParameterizedType paramType = (ParameterizedType) type;

						if (paramType.getRawType() instanceof Class && ReactiveCrudRepository.class.isAssignableFrom( (Class<?>) paramType.getRawType() )) {
							reactiveCrudRepoType = paramType;
							break;

						}

					}

				}

				// ReactiveCrudRepository 인터페이스를 찾지 못한 경우 예외 발생
				if (reactiveCrudRepoType == null) {
					throw new IllegalArgumentException(
						"The provided repository class '" + repositoryClass.getName() + "' does not implement ReactiveCrudRepository."
					);

				}

				// 첫 번째 제너릭 타입 인수(T)를 추출
				Type entityType = reactiveCrudRepoType.getActualTypeArguments()[0];

				if (! (entityType instanceof Class<?>)) { throw new IllegalArgumentException(
					"The entity type is not a class for repository '" + repositoryClass.getName() + "'."
				); }

				Class<?> entityClass = (Class<?>) entityType;

				// 엔티티 클래스가 BaseEntity를 상속하는지 확인
				// if (! BaseEntity.class.isAssignableFrom( entityClass )) { throw new IllegalArgumentException(
				// "The entity class '" + entityClass.getName() + "' must extend 'BaseEntity'."
				// ); }

				return (Class<E>) entityClass;

			} );
			return result;// .onErrorMap( e -> new RuntimeException( "Failed to extract entity class: " + e.getMessage(), e )
							// );

		}

		public abstract class Grouping<KK, V> {

			private final List<String> keyFields = new ArrayList<>();

			private final Document accumulators = new Document(); // as -> {$op: ...}

			private boolean hasAccumulator = false; // 아무것도 지정 안 하면 count 기본

			protected Class<KK> keyType;

			protected Class<V> valueType;

			private Function<Document, KK> keyConverter;

			private Function<Document, V> valueConverter;

			private final QueryBuilderAccesser accessor;

			@SuppressWarnings("unchecked")
			public Grouping(
							Class<KK> k,
							Class<V> v,
							QueryBuilderAccesser accessor
			) {

				this.keyType = k;
				this.valueType = v;
				this.accessor = Objects.requireNonNull( accessor, "accessor" );
				this.keyConverter = (Document kk) -> {
					Object key = kk.get( "_id" );

					return (KK) key;

				};
				this.valueConverter = (Document vv) -> {

					return reactiveMongoTemplate.getConverter().read( this.valueType, vv );

				};

			}
			// @SuppressWarnings("unchecked")
			// public Grouping() {
			//
			// Type genericSuperclass = getClass().getGenericSuperclass();
			//
			// if (! (genericSuperclass instanceof ParameterizedType)) {
			// // 상세한 오류 메시지 생성
			//
			// throw new IllegalStateException(
			// String
			// .format(
			// "Class '%s' inherits from Grouping without specifying generic parameters. " + "To check type
			// information at runtime, you must inherit using the format 'extends Grouping<ConcreteKeyType,
			// ConcreteValueType>'.",
			// getClass().getName()
			// )
			// );
			//
			// }
			//
			// ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
			// Type[] typeArguments = parameterizedType.getActualTypeArguments();
			//
			// System.out.println( Arrays.asList( typeArguments ) );
			//
			// this.keyType = (Class<K>) typeArguments[0];
			// this.valueType = (Class<V>) typeArguments[1];
			//
			// this.keyConverter = (Document kk) -> {
			// Object key = kk.get( "_id" );
			//
			// return (K) key;
			//
			// };
			// this.valueConverter = (Document vv) -> {
			//
			// return reactiveMongoTemplate.getConverter().read( this.valueType, vv );
			//
			// };
			//
			// }

			public Grouping<KK, V> keyConverter(
				Function<Document, KK> fn
			) {

				if (fn != null) {
					this.keyConverter = fn;

				}

				return this;

			}

			public Grouping<KK, V> valueConverter(
				Function<Document, V> fn
			) {

				if (fn != null) {
					this.valueConverter = fn;

				}

				return this;

			}

			/** 그룹 키 지정 (1개 이상) */
			public Grouping<KK, V> by(
				String... keys
			) {

				if (keys == null || keys.length == 0) { throw new IllegalArgumentException( "group by keys must not be empty." ); }

				for (String k : keys) {
					if (k == null || k.isBlank())
						continue;
					keyFields.add( k );

				}

				if (keyFields.isEmpty()) { throw new IllegalArgumentException( "valid group by key required." ); }

				return this;

			}

			/** 누적기들 */
			public Grouping<KK, V> count() {

				return countAs( "count" );

			}

			public Grouping<KK, V> countAs(
				String as
			) {

				accumulators.put( as, new Document( "$sum", 1 ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> sum(
				String field, String as
			) {

				accumulators.put( as, new Document( "$sum", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> avg(
				String field, String as
			) {

				accumulators.put( as, new Document( "$avg", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> min(
				String field, String as
			) {

				accumulators.put( as, new Document( "$min", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> max(
				String field, String as
			) {

				accumulators.put( as, new Document( "$max", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> addToSet(
				String field, String as
			) {

				accumulators.put( as, new Document( "$addToSet", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			public Grouping<KK, V> push(
				String field, String as
			) {

				accumulators.put( as, new Document( "$push", "$" + field ) );
				hasAccumulator = true;
				return this;

			}

			/** lookup 없이 그룹 실행 */
			public Mono<Map<KK, V>> execute() {

				return buildAndRun( null, null );

			}

			/** lookup 포함 그룹 실행 */
			public <R2> Mono<Map<KK, V>> executeLookup(
				ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Objects.requireNonNull( rightBuilder, "rightBuilder is required" );
				Objects.requireNonNull( spec, "LookupSpec is required" );
				return buildAndRun( new LookupCtx<>( rightBuilder, spec ), null );

			}

			// 내부: 파이프라인 구성/실행
			private <R2> Mono<Map<KK, V>> buildAndRun(
				LookupCtx<R2> lookup, Sort dummy
			) {

				if (keyFields.isEmpty())
					throw new IllegalStateException( "group by keys are not specified." );
				if (! hasAccumulator)
					count();

				Mono<Class<E>> leftClassMono = executeClassMono;

				return Mono
					.zip( fieldBuilder.buildCriteria(), leftClassMono )
					.flatMap( tuple -> {
						Optional<Criteria> leftMatch = tuple.getT1();
						Class<E> leftClass = tuple.getT2();

						String leftColl = (collectionName != null && ! collectionName.isBlank())
							? collectionName
							: reactiveMongoTemplate.getCollectionName( leftClass );

						List<AggregationOperation> ops = new ArrayList<>();
						leftMatch.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

						Mono<List<AggregationOperation>> opsMono = (lookup == null)
							? Mono.just( ops )
							: lookup.rightClass().map( rightClass -> {
								String rightColl = (lookup.rightCollectionName() != null && ! lookup.rightCollectionName().isBlank())
									? lookup.rightCollectionName()
									: reactiveMongoTemplate.getCollectionName( rightClass );

								String rightAs = (lookup.spec.getAs() != null && ! lookup.spec.getAs().isBlank())
									? lookup.spec.getAs()
									: rightClass.getSimpleName();

								Document lk = new Document( "from", rightColl ).append( "as", rightAs );

								if (lookup.spec.getLocalField() != null && lookup.spec.getForeignField() != null) {
									lk
										.append( "localField", lookup.spec.getLocalField() )
										.append( "foreignField", lookup.spec.getForeignField() );

								} else {
									lk
										.append( "let", Optional.ofNullable( lookup.spec.getLetDoc() ).orElseGet( Document::new ) )
										.append( "pipeline", Optional.ofNullable( lookup.spec.getPipelineDocs() ).orElseGet( List::of ) );

								}

								ops.add( ctx -> new Document( "$lookup", lk ) );

								if (lookup.spec.isUnwind()) {
									ops
										.add(
											ctx -> new Document(
												"$unwind",
												new Document( "path", "$" + rightAs )
													.append( "preserveNullAndEmptyArrays", lookup.spec.isPreserveNullAndEmptyArrays() )
											)
										);

								}

								if (lookup.spec.getOuterStages() != null) {

									for (Document st : lookup.spec.getOuterStages()) {
										ops.add( ctx -> st );

									}

								}

								return ops;

							} );

						return opsMono.flatMap( opList -> {
							Object groupId = (keyFields.size() == 1)
								? "$" + keyFields.get( 0 )
								: new Document().append( keyFields.get( 0 ), "$" + keyFields.get( 0 ) ); // 아래에서 제대로 채움

							if (keyFields.size() > 1) {
								Document gid = new Document();
								for (String k : keyFields)
									gid.append( k, "$" + k );
								groupId = gid;

							}

							Document groupBody = new Document( "_id", groupId );
							for (String as : accumulators.keySet())
								groupBody.append( as, accumulators.get( as ) );
							opList.add( ctx -> new Document( "$group", groupBody ) );

							Aggregation agg = accessor.applyAggOptions( Aggregation.newAggregation( opList ) );

							Flux<Document> flux = (collectionName != null && ! collectionName.isBlank())
								? reactiveMongoTemplate.aggregate( agg, leftColl, Document.class )
								: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class );

							return flux.collect( LinkedHashMap::new, (LinkedHashMap<KK, V> map, Document d) -> {
								KK key = this.keyConverter.apply( d );
								Document vd = new Document( d );
								vd.remove( "_id" );
								V v = this.valueConverter.apply( vd );
								map.put( key, v );

							} );

						} );

					} );

			}

			// $lookup 컨텍스트 Helper
			private class LookupCtx<R2> {

				final ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder;

				final LookupSpec spec;

				LookupCtx(
						ReactiveMongoDsl<K>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rb,
							LookupSpec sp
				) {

					this.rightBuilder = rb;
					this.spec = sp;

				}

				Mono<Class<R2>> rightClass() {

					return rightBuilder.getExecuteClassMono();

				}

				String rightCollectionName() {

					return rightBuilder.getCollectionName();

				}

			}

		}

		



		public interface ExecuteBuilder {

		}


		protected abstract class QueryBuilderAccesser<Q, A> {

			protected ReadPreference readPreference = null;

			protected Boolean isAllowDiskUse = null;

			protected Consumer<Query> queryCustomizer = q -> {};

			protected Consumer<AggregationOptions.Builder> aggOptionsCustomizer = b -> {};

			public interface Runner {}

			@SuppressWarnings("unchecked")
			public final Q customizeQuery(
				Consumer<Query> c
			) {

				if (c != null)
					this.queryCustomizer = this.queryCustomizer.andThen( c );
				return (Q) this;

			}

			@SuppressWarnings("unchecked")
			public final A customizeAggregation(
				Consumer<AggregationOptions.Builder> c
			) {

				if (c != null)
					this.aggOptionsCustomizer = this.aggOptionsCustomizer.andThen( c );
				return (A) this;

			}


			public QueryBuilderAccesser<Q, A> readPreference(
				ReadPreference rp
			) {

				this.readPreference = rp;
				return this;

			}

			public QueryBuilderAccesser<Q, A> isAllowDiskUse(
				Boolean allow
			) {

				this.isAllowDiskUse = allow;
				return this;

			}

			protected Aggregation applyAggOptions(
				Aggregation agg
			) {

				AggregationOptions.Builder b = AggregationOptions.builder();

				if (isAllowDiskUse != null)
					b.allowDiskUse( isAllowDiskUse );
				if (readPreference != null)
					b.readPreference( readPreference );

				aggOptionsCustomizer.accept( b );

				return agg.withOptions( b.build() );

			}


			protected Query applyQueryOptions(
				Query q
			) {

				if (readPreference != null)
					q.withReadPreference( readPreference );

				if (isAllowDiskUse != null) {
					q.allowDiskUse( isAllowDiskUse );

					// 또는 query.diskUse(isAllowDiskUse ? DiskUse.ALLOW : DiskUse.DISALLOW);
				}

				queryCustomizer.accept( q );
				return q;

			}

			public <KK, V> Grouping<KK, V> group(
				Class<KK> k, Class<V> v
			) {

				return new Grouping<KK, V>( k, v, this ) {};

			}

			protected String resolveCollectionName(
				Class<?> clazz
			) {

				return reactiveMongoTemplate.getCollectionName( clazz );

			}

			protected String simpleName(
				Class<?> clazz
			) {

				return clazz.getSimpleName();

			}


			protected Mono<Class<E>> getExecuteClassMono() { return executeClassMono; }

			protected String getCollectionName() { return collectionName; }

			protected Mono<Optional<Criteria>> getFieldBuilderCriteria() { return fieldBuilder.buildCriteria(); }


			public interface FindAllExecute<E> extends Runner {

				Flux<E> execute();

			}

			public interface FindAllAggregation<E> extends Runner {

				Mono<PageResult<E>> executeAggregation();

				<R2> Flux<ResultTuple<E, List<R2>>> executeLookup(
						ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);

				<R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupAndCount(
						ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);

			}

			public interface FindExecute<E> extends Runner {

				Mono<E> execute();

				Mono<E> executeFirst();

			}

			public interface FindAggregation<E> extends Runner {

				Mono<E> executeAggregation();

				<R2> Mono<ResultTuple<E, R2>> executeLookup(
					ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

			public interface CountExecute<E> extends Runner {

				Mono<Long> execute();


			}

			public interface CountAggregation<E> extends Runner {

				Mono<Long> executeAggregation();

				<R2> Mono<ResultTuple<Long, Long>> executeLookup(
						ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

			public interface ExistsExecute<E> extends Runner {

				Mono<Boolean> execute();


			}

			public interface ExistsAggregation<E> extends Runner {

				Mono<Boolean> executeAggregation();

				<R2> Mono<ResultTuple<Boolean, Boolean>> executeLookup(
						ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
				);


			}

		}

		public class FieldBuilder<S extends E> {

			private Deque<CriteriaGroup> criteriaStack = new ArrayDeque<>();

			/* public FieldBuilder() {
			 * 
			 * // 기본적으로 AND 그룹으로 시작
			 * criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );
			 * 
			 * } */

			public FieldBuilder() {

				this( LogicalOperator.AND );

			}

			public FieldBuilder(
								LogicalOperator rootOperator
			) {

				LogicalOperator op = (rootOperator == null) ? LogicalOperator.AND : rootOperator;
				// ✅ fields(LogicalOperator.xxx)로 시작할 때 루트 그룹에 반영
				criteriaStack.push( new CriteriaGroup( op ) );

			}

			// 필드를 현재 그룹에 추가
			public FieldBuilder<S> fields(
				FieldsPair<?, ?>... fieldsPairs
			) {

				if (fieldsPairs != null && fieldsPairs.length > 0) {

					for (FieldsPair<?, ?> pair : fieldsPairs) {

						if (pair != null) {
							Criteria criteria = MongoCriteriaSupport.createSingleCriteria( pair );

							if (criteria != null) {
								criteriaStack.peek().criteriaList.add( criteria );

							}

						}

					}

				}

				return this;

			}

			public FieldBuilder<S> and(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				} // 자동 닫기

				return this;

			}


			public FieldBuilder<S> or(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.OR ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				} // 자동 닫기

				return this;

			}

			public FieldBuilder<S> not(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );

				try {
					and( block );

				} finally {
					endOperator();

				}

				return this;

			}

			// NOT(OR(...))
			public FieldBuilder<S> notAny(
				Consumer<FieldBuilder<S>> block
			) {

				criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );

				try {
					block.accept( this );

				} finally {
					endOperator();

				}

				return this;

			}

			// NOT(AND(...))
			public FieldBuilder<S> notAll(
				Consumer<FieldBuilder<S>> block
			) {

				return not( block );

			}

			// public FieldBuilder<S> and() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.AND ) );
			// return this;
			//
			// }
			//
			// public FieldBuilder<S> or() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.OR ) );
			// return this;
			//
			// }
			//
			// public FieldBuilder<S> nor() {
			//
			// criteriaStack.push( new CriteriaGroup( LogicalOperator.NOR ) );
			// return this;
			//
			// }

			// 현재 그룹 종료 및 상위 그룹에 추가
			private FieldBuilder<S> endOperator() {

				if (criteriaStack.size() <= 1) { return this; }

				CriteriaGroup finishedGroup = criteriaStack.pop();
				List<Criteria> validCriteria = finishedGroup.criteriaList
					.stream()
					.filter( Objects::nonNull )
					.collect( Collectors.toList() );

				if (! validCriteria.isEmpty()) {
					Criteria groupCriteria;

					switch (finishedGroup.operator) {
						case AND:
							groupCriteria = new Criteria().andOperator( validCriteria );
							break;
						case OR:
							groupCriteria = new Criteria().orOperator( validCriteria );
							break;
						case NOR:
							groupCriteria = new Criteria().norOperator( validCriteria );
							break;
						default:
							throw new IllegalArgumentException( "Unsupported operator: " + finishedGroup.operator );

					}

					// 상위 그룹에 추가
					criteriaStack.peek().criteriaList.add( groupCriteria );

				}

				return this;

			}

			public AbstractQueryBuilder<E, T>.QueryBuilderFactory end() {

				while (criteriaStack.size() > 1) {
					endOperator();

				}

				return new QueryBuilderFactory();

			}

			private Mono<Optional<Criteria>> buildCriteria() {

				Mono<Optional<Criteria>> resultMono = Mono.fromCallable( () -> {
					List<Criteria> allCriteria = new ArrayList<>();
					Deque<CriteriaGroup> tempStack = new ArrayDeque<>( criteriaStack );

					while (! tempStack.isEmpty()) {
						CriteriaGroup group = tempStack.pop();

						if (! group.criteriaList.isEmpty()) {
							Criteria combined = null;

							switch (group.operator) {
								case AND:
									combined = new Criteria().andOperator( group.criteriaList );
									break;
								case OR:
									combined = new Criteria().orOperator( group.criteriaList );
									break;
								case NOR:
									combined = new Criteria().norOperator( group.criteriaList );
									break;

							}

							if (combined != null) {
								allCriteria.add( combined );

							}

						}

					}

					if (allCriteria.isEmpty()) { return Optional.empty(); }

					if (allCriteria.size() == 1) { return Optional.of( allCriteria.get( 0 ) ); }

					return Optional.of( new Criteria().andOperator( allCriteria ) );

				} );
				return resultMono;
				// .onErrorMap( e -> new RuntimeException( "Failed to build Criteria: " + e.getMessage(), e ) );


			}

		}

		public class QueryBuilderFactory {

			public FindAllQueryBuilder<E> findAll() {

				return new FindAllQueryBuilder<E>();

			}

			public FindQueryBuilder<E> find() {

				return new FindQueryBuilder<E>();

			}

			public CountQueryBuilder count() {

				return new CountQueryBuilder();

			}


			public DeleteQueryBuilder delete() {

				return new DeleteQueryBuilder();

			}

			public ExistsQueryBuilder exists() {

				return new ExistsQueryBuilder();

			}

			// 원자적 update 빌더
			public AtomicUpdateQueryBuilder atomicUpdate() {

				return new AtomicUpdateQueryBuilder();

			}


		}

		public class FindAllQueryBuilder<S extends E> extends QueryBuilderAccesser<FindAllExecute<E>, FindAllAggregation<E>> implements FindAllExecute<E>, FindAllAggregation<E> {


			private Paging paging;

			private Sort sort = Sort.unsorted();

			private String[] excludes = null;


			public PageBuilder paging() {

				return new PageBuilder();

			}

			public FindAllQueryBuilder<S> paging(
				Integer pageNumber, Integer pageSize
			) {

				return new PageBuilder().and( pageNumber, pageSize );

			}

			public FindAllQueryBuilder<S> sorts(
				Order... sorts
			) {

				this.sort = Sort.by( sorts );
				return this;

			}


			public FindAllQueryBuilder<S> sorts(
				Collection<Order> sorts
			) {

				this.sort = Sort.by( sorts.toArray( Order[]::new ) );
				return this;

			}

			public FindAllQueryBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = excludes;
				return this;

			}


			public FindAllQueryBuilder<S> excludes(
				Collection<String> excludes
			) {

				this.excludes = excludes.toArray( String[]::new );
				return this;

			}

			public class PageBuilder {

				private Integer pageNumber;

				private Integer pageSize;

				public PageBuilder pageNumber(
					int pageNumber
				) {

					this.pageNumber = pageNumber;
					return this;

				}

				public PageBuilder pageSize(
					int pageSize
				) {

					this.pageSize = pageSize;
					return this;

				}

				public FindAllQueryBuilder<S> and(
					Integer pageNumber, Integer pageSize
				) {

					if (pageNumber == null || pageSize == null) { throw new IllegalArgumentException( "Both pageNumber and pageSize must be specified." ); }

					if (pageNumber < 0 || pageSize <= 0) { throw new IllegalArgumentException( "Invalid pageNumber or pageSize." ); }

					paging = new Paging( pageNumber, pageSize );
					return FindAllQueryBuilder.this;

				}

				public FindAllQueryBuilder<S> and() {

					if (pageNumber == null || pageSize == null) { throw new IllegalArgumentException( "Both pageNumber and pageSize must be specified." ); }

					if (pageNumber < 0 || pageSize <= 0) { throw new IllegalArgumentException( "Invalid pageNumber or pageSize." ); }

					paging = new Paging( pageNumber, pageSize );
					return FindAllQueryBuilder.this;

				}

			}

			private class Paging {

				private final int pageNumber;

				private final int pageSize;

				public Paging(
								int pageNumber,
								int pageSize
				) {

					this.pageNumber = pageNumber;
					this.pageSize = pageSize;

				}

			}

			@Override
			public Mono<PageResult<E>> executeAggregation() {

				// fieldBuilder.buildCriteria()는 Mono<Optional<Criteria>>를 반환한다고 가정합니다.
				Mono<Aggregation> aggregationMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					List<AggregationOperation> operations = new ArrayList<>();

					// criteriaOptional이 존재하면 $match 단계 추가
					if (criteriaOptional.isPresent()) {
						operations.add( Aggregation.match( criteriaOptional.get() ) );

					}

					// 정렬 단계 추가 (this.sort가 null이 아니라고 가정)
					operations
						.add(
							Aggregation
								.sort(
									(this.sort != null && this.sort.isSorted())
										? this.sort
										: Sort.by( Sort.Direction.DESC, "_id" )
								)
						);


					if (paging != null) {
						// operations.add( Aggregation.limit( paging.pageSize ) );
						// operations.add( Aggregation.skip( (long) paging.pageNumber * paging.pageSize ) );

						// "data" facet: 실제 데이터를 skip 후 limit 적용
						AggregationOperation dataFacet = Aggregation.skip( (long) paging.pageNumber * paging.pageSize );
						AggregationOperation dataLimitFacet = Aggregation.limit( paging.pageSize );

						// "totalCount" facet: 전체 개수를 계산
						AggregationOperation countFacet = Aggregation.count().as( "count" );

						FacetOperation facetOperation = Aggregation
							.facet( dataFacet, dataLimitFacet )
							.as( "data" )
							.and( countFacet )
							.as( "totalCount" );
						operations.add( facetOperation );

					}

					// excludes가 있을 경우 $project 단계로 제외할 필드 지정
					if (excludes != null && excludes.length != 0) {
						ProjectionOperation projection = Aggregation.project().andExclude( excludes );
						operations.add( projection );

					}

					Aggregation aggregation = applyAggOptions( Aggregation.newAggregation( operations ) );

					return aggregation;

				} );
				Mono<PageResult<E>> result = Mono
					.zip( executeClassMono, aggregationMono )
					.flatMap( tuple -> {
						Class<E> entityClass = tuple.getT1();
						Aggregation aggregation = tuple.getT2();

						// collectionName이 지정되어 있으면 해당 컬렉션에서 Aggregation 실행
						Flux<Document> resultDocument;

						if (collectionName != null && ! collectionName.isBlank()) {
							resultDocument = reactiveMongoTemplate
								.aggregate( aggregation, collectionName, Document.class );

						} else {
							resultDocument = reactiveMongoTemplate
								.aggregate( aggregation, entityClass, Document.class );

						}

						return resultDocument
							.single()
							.map( doc -> {
								// "data" 배열 추출 후, Entity로 매핑
								@SuppressWarnings("unchecked")
								List<Document> dataDocs = (List<Document>) doc.get( "data" );
								List<E> entities = dataDocs
									.stream()
									.map( document -> reactiveMongoTemplate.getConverter().read( entityClass, document ) )
									.collect( Collectors.toList() );

								// "totalCount" 배열에서 전체 개수 추출
								@SuppressWarnings("unchecked")
								List<Document> countDocs = (List<Document>) doc.get( "totalCount" );
								Number countNumber = countDocs.isEmpty()
									? 0
									: countDocs.get( 0 ).get( "count", Number.class );
								long totalCount = countNumber == null ? 0 : countNumber.longValue();
								return new PageResult<>( entities, totalCount );

							} );

					} );

				return result;
				// .onErrorMap( e -> new RuntimeException( "Failed to find with: " + e.getMessage(), e ) );

			}

			@Override
			public <R2> Mono<PageResult<ResultTuple<E, List<R2>>>> executeLookupAndCount(
					ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();
				// rightBuilder
				return Mono
					.zip(
						fieldBuilder.buildCriteria(), // 왼쪽 match
						rightBuilder.getFieldBuilderCriteria(),
						leftClassMono,
						rightClassMono
					)
					.flatMap( tuple -> {
						Optional<Criteria> leftCriteriaOpt = tuple.getT1();
						Optional<Criteria> rightCriteriaOpt = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();

						String leftCollection = (collectionName != null && ! collectionName.isBlank())
							? collectionName
							: resolveCollectionName( leftClass );

						String rightCollection = (rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank())
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );

						String leftKey = simpleName( leftClass );
						String rightAs = (spec.getAs() != null && ! spec.getAs().isBlank()) ? spec.getAs() : simpleName( rightClass );
						String rightKey = simpleName( rightClass );

						// ===== 공통 스테이지 빌드 =====
						List<AggregationOperation> common = new ArrayList<>();
						leftCriteriaOpt.ifPresent( c -> common.add( Aggregation.match( c ) ) );

						// $lookup
						Document lookupBody = new Document( "from", rightCollection ).append( "as", rightAs );

						// spec.pipelineDocs 분해: $limit(들)은 끝으로 보내기 위해 따로 모아둠
						List<Document> userStages = Optional.ofNullable( spec.getPipelineDocs() ).orElseGet( List::of );
						List<Document> nonLimitStages = new ArrayList<>();
						List<Document> limitStages = new ArrayList<>();

						for (Document st : userStages) {
							if (st.containsKey( "$limit" ))
								limitStages.add( st );
							else
								nonLimitStages.add( st );

						}

						boolean needPipeline = (spec.getLocalField() == null || spec.getForeignField() == null) // 원래 pipeline 모드
							|| rightCriteriaOpt.isPresent() // 오른쪽 추가 필터 있음
							|| ! nonLimitStages.isEmpty() || ! limitStages.isEmpty(); // 사용자가 넣은 stage 있음

						if (! needPipeline) {
							// 단순 모드: 평문 필드명 (접두 $ 넣지 않음)
							lookupBody
								.append( "localField", spec.getLocalField() )
								.append( "foreignField", spec.getForeignField() );

						} else {
							List<Document> pipe = new ArrayList<>();

							// 1) 오른쪽 일반 필터를 먼저 (인덱스 타게)
							rightCriteriaOpt.ifPresent( rc -> pipe.add( new Document( "$match", rc.getCriteriaObject() ) ) );

							// 2) local/foreign 있다면 $expr 조인식 추가 (let 필요)
							if (spec.getLocalField() != null && spec.getForeignField() != null) {
								String lfVar = "vlf"; // 반드시 영문자로 시작
								lookupBody.append( "let", new Document( lfVar, "$" + spec.getLocalField() ) );
								pipe
									.add(
										new Document(
											"$match",
											new Document(
												"$expr",
												new Document( "$eq", Arrays.asList( "$" + spec.getForeignField(), "$$" + lfVar ) )
											)
										)
									);

							} else {
								// let 그대로 유지 (없으면 빈 Document)
								lookupBody.append( "let", Optional.ofNullable( spec.getLetDoc() ).orElseGet( Document::new ) );

							}

							boolean onlyProjects = ! nonLimitStages.isEmpty() && nonLimitStages.stream().allMatch( st -> st.containsKey( "$project" ) );

							if (onlyProjects) {
								// EXISTS 최적화: limit → project (후보를 1건으로 줄인 다음 project)
								pipe.addAll( limitStages );
								pipe.addAll( nonLimitStages );

							} else {
								// 일반 케이스: 기존 순서 유지
								pipe.addAll( nonLimitStages );
								pipe.addAll( limitStages );

							}

							lookupBody.append( "pipeline", pipe );

						}

						AggregationOperation lookupOp = (ctx) -> new Document( "$lookup", lookupBody );
						common.add( lookupOp );

						if (spec.isUnwind()) {
							Document unwind = new Document(
								"$unwind",
								new Document( "path", "$" + rightAs )
									.append( "preserveNullAndEmptyArrays", spec.isPreserveNullAndEmptyArrays() )
							);
							common.add( ctx -> unwind );

						}

						if (spec.getOuterStages() != null && ! spec.getOuterStages().isEmpty()) {

							for (Document st : spec.getOuterStages()) {
								common.add( ctx -> st );

							}

						}

						// ===== data 서브파이프라인 =====
						List<AggregationOperation> dataOps = new ArrayList<>( common );
						dataOps
							.add(
								Aggregation
									.sort(
										(this.sort != null && this.sort.isSorted()) ? this.sort : Sort.by( Sort.Direction.DESC, "_id" )
									)
							);

						if (this.paging != null) {
							dataOps.add( Aggregation.skip( (long) this.paging.pageNumber * this.paging.pageSize ) );
							dataOps.add( Aggregation.limit( this.paging.pageSize ) );

						}

						// 프로젝트: { LeftName: $$ROOT, RightName: $<rightAs> }
						Document project = new Document(
							"$project",
							new Document()
								.append( leftKey, "$$ROOT" )
								.append( rightKey, "$" + rightAs )
						);
						dataOps.add( ctx -> project );

						// ===== count 서브파이프라인 (isCounitng == true일 때만) =====
						List<AggregationOperation> countOps = new ArrayList<>( common );
						// 정렬/페이징/프로젝션 없이, 동일 조건 기준으로 개수만 집계
						countOps.add( Aggregation.count().as( "totalCount" ) );


						// ===== $facet 구성 =====
						FacetOperation facetOp = Aggregation
							.facet( dataOps.toArray( new AggregationOperation[0] ) )
							.as( "data" )
							.and( countOps.toArray( new AggregationOperation[0] ) )
							.as( "count" );

						Aggregation agg = applyAggOptions(
							Aggregation
								.newAggregation( facetOp )
						);


						Mono<Document> facetDocMono = ((collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, leftCollection, Document.class )
							: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class )).next(); // $facet 결과는 1문서

						return facetDocMono.flatMap( facetDoc -> {
							@SuppressWarnings("unchecked")
							List<Document> dataArr = (List<Document>) facetDoc.getOrDefault( "data", List.of() );

							// data 매핑
							List<ResultTuple<E, List<R2>>> data = dataArr.stream().map( d -> {
								@SuppressWarnings("unchecked")
								E leftVal = (E) reactiveMongoTemplate.getConverter().read( leftClass, (Document) d.get( leftKey ) );

								Object rawRight = d.get( rightKey );
								List<R2> rightVal;

								if (rawRight instanceof List<?> rawList) {
									@SuppressWarnings("unchecked")
									List<Document> rightDocs = (List<Document>) rawList;
									rightVal = rightDocs
										.stream()
										.map( x -> reactiveMongoTemplate.getConverter().read( rightClass, x ) )
										.collect( Collectors.toList() );

								} else if (rawRight instanceof Document rd) {
									// unwind(true) 케이스: 단건을 리스트로 래핑
									rightVal = List.of( reactiveMongoTemplate.getConverter().read( rightClass, rd ) );

								} else {
									rightVal = List.of();

								}

								return new ResultTuple<>( leftKey, leftVal, rightKey, rightVal );

							} ).collect( Collectors.toList() );

							Long totalCount = 0L;

							@SuppressWarnings("unchecked")
							List<Document> countArr = (List<Document>) facetDoc.getOrDefault( "count", List.of() );

							if (! countArr.isEmpty()) {
								Object n = countArr.get( 0 ).get( "totalCount" );
								if (n instanceof Number)
									totalCount = ((Number) n).longValue();
								else if (n != null)
									totalCount = Long.parseLong( n.toString() );
								else
									totalCount = 0L;

							}

							return Mono.just( new PageResult<>( data, totalCount ) );

						} );

					} );

			}

			@Override
			public <R2> Flux<ResultTuple<E, List<R2>>> executeLookup(
					ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				// 왼쪽/오른쪽 클래스, 컬렉션명 결정
				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();


				Mono<Aggregation> aggMono = Mono
					.zip(
						fieldBuilder.buildCriteria(), // 왼쪽 match
						rightBuilder.getFieldBuilderCriteria(),
						leftClassMono,
						rightClassMono
					)
					.map( tuple -> {
						Optional<Criteria> leftCriteriaOpt = tuple.getT1();
						Optional<Criteria> rightCriteriaOpt = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();

						// String leftCollection = (collectionName != null && ! collectionName.isBlank())
						// ? collectionName
						// : resolveCollectionName( leftClass );

						String rightCollection = (rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank())
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );

						String leftKey = simpleName( leftClass );
						String rightAs = (spec.getAs() != null && ! spec.getAs().isBlank()) ? spec.getAs() : simpleName( rightClass );
						String rightKey = simpleName( rightClass );

						List<AggregationOperation> ops = new ArrayList<>();
						leftCriteriaOpt.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

						// $lookup 구성
						Document lookupBody = new Document( "from", rightCollection ).append( "as", rightAs );

						// spec.pipelineDocs 분해: $limit(들)은 끝으로 보내기 위해 따로 모아둠
						List<Document> userStages = Optional.ofNullable( spec.getPipelineDocs() ).orElseGet( List::of );
						List<Document> nonLimitStages = new ArrayList<>();
						List<Document> limitStages = new ArrayList<>();

						for (Document st : userStages) {
							if (st.containsKey( "$limit" ))
								limitStages.add( st );
							else
								nonLimitStages.add( st );

						}

						boolean needPipeline = (spec.getLocalField() == null || spec.getForeignField() == null) // 원래 pipeline 모드
							|| rightCriteriaOpt.isPresent() // 오른쪽 추가 필터 있음
							|| ! nonLimitStages.isEmpty() || ! limitStages.isEmpty(); // 사용자가 넣은 stage 있음

						if (! needPipeline) {
							// 단순 모드: 평문 필드명 (접두 $ 넣지 않음)
							lookupBody
								.append( "localField", spec.getLocalField() )
								.append( "foreignField", spec.getForeignField() );

						} else {
							List<Document> pipe = new ArrayList<>();

							// 1) 오른쪽 일반 필터를 먼저 (인덱스 타게)
							rightCriteriaOpt.ifPresent( rc -> pipe.add( new Document( "$match", rc.getCriteriaObject() ) ) );

							// 2) local/foreign 있다면 $expr 조인식 추가 (let 필요)
							if (spec.getLocalField() != null && spec.getForeignField() != null) {
								String lfVar = "vlf"; // 반드시 영문자로 시작
								lookupBody.append( "let", new Document( lfVar, "$" + spec.getLocalField() ) );
								pipe
									.add(
										new Document(
											"$match",
											new Document(
												"$expr",
												new Document( "$eq", Arrays.asList( "$" + spec.getForeignField(), "$$" + lfVar ) )
											)
										)
									);

							} else {
								// let 그대로 유지 (없으면 빈 Document)
								lookupBody.append( "let", Optional.ofNullable( spec.getLetDoc() ).orElseGet( Document::new ) );

							}

							boolean onlyProjects = ! nonLimitStages.isEmpty() && nonLimitStages.stream().allMatch( st -> st.containsKey( "$project" ) );

							if (onlyProjects) {
								// EXISTS 최적화: limit → project (후보를 1건으로 줄인 다음 project)
								pipe.addAll( limitStages );
								pipe.addAll( nonLimitStages );

							} else {
								// 일반 케이스: 기존 순서 유지
								pipe.addAll( nonLimitStages );
								pipe.addAll( limitStages );

							}

							lookupBody.append( "pipeline", pipe );

						}

						AggregationOperation lookupOp = (ctx) -> new Document( "$lookup", lookupBody );
						ops.add( lookupOp );

						if (spec.isUnwind()) {
							Document unwind = new Document(
								"$unwind",
								new Document( "path", "$" + rightAs )
									.append( "preserveNullAndEmptyArrays", spec.isPreserveNullAndEmptyArrays() )
							);
							ops.add( ctx -> unwind );

						}

						if (spec.getOuterStages() != null && ! spec.getOuterStages().isEmpty()) {

							for (Document st : spec.getOuterStages()) {
								ops.add( ctx -> st );

							}

						}

						// 정렬/페이징(왼쪽 기준) 유지
						ops.add( Aggregation.sort( (this.sort != null && this.sort.isSorted()) ? this.sort : Sort.by( Sort.Direction.DESC, "_id" ) ) );

						if (this.paging != null) {
							ops.add( Aggregation.skip( (long) this.paging.pageNumber * this.paging.pageSize ) );
							ops.add( Aggregation.limit( this.paging.pageSize ) );

						}

						// 결과 모양: { LeftName: $$ROOT, RightName: $<rightAs> }
						Document project = new Document(
							"$project",
							new Document()
								.append( leftKey, "$$ROOT" )
								.append( rightKey, "$" + rightAs )
						);
						ops.add( ctx -> project );

						Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );

						return agg;

					} );

				return Mono
					.zip( leftClassMono, rightClassMono, aggMono )
					.flatMapMany( tuple -> {
						Class<E> leftClass = tuple.getT1();
						Class<R2> rightClass = tuple.getT2();
						Aggregation agg = tuple.getT3();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class );

						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );

						return docs.map( d -> {
							@SuppressWarnings("unchecked")
							S leftVal = (S) reactiveMongoTemplate.getConverter().read( leftClass, (Document) d.get( leftKey ) );

							@SuppressWarnings("unchecked")
							List<Document> rightArr = (List<Document>) d.get( rightKey );

							List<R2> rightVal = (rightArr == null) ? List.of()
								: rightArr
									.stream()
									.map( x -> reactiveMongoTemplate.getConverter().read( rightClass, x ) )
									.collect( Collectors.toList() );

							return new ResultTuple<>( leftKey, leftVal, rightKey, rightVal );

						} );

					} );

			}

			@Override
			public Flux<E> execute() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					if (paging != null) {
						query.skip( (long) paging.pageNumber * paging.pageSize ).limit( paging.pageSize );

					}

					query.with( this.sort );

					if (excludes != null && excludes.length != 0) {
						query.fields().exclude( excludes );
						// query.fields().slice( collectionName, 0 );

					}

					applyQueryOptions( query );

					if (excludes != null && excludes.length > 0) {
						var fields = query.fields();
						Arrays
							.stream( excludes )
							.filter( s -> s != null && ! s.isBlank() )
							.forEach( fields::exclude );

					}

					return query;

				} );
				Flux<E> result = Mono
					.zip( executeClassMono, queryMono )
					.flatMapMany( tuple -> {
						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						Flux<? extends E> queryResult = collectionName != null && ! collectionName.isBlank() ? reactiveMongoTemplate.find( query, entityClass, collectionName )
							: reactiveMongoTemplate.find( query, entityClass );
						return queryResult;

					} );

				return result;
				// .onErrorMap( e -> new RuntimeException( "Failed to find with : " + e.getMessage(), e ) );

			}

		}

		public class FindQueryBuilder<S extends E> extends QueryBuilderAccesser<FindExecute<E>, FindAggregation<E>> implements FindExecute<E>, FindAggregation<E> {

			private Sort sort = Sort.unsorted();

			private String[] excludes = null;


			public FindQueryBuilder<S> sorts(
				Order... sorts
			) {

				this.sort = Sort.by( sorts );
				return this;

			}

			public FindQueryBuilder<S> sorts(
				Collection<Order> sorts
			) {

				this.sort = Sort.by( sorts.toArray( Order[]::new ) );
				return this;

			}

			public FindQueryBuilder<S> excludes(
				String... excludes
			) {

				this.excludes = excludes;
				return this;

			}


			public FindQueryBuilder<S> excludes(
				Collection<String> excludes
			) {

				this.excludes = excludes.toArray( String[]::new );
				return this;

			}

			@Override
			public <R2> Mono<ResultTuple<E, R2>> executeLookup(
				ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				// 내부적으로 FindAll과 거의 동일하되, limit(1) 보장
				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();

				Mono<Aggregation> aggMono = Mono
					.zip(
						fieldBuilder.buildCriteria(),
						rightBuilder.getFieldBuilderCriteria(),
						leftClassMono,
						rightClassMono
					)
					.map( tuple -> {
						Optional<Criteria> leftCriteriaOpt = tuple.getT1();
						Optional<Criteria> rightCriteriaOpt = tuple.getT2();
						Class<E> leftClass = tuple.getT3();
						Class<R2> rightClass = tuple.getT4();

						// String leftCollection = (collectionName != null && ! collectionName.isBlank())
						// ? collectionName
						// : resolveCollectionName( leftClass );

						String rightCollection = (rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank())
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );

						String leftKey = simpleName( leftClass );
						String rightAs = (spec.getAs() != null && ! spec.getAs().isBlank()) ? spec.getAs() : simpleName( rightClass );
						String rightKey = simpleName( rightClass );

						List<AggregationOperation> ops = new ArrayList<>();
						leftCriteriaOpt.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

						Document lookupBody = new Document( "from", rightCollection ).append( "as", rightAs );

						// spec.pipelineDocs 분해: $limit(들)은 끝으로 보내기 위해 따로 모아둠
						List<Document> userStages = Optional.ofNullable( spec.getPipelineDocs() ).orElseGet( List::of );
						List<Document> nonLimitStages = new ArrayList<>();
						List<Document> limitStages = new ArrayList<>();

						for (Document st : userStages) {
							if (st.containsKey( "$limit" ))
								limitStages.add( st );
							else
								nonLimitStages.add( st );

						}

						boolean needPipeline = (spec.getLocalField() == null || spec.getForeignField() == null) // 원래 pipeline 모드
							|| rightCriteriaOpt.isPresent() // 오른쪽 추가 필터 있음
							|| ! nonLimitStages.isEmpty() || ! limitStages.isEmpty(); // 사용자가 넣은 stage 있음

						if (! needPipeline) {
							// 단순 모드: 평문 필드명 (접두 $ 넣지 않음)
							lookupBody
								.append( "localField", spec.getLocalField() )
								.append( "foreignField", spec.getForeignField() );

						} else {
							List<Document> pipe = new ArrayList<>();

							// 1) 오른쪽 일반 필터를 먼저 (인덱스 타게)
							rightCriteriaOpt.ifPresent( rc -> pipe.add( new Document( "$match", rc.getCriteriaObject() ) ) );

							// 2) local/foreign 있다면 $expr 조인식 추가 (let 필요)
							if (spec.getLocalField() != null && spec.getForeignField() != null) {
								String lfVar = "vlf"; // 반드시 영문자로 시작
								lookupBody.append( "let", new Document( lfVar, "$" + spec.getLocalField() ) );
								pipe
									.add(
										new Document(
											"$match",
											new Document(
												"$expr",
												new Document( "$eq", Arrays.asList( "$" + spec.getForeignField(), "$$" + lfVar ) )
											)
										)
									);

							} else {
								// let 그대로 유지 (없으면 빈 Document)
								lookupBody.append( "let", Optional.ofNullable( spec.getLetDoc() ).orElseGet( Document::new ) );

							}

							boolean onlyProjects = ! nonLimitStages.isEmpty() && nonLimitStages.stream().allMatch( st -> st.containsKey( "$project" ) );

							if (onlyProjects) {
								// EXISTS 최적화: limit → project (후보를 1건으로 줄인 다음 project)
								pipe.addAll( limitStages );
								pipe.addAll( nonLimitStages );

							} else {
								// 일반 케이스: 기존 순서 유지
								pipe.addAll( nonLimitStages );
								pipe.addAll( limitStages );

							}

							lookupBody.append( "pipeline", pipe );

						}

						ops.add( ctx -> new Document( "$lookup", lookupBody ) );

						if (spec.isUnwind()) {
							ops
								.add(
									ctx -> new Document(
										"$unwind",
										new Document( "path", "$" + rightAs )
											.append( "preserveNullAndEmptyArrays", spec.isPreserveNullAndEmptyArrays() )
									)
								);

						}

						if (spec.getOuterStages() != null && ! spec.getOuterStages().isEmpty()) {

							for (Document st : spec.getOuterStages()) {
								ops.add( ctx -> st );

							}

						}

						// sort + limit(1)
						ops.add( Aggregation.sort( (this.sort != null && this.sort.isSorted()) ? this.sort : Sort.by( Sort.Direction.DESC, "_id" ) ) );
						ops.add( Aggregation.limit( 1 ) );

						Document project = new Document(
							"$project",
							new Document()
								.append( leftKey, "$$ROOT" )
								.append( rightKey, "$" + rightAs )
						);
						ops.add( ctx -> project );

						Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );


						// agg.withOptions( Aggregation.newAggregationOptions().allowDiskUse( false ).build() );
						return agg;

					} );

				return Mono
					.zip( leftClassMono, rightClassMono, aggMono )
					.flatMap( tuple -> {
						Class<E> leftClass = tuple.getT1();
						Class<R2> rightClass = tuple.getT2();
						Aggregation agg = tuple.getT3();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class );

						String leftKey = simpleName( leftClass );
						String rightKey = simpleName( rightClass );

						return docs.next().map( d -> {
							@SuppressWarnings("unchecked")
							S leftVal = (S) reactiveMongoTemplate.getConverter().read( leftClass, (Document) d.get( leftKey ) );

							Object raw = d.get( rightKey );
							R2 rightVal = null;

							if (raw instanceof Document rd) {
								rightVal = reactiveMongoTemplate.getConverter().read( rightClass, rd );

							} else if (raw instanceof List<?> rl && ! rl.isEmpty() && rl.get( 0 ) instanceof Document r0) {
								rightVal = reactiveMongoTemplate.getConverter().read( rightClass, r0 ); // 첫 원소

							}

							return new ResultTuple<>( leftKey, leftVal, rightKey, rightVal );

						} );

					} );

			}

			@Override
			public Mono<E> execute() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					query.with( this.sort );


					applyQueryOptions( query );


					if (excludes != null && excludes.length > 0) {
						var fields = query.fields();
						Arrays
							.stream( excludes )
							.filter( s -> s != null && ! s.isBlank() )
							.forEach( fields::exclude );

					}

					return query;

				} );
				Mono<E> result = Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> {
						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						if (collectionName != null && ! collectionName.isBlank())
							return reactiveMongoTemplate.findOne( query, entityClass, collectionName );
						else
							return reactiveMongoTemplate.findOne( query, entityClass );


					} );
				return result;// .onErrorMap( e -> new RuntimeException( "Failed to find by fields: " + e.getMessage(), e ) );

			}

			@Override
			public Mono<E> executeFirst() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					query.limit( 1 );
					query.with( sort );

					applyQueryOptions( query );

					if (excludes != null && excludes.length > 0) {
						var fields = query.fields();
						Arrays
							.stream( excludes )
							.filter( s -> s != null && ! s.isBlank() )
							.forEach( fields::exclude );

					}

					return query;

				} );

				Mono<E> result = Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> {
						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						if (collectionName != null && ! collectionName.isBlank())
							return reactiveMongoTemplate.findOne( query, entityClass, collectionName );
						else
							return reactiveMongoTemplate.findOne( query, entityClass );


					} );
				return result
					.doOnError( e -> {
						e.printStackTrace();

					} );

			}

			@Override
			public Mono<E> executeAggregation() {

				Mono<Aggregation> aggregationMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					List<AggregationOperation> ops = new ArrayList<>();

					// where 절 ($match)
					criteriaOptional.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

					// 정렬
					ops
						.add(
							Aggregation
								.sort(
									(this.sort != null && this.sort.isSorted())
										? this.sort
										: Sort.by( Sort.Direction.DESC, "_id" )
								)
						);

					// 단건만
					ops.add( Aggregation.limit( 1 ) );

					// 프로젝트 (exclude)
					if (excludes != null && excludes.length > 0) {
						ops.add( Aggregation.project().andExclude( excludes ) );

					}

					Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );

					return agg;

				} );

				return Mono
					.zip( executeClassMono, aggregationMono )
					.flatMap( tuple -> {
						Class<E> entityClass = tuple.getT1();
						Aggregation aggregation = tuple.getT2();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( aggregation, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( aggregation, entityClass, Document.class );

						// 첫 문서를 엔티티로 매핑 (없으면 empty Mono)
						return docs.next().map( doc -> reactiveMongoTemplate.getConverter().read( entityClass, doc ) );

					} );

			}

		}

		public class CountQueryBuilder extends QueryBuilderAccesser<CountExecute<E>, CountAggregation<E>> implements CountExecute<E>, CountAggregation<E> {


			@Override
			public <R2> Mono<ResultTuple<Long, Long>> executeLookup(
					ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();

				Mono<Aggregation> aggMono = Mono
					.zip(
						fieldBuilder.buildCriteria(),
						rightBuilder.getFieldBuilderCriteria(),
						leftClassMono,
						rightClassMono
					)
					.map( tp -> {
						Optional<Criteria> leftMatch = tp.getT1();
						Optional<Criteria> rightMatch = tp.getT2();
						Class<E> leftClass = tp.getT3();
						Class<R2> rightClass = tp.getT4();

						String rightColl = (rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank())
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );

						// String leftKey = simpleName( leftClass );
						String rightAs = (spec.getAs() != null && ! spec.getAs().isBlank()) ? spec.getAs() : simpleName( rightClass );
						// String rightKey = simpleName( rightClass ); // 이름만 쓸거라 키로도 사용

						List<AggregationOperation> ops = new ArrayList<>();
						leftMatch.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

						// $lookup
						Document lk = new Document( "from", rightColl ).append( "as", rightAs );
						// spec.pipelineDocs 분해: $limit(들)은 끝으로 보내기 위해 따로 모아둠
						List<Document> userStages = Optional.ofNullable( spec.getPipelineDocs() ).orElseGet( List::of );
						List<Document> nonLimitStages = new ArrayList<>();
						List<Document> limitStages = new ArrayList<>();

						for (Document st : userStages) {
							if (st.containsKey( "$limit" ))
								limitStages.add( st );
							else
								nonLimitStages.add( st );

						}

						boolean needPipeline = (spec.getLocalField() == null || spec.getForeignField() == null) // 원래 pipeline 모드
							|| rightMatch.isPresent() // 오른쪽 추가 필터 있음
							|| ! nonLimitStages.isEmpty() || ! limitStages.isEmpty(); // 사용자가 넣은 stage 있음

						if (! needPipeline) {
							// 단순 모드: 평문 필드명 (접두 $ 넣지 않음)
							lk
								.append( "localField", spec.getLocalField() )
								.append( "foreignField", spec.getForeignField() );

						} else {
							List<Document> pipe = new ArrayList<>();

							// 1) 오른쪽 일반 필터를 먼저 (인덱스 타게)
							rightMatch.ifPresent( rc -> pipe.add( new Document( "$match", rc.getCriteriaObject() ) ) );

							// 2) local/foreign 있다면 $expr 조인식 추가 (let 필요)
							if (spec.getLocalField() != null && spec.getForeignField() != null) {
								String lfVar = "vlf"; // 반드시 영문자로 시작
								lk.append( "let", new Document( lfVar, "$" + spec.getLocalField() ) );
								pipe
									.add(
										new Document(
											"$match",
											new Document(
												"$expr",
												new Document( "$eq", Arrays.asList( "$" + spec.getForeignField(), "$$" + lfVar ) )
											)
										)
									);

							} else {
								// let 그대로 유지 (없으면 빈 Document)
								lk.append( "let", Optional.ofNullable( spec.getLetDoc() ).orElseGet( Document::new ) );

							}

							boolean onlyProjects = ! nonLimitStages.isEmpty() && nonLimitStages.stream().allMatch( st -> st.containsKey( "$project" ) );

							if (onlyProjects) {
								// EXISTS 최적화: limit → project (후보를 1건으로 줄인 다음 project)
								pipe.addAll( limitStages );
								pipe.addAll( nonLimitStages );

							} else {
								// 일반 케이스: 기존 순서 유지
								pipe.addAll( nonLimitStages );
								pipe.addAll( limitStages );

							}

							lk.append( "pipeline", pipe );

						}

						ops.add( ctx -> new Document( "$lookup", lk ) );

						if (spec.isUnwind()) {
							ops
								.add(
									ctx -> new Document(
										"$unwind",
										new Document( "path", "$" + rightAs )
											.append( "preserveNullAndEmptyArrays", spec.isPreserveNullAndEmptyArrays() )
									)
								);

						}

						if (spec.getOuterStages() != null) {
							for (Document st : spec.getOuterStages())
								ops.add( ctx -> st ); // ← lookup 이후 필터

						}

						if (spec.isUnwind()) {

							// 그룹으로 왼쪽/오른쪽 카운트 동시 계산
							Document group = new Document(
								"$group",
								new Document( "_id", null )
									.append( "leftCount", new Document( "$sum", 1 ) )
									.append(
										"rightCount",
										new Document(
											"$sum",
											new Document(
												"$cond",
												List
													.of(
														new Document( "$ifNull", List.of( "$" + rightAs, null ) ),
														1,
														0
													)
											)
										)
									)
							);
							ops.add( ctx -> group );

						} else {
							// 배열 크기를 더해서 오른쪽 총 매칭 수를 계산
							Document setSize = new Document(
								"$set",
								new Document(
									"_rightSize",
									new Document(
										"$size",
										new Document( "$ifNull", List.of( "$" + rightAs, List.of() ) )
									)
								)
							);
							ops.add( ctx -> setSize );

							Document group = new Document(
								"$group",
								new Document( "_id", null )
									.append( "leftCount", new Document( "$sum", 1 ) )
									.append( "rightCount", new Document( "$sum", "$_rightSize" ) )
							);
							ops.add( ctx -> group );

						}

						Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );

						// agg.withOptions( Aggregation.newAggregationOptions().allowDiskUse( false ).build() );
						return agg;

					} );

				return Mono
					.zip( leftClassMono, rightClassMono, aggMono )
					.flatMap( tp -> {
						Class<E> leftClass = tp.getT1();
						Class<R2> rightClass = tp.getT2();
						Aggregation agg = tp.getT3();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class );

						String leftName = simpleName( leftClass );
						String rightName = simpleName( rightClass );

						return docs
							.singleOrEmpty()
							.map( d -> {
								long lc = Optional.ofNullable( d.get( "leftCount", Number.class ) ).map( Number::longValue ).orElse( 0L );
								long rc = Optional.ofNullable( d.get( "rightCount", Number.class ) ).map( Number::longValue ).orElse( 0L );
								return new ResultTuple<>( leftName, lc, rightName, rc );

							} )
							.defaultIfEmpty( new ResultTuple<>( leftName, 0L, rightName, 0L ) );

					} );

			}

			@Override
			public Mono<Long> execute() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					applyQueryOptions( query );

					return query;

				} );
				return Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> {

						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						if (collectionName != null && ! collectionName.isBlank())
							return reactiveMongoTemplate.count( query, entityClass, collectionName );
						else
							return reactiveMongoTemplate.count( query, entityClass );

					} )
				// .onErrorMap( e -> new RuntimeException( "Failed to count documents: " + e.getMessage(), e ) )
				;

			}

			@Override
			public Mono<Long> executeAggregation() {

				Mono<Aggregation> aggMono = fieldBuilder.buildCriteria().map( criteriaOpt -> {
					List<AggregationOperation> ops = new ArrayList<>();

					// where 절($match)
					criteriaOpt.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

					// 카운트
					ops.add( ctx -> new Document( "$count", "count" ) );

					Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );

					return agg;

				} );

				return Mono
					.zip( executeClassMono, aggMono )
					.flatMap( tuple -> {
						Class<E> entityClass = tuple.getT1();
						Aggregation aggregation = tuple.getT2();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( aggregation, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( aggregation, entityClass, Document.class );

						return docs
							.singleOrEmpty()
							.map( d -> {
								Number n = d.get( "count", Number.class );
								return (n == null) ? 0L : n.longValue();

							} )
							.defaultIfEmpty( 0L );

					} );

			}

		}

		public class DeleteQueryBuilder {

			public Mono<DeleteResult> execute() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					return query;

				} );
				return Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> {
						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						if (collectionName != null && ! collectionName.isBlank())
							return reactiveMongoTemplate.remove( query, entityClass, collectionName );
						else
							return reactiveMongoTemplate.remove( query, entityClass );

					} )
				// .onErrorMap( e -> new RuntimeException( "Failed to delete documents: " + e.getMessage(), e ) )
				;

			}

		}

		public class ExistsQueryBuilder extends QueryBuilderAccesser<ExistsExecute<E>, ExistsAggregation<E>> implements ExistsExecute<E>, ExistsAggregation<E> {

			@Override
			public <R2> Mono<ResultTuple<Boolean, Boolean>> executeLookup(
					ReactiveMongoDsl<E>.AbstractQueryBuilder<R2, ?>.FindAllQueryBuilder<R2> rightBuilder, LookupSpec spec
			) {

				Mono<Class<E>> leftClassMono = executeClassMono;
				Mono<Class<R2>> rightClassMono = rightBuilder.getExecuteClassMono();

				Mono<Aggregation> aggMono = Mono
					.zip(
						fieldBuilder.buildCriteria(),
						rightBuilder.getFieldBuilderCriteria(),
						leftClassMono,
						rightClassMono
					)
					.map( tp -> {
						Optional<Criteria> leftMatch = tp.getT1();
						Optional<Criteria> rightMatch = tp.getT2();
						// Class<E> leftClass = tp.getT3();
						Class<R2> rightClass = tp.getT4();

						String rightColl = (rightBuilder.getCollectionName() != null && ! rightBuilder.getCollectionName().isBlank())
							? rightBuilder.getCollectionName()
							: rightBuilder.resolveCollectionName( rightClass );

						String rightAs = (spec.getAs() != null && ! spec.getAs().isBlank()) ? spec.getAs() : simpleName( rightClass );

						List<AggregationOperation> ops = new ArrayList<>();
						leftMatch.ifPresent( c -> ops.add( Aggregation.match( c ) ) );

						Document lk = new Document( "from", rightColl ).append( "as", rightAs );


						// spec.pipelineDocs 분해: $limit(들)은 끝으로 보내기 위해 따로 모아둠
						List<Document> userStages = Optional.ofNullable( spec.getPipelineDocs() ).orElseGet( List::of );
						List<Document> nonLimitStages = new ArrayList<>();
						List<Document> limitStages = new ArrayList<>();

						for (Document st : userStages) {
							if (st.containsKey( "$limit" ))
								limitStages.add( st );
							else
								nonLimitStages.add( st );

						}

						boolean needPipeline = (spec.getLocalField() == null || spec.getForeignField() == null) // 원래 pipeline 모드
							|| rightMatch.isPresent() // 오른쪽 추가 필터 있음
							|| ! nonLimitStages.isEmpty() || ! limitStages.isEmpty(); // 사용자가 넣은 stage 있음

						if (! needPipeline) {
							// 단순 모드: 평문 필드명 (접두 $ 넣지 않음)
							lk
								.append( "localField", spec.getLocalField() )
								.append( "foreignField", spec.getForeignField() );

						} else {
							List<Document> pipe = new ArrayList<>();

							// 1) 오른쪽 일반 필터를 먼저 (인덱스 타게)
							rightMatch.ifPresent( rc -> pipe.add( new Document( "$match", rc.getCriteriaObject() ) ) );

							// 2) local/foreign 있다면 $expr 조인식 추가 (let 필요)
							if (spec.getLocalField() != null && spec.getForeignField() != null) {
								String lfVar = "vlf"; // 반드시 영문자로 시작
								lk.append( "let", new Document( lfVar, "$" + spec.getLocalField() ) );
								pipe
									.add(
										new Document(
											"$match",
											new Document(
												"$expr",
												new Document( "$eq", Arrays.asList( "$" + spec.getForeignField(), "$$" + lfVar ) )
											)
										)
									);

							} else {
								// let 그대로 유지 (없으면 빈 Document)
								lk.append( "let", Optional.ofNullable( spec.getLetDoc() ).orElseGet( Document::new ) );

							}

							boolean onlyProjects = ! nonLimitStages.isEmpty() && nonLimitStages.stream().allMatch( st -> st.containsKey( "$project" ) );

							if (onlyProjects) {
								// EXISTS 최적화: limit → project (후보를 1건으로 줄인 다음 project)
								pipe.addAll( limitStages );
								pipe.addAll( nonLimitStages );

							} else {
								// 일반 케이스: 기존 순서 유지
								pipe.addAll( nonLimitStages );
								pipe.addAll( limitStages );

							}

							lk.append( "pipeline", pipe );

						}

						ops.add( ctx -> new Document( "$lookup", lk ) );

						if (spec.isUnwind()) {
							ops
								.add(
									ctx -> new Document(
										"$unwind",
										new Document( "path", "$" + rightAs )
											.append( "preserveNullAndEmptyArrays", spec.isPreserveNullAndEmptyArrays() )
									)
								);

						}

						if (spec.getOuterStages() != null) {
							for (Document st : spec.getOuterStages())
								ops.add( ctx -> st ); // ← lookup 이후 필터

						}

						// 오른쪽 존재 플래그 계산
						Document rightExistsExpr = spec.isUnwind()
							? new Document( "$gt", List.of( new Document( "$type", "$" + rightAs ), "missing" ) )
							: new Document(
								"$gt",
								List
									.of(
										new Document(
											"$size",
											new Document( "$ifNull", List.of( "$" + rightAs, List.of() ) )
										),
										0
									)
							);

						ops
							.add(
								ctx -> new Document(
									"$project",
									new Document( "_rightExists", rightExistsExpr )
								)
							);

						ops.add( Aggregation.limit( 1 ) ); // 왼쪽 존재여부 판정

						Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );

						agg.withOptions( Aggregation.newAggregationOptions().allowDiskUse( false ).build() );
						return agg;

					} );

				return Mono
					.zip( leftClassMono, rightClassMono, aggMono )
					.flatMap( tp -> {
						Class<E> leftClass = tp.getT1();
						Class<R2> rightClass = tp.getT2();
						Aggregation agg = tp.getT3();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( agg, leftClass, Document.class );

						String leftName = simpleName( leftClass );
						String rightName = simpleName( rightClass );

						Mono<Document> firstDocMono = docs.next();

						return firstDocMono
							.map( d -> {
								boolean rightExists = Optional.ofNullable( d.get( "_rightExists", Boolean.class ) ).orElse( false );
								return new ResultTuple<>( leftName, true, rightName, rightExists );

							} )
							.defaultIfEmpty( new ResultTuple<>( leftName, false, rightName, false ) );

					} );

			}

			@Override
			public Mono<Boolean> execute() {

				var queryMono = fieldBuilder.buildCriteria().map( criteriaOptional -> {
					Query query = new Query();

					if (criteriaOptional.isPresent()) {
						query.addCriteria( criteriaOptional.get() );

					}

					applyQueryOptions( query );

					return query;

				} );
				return Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tuple -> {
						var entityClass = tuple.getT1();
						var query = tuple.getT2();
						if (collectionName != null && ! collectionName.isBlank())
							return reactiveMongoTemplate.exists( query, entityClass, collectionName );
						else
							return reactiveMongoTemplate.exists( query, entityClass );

					} )
				// .onErrorMap( e -> new RuntimeException( "Failed to check existence: " + e.getMessage(), e ) )
				;

			}

			@Override
			public Mono<Boolean> executeAggregation() {

				Mono<Aggregation> aggMono = fieldBuilder.buildCriteria().map( criteriaOpt -> {
					List<AggregationOperation> ops = new ArrayList<>();
					criteriaOpt.ifPresent( c -> ops.add( Aggregation.match( c ) ) );
					ops.add( Aggregation.limit( 1 ) ); // 한 건만 있으면 true
					Aggregation agg = applyAggOptions( Aggregation.newAggregation( ops ) );
					return agg;

				} );

				return Mono
					.zip( executeClassMono, aggMono )
					.flatMap( tp -> {
						Class<E> entityClass = tp.getT1();
						Aggregation agg = tp.getT2();

						Flux<Document> docs = (collectionName != null && ! collectionName.isBlank())
							? reactiveMongoTemplate.aggregate( agg, collectionName, Document.class )
							: reactiveMongoTemplate.aggregate( agg, entityClass, Document.class );

						return docs.hasElements(); // 있으면 true

					} );

			}

		}

		public class AtomicUpdateQueryBuilder {

			private boolean multi = false;

			private boolean upsert = false;

			private final DocumentSpec doc = new DocumentSpec();

			private final PipelineSpec pipe = new PipelineSpec();

			// 공통 옵션
			public AtomicUpdateQueryBuilder multi() {

				this.multi = true;
				return this;

			}

			public AtomicUpdateQueryBuilder first() {

				this.multi = false;
				return this;

			}

			public AtomicUpdateQueryBuilder upsert() {

				this.upsert = true;
				return this;

			}

			// -------------------------
			// Document(Update) 연산들
			// -------------------------
			public AtomicUpdateQueryBuilder inc(
				String field, Number delta
			) {

				doc.inc( field, delta );
				return this;

			}

			public AtomicUpdateQueryBuilder set(
				String field, Object value
			) {

				doc.set( field, value );
				return this;

			}

			public AtomicUpdateQueryBuilder setOnInsert(
				String field, Object value
			) {

				doc.setOnInsert( field, value );
				return this;

			}

			public AtomicUpdateQueryBuilder unset(
				String field
			) {

				doc.unset( field );
				return this;

			}

			public AtomicUpdateQueryBuilder push(
				String field, Object value
			) {

				doc.push( field, value );
				return this;

			}

			public AtomicUpdateQueryBuilder addToSet(
				String field, Object value
			) {

				doc.addToSet( field, value );
				return this;

			}

			public AtomicUpdateQueryBuilder pull(
				String field, Object value
			) {

				doc.pull( field, value );
				return this;

			}

			// -------------------------
			// Pipeline(AggregationUpdate) 연산들
			// (이름을 구분하거나, pipelineXXX로 두는게 안전)
			// -------------------------
			public AtomicUpdateQueryBuilder pipelineSet(
				String field, Object valueOrExpr
			) {

				pipe.set( field, valueOrExpr );
				return this;

			}

			public AtomicUpdateQueryBuilder pipelineInc(
				String field, Number delta
			) {

				pipe.inc( field, delta );
				return this;

			}

			public AtomicUpdateQueryBuilder pipelineUnset(
				String... fields
			) {

				pipe.unset( fields );
				return this;

			}

			public AtomicUpdateQueryBuilder stage(
				Document stage
			) {

				pipe.stage( stage );
				return this;

			}

			public AtomicUpdateQueryBuilder nextStage() {

				pipe.nextStage();
				return this;

			}

			// -------------------------
			// execute 분기
			// -------------------------
			public Mono<UpdateResult> execute() {

				UpdateDefinition ud = doc.build();
				if (doc.isEmpty())
					return Mono.error( new IllegalStateException( "No document update specified." ) );
				return doExecute( ud );

			}

			public Mono<UpdateResult> executeAggregation() {

				UpdateDefinition ud = pipe.build();
				if (pipe.isEmpty())
					return Mono.error( new IllegalStateException( "No pipeline update specified." ) );
				return doExecute( ud );

			}

			private Mono<UpdateResult> doExecute(
				UpdateDefinition updateDef
			) {

				Mono<Query> queryMono = fieldBuilder.buildCriteria().map( opt -> {
					Query q = new Query();
					opt.ifPresent( q::addCriteria );
					// applyQueryOptions( q );
					return q;

				} );

				return Mono
					.zip( executeClassMono, queryMono )
					.flatMap( tp -> {
						Class<E> entityClass = tp.getT1();
						Query query = tp.getT2();

						boolean hasCollection = (collectionName != null && ! collectionName.isBlank());

						if (hasCollection) {
							if (upsert)
								return reactiveMongoTemplate.upsert( query, updateDef, entityClass, collectionName );
							if (multi)
								return reactiveMongoTemplate.updateMulti( query, updateDef, entityClass, collectionName );
							return reactiveMongoTemplate.updateFirst( query, updateDef, entityClass, collectionName );

						} else {
							if (upsert)
								return reactiveMongoTemplate.upsert( query, updateDef, entityClass );
							if (multi)
								return reactiveMongoTemplate.updateMulti( query, updateDef, entityClass );
							return reactiveMongoTemplate.updateFirst( query, updateDef, entityClass );

						}

					} );

			}

			// -------------------------
			// 내부 Spec
			// -------------------------
			private class DocumentSpec {

				private final Update update = new Update();

				void inc(
					String f, Number d
				) {

					update.inc( requireField( f ), d );

				}

				void set(
					String f, Object v
				) {

					update.set( requireField( f ), v );

				}

				void setOnInsert(
					String f, Object v
				) {

					update.setOnInsert( requireField( f ), v );

				}

				void unset(
					String f
				) {

					update.unset( requireField( f ) );

				}

				void push(
					String f, Object v
				) {

					update.push( requireField( f ), v );

				}

				void addToSet(
					String f, Object v
				) {

					update.addToSet( requireField( f ), v );

				}

				void pull(
					String f, Object v
				) {

					update.pull( requireField( f ), v );

				}

				UpdateDefinition build() {

					return update;

				}

				boolean isEmpty() { return update.getUpdateObject() == null || update.getUpdateObject().isEmpty(); }

			}

			private class PipelineSpec {

				private final List<AggregationOperation> pipeline = new ArrayList<>();

				private Document pendingSet = new Document();

				void set(
					String f, Object vOrExpr
				) {

					pendingSet.put( requireField( f ), vOrExpr );

				}

				void inc(
					String f, Number d
				) {

					String ff = requireField( f );
					Document expr = new Document( "$add", List.of( new Document( "$ifNull", List.of( "$" + ff, 0 ) ), d ) );
					set( ff, expr );

				}

				void unset(
					String... fields
				) {

					flushSet();
					List<String> keys = Arrays.stream( fields ).filter( Objects::nonNull ).map( String::trim ).filter( s -> ! s.isBlank() ).toList();
					if (! keys.isEmpty())
						pipeline.add( ctx -> new Document( "$unset", keys ) );

				}

				void stage(
					Document st
				) {

					flushSet();
					if (st != null && ! st.isEmpty())
						pipeline.add( ctx -> new Document( st ) );

				}

				void nextStage() {

					flushSet();

				}

				UpdateDefinition build() {

					flushSet();
					return AggregationUpdate.from( pipeline );

				}

				boolean isEmpty() {

					flushSet();
					return pipeline.isEmpty();

				}

				private void flushSet() {

					if (pendingSet != null && ! pendingSet.isEmpty()) {
						Document st = new Document( "$set", new Document( pendingSet ) );
						pipeline.add( ctx -> st );
						pendingSet = new Document();

					}

				}

			}

			private String requireField(
				String field
			) {

				if (field == null || field.isBlank())
					throw new IllegalArgumentException( "field must not be null/blank" );
				return field;

			}

		}



	}


	public class ExecuteRepositoryBuilder<E> extends AbstractQueryBuilder<E, ExecuteRepositoryBuilder<E>> implements ExecuteBuilder {

		// private final Class<? extends ReactiveCrudRepository<?, ?>> repositoryClass;

		ExecuteRepositoryBuilder(
									K key,
									Class<? extends ReactiveCrudRepository<?, ?>> repositoryClass
		) {

			this.repositoryClass = repositoryClass;
			this.reactiveMongoTemplate = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.executeClassMono = extractEntityClass( repositoryClass );
			this.executeBuilder = this;

		}

	}


	public class ExecuteEntityBuilder<E> extends AbstractQueryBuilder<E, ExecuteEntityBuilder<E>> implements ExecuteBuilder {

		@SuppressWarnings("unchecked")
		ExecuteEntityBuilder(
								K key
		) {

			this.executeClassMono = Mono
				.just(
					(Class<E>) ((ParameterizedType) getClass()
						.getGenericSuperclass()).getActualTypeArguments()[0]
				);

			// 🔥 핵심: applicationContext 대신 resolver를 통해 템플릿 획득
			this.reactiveMongoTemplate = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.executeBuilder = this;

		}

		ExecuteEntityBuilder(
								Class<E> executeClass,
								K key
		) {

			this.executeClassMono = Mono.just( executeClass );
			this.reactiveMongoTemplate = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.executeBuilder = this;

		}

	}


	public class ExecuteCustomClassBuilder<E> extends AbstractQueryBuilder<E, ExecuteCustomClassBuilder<E>> implements ExecuteBuilder {

		@SuppressWarnings("unchecked")
		ExecuteCustomClassBuilder(
									K key,
									String collectionName
		) {

			this.executeClassMono = Mono
				.just(
					(Class<E>) ((ParameterizedType) getClass()
						.getGenericSuperclass()).getActualTypeArguments()[0]
				);

			this.reactiveMongoTemplate = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.collectionName = collectionName;
			this.executeBuilder = this;

		}

		ExecuteCustomClassBuilder(
									Class<E> executeClass,
									K key,
									String collectionName
		) {

			this.executeClassMono = Mono.just( executeClass );
			this.reactiveMongoTemplate = ReactiveMongoDsl.this.getMongoTemplate( key );
			this.collectionName = collectionName;
			this.executeBuilder = this;

		}

	}

	public <E> ExecuteRepositoryBuilder<E> executeRepository(
		K key, Class<? extends ReactiveCrudRepository<?, ?>> repositoryClass
	) {

		return new ExecuteRepositoryBuilder<>( key, repositoryClass );

	}

	public <E> ExecuteEntityBuilder<E> executeEntity(
		K key
	) {

		return new ExecuteEntityBuilder<>( key );

	}

	public <E> ExecuteEntityBuilder<E> executeEntity(
		Class<E> executeEntity, K key
	) {

		return new ExecuteEntityBuilder<>( executeEntity, key );

	}

	public <E> ExecuteCustomClassBuilder<E> executeCustomClass(
		Class<E> executeCustomClass, K key, String collectionName
	) {

		return new ExecuteCustomClassBuilder<>( executeCustomClass, key, collectionName );

	}

	public <E> ExecuteCustomClassBuilder<E> executeCustomClass(
		K key, String collectionName
	) {

		return new ExecuteCustomClassBuilder<>( key, collectionName );

	}

	

}
