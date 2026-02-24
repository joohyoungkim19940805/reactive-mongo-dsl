# Reactive Mongo DSL (reactive-mongo-dsl)

Spring Data **ReactiveMongoTemplate** 기반으로, **동적 Criteria / Aggregation / $lookup / 원자적 업데이트 / Bulk 작업**을 “체이닝 DSL”로 묶은 유틸리티입니다.

---

## 주요 컨셉

### 1) 템플릿/트랜잭션 라우팅: `MongoTemplateResolver<K>`

`ReactiveMongoDsl<K>`는 내부에서 `MongoTemplateResolver<K>`를 통해 **key(K)** 별 `ReactiveMongoTemplate` / 트랜잭션 오퍼레이터를 가져옵니다.
즉, 멀티 DB/멀티 템플릿 환경에서도 동일한 DSL을 유지할 수 있습니다.

### 2) Query 흐름: `execute* → fields(...) → end() → find/findAll/count/delete/exists/atomicUpdate`

보통 아래 흐름으로 사용합니다.

1. `executeEntity(...)` 또는 `executeRepository(...)` 또는 `executeCustomClass(...)`로 “실행 컨텍스트” 선택
2. `fields(...)`로 where 조건 쌓기
3. `end()`로 쿼리 빌더로 전환
4. `find() / findAll() / count() / delete() / exists() / atomicUpdate()` 선택 후 `execute*()` 실행

### 3) 조건 표현: `FieldsPair` (+ `Condition`)

`FieldsPair.pair(field, value[, Condition])` 형태로 조건을 표현하고, 내부에서 `MongoCriteriaSupport`가 `Criteria`로 변환합니다.

---

## 요구사항

- Java 17+
- Spring Data MongoDB Reactive / Project Reactor

---

## 빠른 시작

### 1) `MongoTemplateResolver` 구현 예시

```java
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;

public enum MongoKey { FRONT, BACK }

@Component
public class MyMongoTemplateResolver implements MongoTemplateResolver<MongoKey> {
  private final ReactiveMongoTemplate front;
  private final ReactiveMongoTemplate back;
  private final TransactionalOperator frontTx;
  private final TransactionalOperator backTx;

  public MyMongoTemplateResolver(
      ReactiveMongoTemplate front,
      ReactiveMongoTemplate back,
      TransactionalOperator frontTx,
      TransactionalOperator backTx
  ) {
    this.front = front;
    this.back = back;
    this.frontTx = frontTx;
    this.backTx = backTx;
  }

  @Override
  public ReactiveMongoTemplate getTemplate(MongoKey key) {
    return (key == MongoKey.BACK) ? back : front;
  }

  @Override
  public TransactionalOperator getTxOperator(MongoKey key) {
    return (key == MongoKey.BACK) ? backTx : frontTx;
  }
}
```

```java

@Configuration
public class ReactiveMongoDslConfig {

	@Bean
	public ReactiveMongoDsl<MongoTemplateName> mongoQueryBuilder(
		MongoTemplateResolver<MongoTemplateName> resolver
	) {

		return new ReactiveMongoDsl<>( resolver );

	}


}

	@Autowired
	private ReactiveMongoDsl<MongoTemplateName> dsl;
	

	var testEntityDsl = dsl.executeEntity( TestEntity.class, MongoTemplateName.FRONT );
	Mono<TestResponse> testResponse = testEntityDsl
		.fields()
		.and( (builder) -> builder.fields( FieldsPair.pair( "key", "helloWorld", Condition.eq ) ) )
		.end()
		.find()
		.execute()
		.map(
			e -> TestResponse
				.builder()
				.id( e.getId() )
				.key( e.getKey() )
				.comment( e.getComment() )
				.version( e.getVersion() )
				.build()
		);
```

---

## 기본 조회

### 1) findAll (Flux) / find (Mono)

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

Flux<User> users =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(
        pair("status", "ACTIVE"),
        pair("age", 20, gte),
        pair("name", "kim", like)
     )
     .end()
     .findAll()
     .execute();

Mono<User> one =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("_id", userId))
     .end()
     .find()
     .execute();      // 0~1건

Mono<User> first =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("status", "ACTIVE"))
     .end()
     .find()
     .executeFirst(); // 0~N 중 첫 건
```

### 2) OR/AND/NOT 그룹핑

`FieldBuilder`는 `and / or / not / notAny / notAll` 블록을 지원합니다.

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

Flux<User> users =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields() // 루트 AND
       .and(f -> f.fields(
           pair("status", List.of("ACTIVE_1","ACTIVE_2"), in),
           pair("age", 20, gte)
       ))
       .or(f -> f.fields(
           pair("name", "kim", like),
           pair("name", "lee", like)
       ))
       .notAny(f -> f.fields( // NOT(OR(...)) 용도
           pair("banned", true)
       ))
     .end()
     .findAll()
     .execute();
```

---

## 페이징 + count (Aggregation)

`findAll().paging(pageNumber, pageSize)`가 설정된 상태에서 `executeAggregation()`을 호출하면,
내부적으로 `$facet(data, totalCount)`를 구성해 `PageResult<E>`를 반환합니다.

```java
import org.springframework.data.domain.Sort.Order;

Mono<PageResult<User>> page =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("status", "ACTIVE"))
     .end()
     .findAll()
       .paging(0, 20) // 0-based
       .sorts(Order.desc("_id"))
     .executeAggregation();
```

---

## count / exists / delete

