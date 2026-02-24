# Reactive Mongo DSL (reactive-mongo-dsl)

A fluent DSL built on top of Spring Data **ReactiveMongoTemplate**, designed to make **dynamic criteria / aggregation / $lookup joins / atomic updates / bulk operations** easier to compose in reactive pipelines.

---

## Core ideas

### 1) Template/transaction routing via `MongoTemplateResolver<K>`

`ReactiveMongoDsl<K>` delegates template and transaction resolution to `MongoTemplateResolver<K>`, so you can keep a single DSL while targeting multiple templates (multi DB / multi cluster / multi tenant).

### 2) Typical flow: `execute* → fields(...) → end() → find/findAll/count/delete/exists/atomicUpdate`

A common usage pattern:

1. Choose execution context by `executeEntity(...)`, `executeRepository(...)`, or `executeCustomClass(...)`
2. Build filters with `fields(...)`
3. Switch to query builders with `end()`
4. Pick an operation (`find()`, `findAll()`, `count()`, `delete()`, `exists()`, `atomicUpdate()`) and call `execute*()`

### 3) Conditions are expressed with `FieldsPair` (+ `Condition`)

Use `FieldsPair.pair(field, value[, Condition])` and the DSL will convert them into Spring `Criteria` internally.

---

## Requirements

- Java 17+
- Spring Data MongoDB Reactive / Project Reactor

---

## Quick start

### 1) Implement `MongoTemplateResolver`

```java
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;

public enum MongoKey { FRONT, BACK }

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

## Basic queries

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
     .execute();      // 0..1

Mono<User> first =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("status", "ACTIVE"))
     .end()
     .find()
     .executeFirst(); // first of many
```

### 2) Grouping AND/OR/NOT

`FieldBuilder` supports nested blocks: `and / or / not / notAny / notAll`.

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

Flux<User> users =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields() // root AND
       .and(f -> f.fields(
           pair("status", List.of("ACTIVE_1","ACTIVE_2"), in),
           pair("age", 20, gte)
       ))
       .or(f -> f.fields(
           pair("name", "kim", like),
           pair("name", "lee", like)
       ))
       .notAny(f -> f.fields( // good for NOT(OR(...))
           pair("banned", true)
       ))
     .end()
     .findAll()
     .execute();
```

---

## Paging + total count (Aggregation)

When paging is configured, `executeAggregation()` builds a `$facet(data, totalCount)` pipeline and returns `PageResult<E>`.

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

## $lookup joins

### 1) Build join conditions with `LookupSpec`

- Simple mode with `localField/foreignField`
- Or advanced mode using `let + pipeline + $expr` via `bindConditionFields`, `bindConditionConst`, `bindConditionBetween`, `bindConditionLike`, `bindConditionExists`, etc.
- Optional `unwind(preserveNullAndEmptyArrays)` to control “single row” / left outer join semantics
- `outerStage(s)` lets you append post-lookup stages

```java
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

LookupSpec spec = LookupSpec.builder()
  .as("orders")
  .bindConditionFields("_id", eq, "userId")     // left._id == right.userId
  .bindConditionConst("DONE", eq, "status")    // right.status == "DONE"
  .limit(10)
  // .unwind(true) // preserveNullAndEmptyArrays=true -> left outer join-ish
  .build();
```

### 2) Execute: `executeLookup(...)`

- From `FindAll`: `Flux<ResultTuple<Left, List<Right>>>`
- With paging + count: `Mono<PageResult<ResultTuple<Left, List<Right>>>>`

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

## Atomic updates: `atomicUpdate()`

`atomicUpdate()` supports both classic **document updates** (`Update`) and **pipeline updates** (`AggregationUpdate`).

```java
import com.mongodb.client.result.UpdateResult;

Mono<UpdateResult> updated =
  dsl.executeEntity(User.class, MongoKey.FRONT)
     .fields(pair("_id", userId))
     .end()
     .atomicUpdate()
       .first()    // default (single)
       // .multi()  // multi update
       // .upsert() // upsert
       .inc("loginCount", 1)
       .set("lastLoginAt", Instant.now())
     .execute();
```

For pipeline updates, use `pipelineSet/pipelineInc/pipelineUnset/stage/nextStage` and call `executeAggregation()`.

⚠️ (EN) Auditing (createdAt/updatedAt) is NOT applied with atomicUpdate()

Spring Data MongoDB auditing (@CreatedDate, @LastModifiedDate, @CreatedBy, @LastModifiedBy, etc.) is typically populated during entity save/insert flows where entity callbacks/conversion are invoked.
However, atomicUpdate() performs MongoDB update operations (e.g., updateFirst/updateMulti/upsert) and therefore auditing annotations are not automatically populated/updated.

@LastModifiedDate may not be updated automatically

On upsert(), @CreatedDate may not be set automatically

Recommended approach

Set auditing fields explicitly:

updatedAt → .set("updatedAt", now) on every update

You can handle createdAt/updatedAt directly in your pipeline stages.

```java
Instant now = Instant.now();

dsl.executeEntity(User.class, key)
   .fields(pair("_id", userId))
   .end()
   .atomicUpdate()
     .upsert()
     .set("updatedAt", now)
     // For upsert-only createdAt, use setOnInsert when available
     // .setOnInsert("createdAt", now)
   .execute();
```

---

## Bulk operations

### 1) Bulk insert

- `saveAllBulk(Iterable/Collection/Flux)` → collects and performs `insertAll`

### 2) Bulk upsert

- `saveAllBulkUpsert(...)` → upsert by `@Id` or `id` field
- `saveAllBulkUpsertByKey(entities, "k1", "k2", ...)` → upsert by composite key fields

---

## History snapshots

`createHistory(entity[, prefix])` deep-clones an entity and inserts it into `<base>_<prefix>` collection.

---

## Result types

- `PageResult<T>`: classic page object (`List<T> data`, `Long totalCount`)
- `PageStream<T>`: reactive-friendly wrapper (`Flux<T> data`, `Mono<Long> totalCount`)
- `ResultTuple<L, R>`: container for join/group results

---

## Notes

- When using `FieldsPair.Condition.near/nearSphere`, make sure your schema/indexes match (2d/2dsphere, Geo types).
- `FieldsPair.autoRangePair(field, from, to)` chooses between `between/gte/lte` automatically and returns `null` if both bounds are missing.