```java
Mono<Long> cnt =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("status", "ACTIVE"))
     .end()
     .count()
     .execute();

Mono<Boolean> exists =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("_id", userId))
     .end()
     .exists()
     .execute();

Mono<DeleteResult> del =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("_id", userId))
     .end()
     .delete()
     .execute();
```

---

## $lookup 조인

### 1) `LookupSpec`로 join 조건 구성

- `localField/foreignField` 기반의 간단 모드
- 혹은 `let + pipeline + $expr` 기반의 조건 모드 (`bindConditionFields`, `bindConditionConst`, `bindConditionBetween`, `bindConditionLike`, `bindConditionExists` 등)
- 필요 시 `unwind(preserveNullAndEmptyArrays)`로 단건화/outer join 형태 제어
- `outerStage(s)`로 `$lookup` 이후 추가 스테이지를 덧붙일 수 있음

```java
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

LookupSpec spec = LookupSpec.builder()
  .as("orders")
  .bindConditionFields("_id", eq, "userId")     // left._id == right.userId
  .bindConditionConst("DONE", eq, "status")    // right.status == "DONE"
  .limit(10)
  // .unwind(true) // preserveNullAndEmptyArrays=true -> LEFT OUTER JOIN 느낌
  .build();
```

### 2) 실행: `executeLookup(...)`

- `FindAll` 기준: `Flux<ResultTuple<Left, List<Right>>>`
- `FindAll + Count`: `Mono<PageResult<ResultTuple<Left, List<Right>>>>`

```java
var left = dsl.executeEntity(User.class, MongoKey.FRONT)
  .fields(pair("status", "ACTIVE"))
  .end()
  .findAll()
  .paging(0, 20);

var right = dsl.executeEntity(Order.class, MongoKey.FRONT)
  .fields(pair("status", "DONE"))
  .end()
  .findAll();

Flux<ResultTuple<User, List<Order>>> joined =
  left.executeLookup(right, spec);

Mono<PageResult<ResultTuple<User, List<Order>>>> joinedPage =
  left.executeLookupAndCount(right, spec);
```

---

## 원자적 업데이트: `atomicUpdate()`

`atomicUpdate()`는 **Document(Update)** 방식과 **Pipeline(AggregationUpdate)** 방식을 둘 다 지원합니다.

```java
import com.mongodb.client.result.UpdateResult;

Mono<UpdateResult> updated =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("_id", userId))
     .end()
     .atomicUpdate()
       .first()    // default (단건)
       // .multi()  // 다건
       // .upsert() // upsert
       .inc("loginCount", 1)
       .set("lastLoginAt", Instant.now())
     .execute();
```

Pipeline update(aggregation update)는 `pipelineSet/pipelineInc/pipelineUnset/stage/nextStage` 후 `executeAggregation()`으로 실행합니다.

⚠️ atomicUpdate() 사용 시 Auditing(createdAt/updatedAt) 미적용 주의

Spring Data MongoDB의 Auditing(@CreatedDate, @LastModifiedDate, @CreatedBy, @LastModifiedBy 등)은 보통 엔티티를 저장(save/insert) 하는 흐름에서 엔티티 콜백/컨버전 과정을 거치며 자동 주입됩니다.
하지만 atomicUpdate()는 내부적으로 **updateFirst/updateMulti/upsert 같은 “Update 연산”**을 수행하므로, Auditing 자동 주입이 동작하지 않습니다.

@LastModifiedDate가 자동으로 갱신되지 않을 수 있음

upsert() 시 @CreatedDate가 자동으로 세팅되지 않을 수 있음

파이프라인 스테이지에서 createdAt/updatedAt을 직접 처리하세요.

```java
Instant now = Instant.now();

dsl.executeEntity(User.class, key)
   .fields(pair("_id", userId))
   .end()
   .atomicUpdate()
     .upsert()
     .set("updatedAt", now)
     // upsert에서만 createdAt 세팅이 필요하면 setOnInsert 사용(지원되는 경우)
     // .setOnInsert("createdAt", now)
   .execute();
```

---

## Bulk 작업

### 1) Bulk insert

- `saveAllBulk(Iterable/Collection/Flux)` → 내부적으로 모아서 `insertAll`

### 2) Bulk upsert

- `saveAllBulkUpsert(...)` → 엔티티의 `@Id` 또는 `id` 필드 기준으로 upsert
- `saveAllBulkUpsertByKey(entities, "k1", "k2", ...)` → 복합키 기준 upsert

---

## 히스토리 스냅샷

`createHistory(entity[, prefix])`는 엔티티를 deep-clone 후, 원본 컬렉션명 기반으로 `<base>_<prefix>` 컬렉션에 스냅샷을 저장합니다.

---

## 결과 타입

- `PageResult<T>`: `data(List<T>) + totalCount(Long)` 형태의 전통적인 페이지 결과
- `PageStream<T>`: `data(Flux<T>) + totalCount(Mono<Long>)` 형태의 reactive-friendly 래퍼
- `ResultTuple<L, R>`: lookup/group 등에서 좌/우 결과를 함께 반환하기 위한 컨테이너

---

## 팁 / 주의사항

- `FieldsPair.Condition.near/nearSphere` 사용 시 Mongo 인덱스(2d/2dsphere)와 필드 형태에 유의하세요.
- `FieldsPair.autoRangePair(field, from, to)`는 from/to 유무에 따라 between/gte/lte를 자동 선택하며, 둘 다 없으면 `null`을 반환합니다.
