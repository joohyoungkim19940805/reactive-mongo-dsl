# Reactive Mongo DSL (reactive-mongo-dsl)

A fluent DSL built on top of Spring Data **ReactiveMongoTemplate**, designed to make **dynamic criteria / aggregation / `$lookup` joins / atomic updates / bulk operations / Atlas Search** easier to compose in reactive pipelines.

---

## Core ideas

### 1) Template / transaction routing via `MongoTemplateResolver<K>`

`ReactiveMongoDsl<K>` delegates template and transaction resolution to `MongoTemplateResolver<K>`, so you can keep a single DSL while targeting multiple templates (multi DB / multi cluster / multi tenant).

### 2) Query flow: classic query vs Atlas Search query

There are now **two distinct entry flows**:

#### Classic Mongo query flow

`execute* -> fields(...) -> end() -> find/findAll/count/delete/exists/atomicUpdate`

1. Choose execution context with `executeEntity(...)`, `executeRepository(...)`, or `executeCustomClass(...)`
2. Build ordinary Mongo criteria with `fields(...)`
3. Switch to terminal query builders with `end()`
4. Execute with `find()`, `findAll()`, `count()`, `delete()`, `exists()`, `atomicUpdate()`

#### Atlas Search flow

`execute* -> search([index]) -> <search clauses + post-filter> -> find/findAll/count/existsQuery`

Atlas Search is intentionally separated because `$search` / `$searchMeta` must be the **first stage** in the pipeline, and `$search` cannot be placed inside `$facet`. That makes Atlas Search a different execution path from ordinary `fields(...).end()` style queries.

### 3) Conditions and clause naming

- Ordinary Mongo filtering is expressed with `FieldsPair` (+ `Condition`)
- Atlas Search filtering is expressed with **strongly typed search clauses**

The Atlas Search types use the following names:

- `TextClause`
- `PhraseClause`
- `AutocompleteClause`
- `EqualsClause`
- `ExistsClause`
- `InClause`
- `RangeClause`
- `SearchScoreSpec`

The term **Clause** is used intentionally: these types represent **search-query clauses** that become part of the final `$search` body. They are not generic config bags, and they intentionally avoid a raw `Object`-centric public API.

---

## Requirements

- Java 17+
- Spring Data MongoDB Reactive / Project Reactor
- MongoDB Atlas Search index (or MongoDB Search-compatible deployment for supported environments)

---

## Quick start

### 1) Implement `MongoTemplateResolver`

```java
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;

public enum MongoTemplateName { FRONT, BACK }

public class MyMongoTemplateResolver implements MongoTemplateResolver<MongoTemplateName> {
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
  public ReactiveMongoTemplate getTemplate(MongoTemplateName key) {
    return (key == MongoTemplateName.BACK) ? back : front;
  }

  @Override
  public TransactionalOperator getTxOperator(MongoTemplateName key) {
    return (key == MongoTemplateName.BACK) ? backTx : frontTx;
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
    return new ReactiveMongoDsl<>(resolver);
  }
}
```

---

## Basic Mongo queries

### findAll / find

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

Flux<User> users =
  dsl.executeEntity(User.class, MongoTemplateName.FRONT)
     .fields(
        pair("status", "ACTIVE"),
        pair("age", 20, gte),
        pair("name", "kim", like)
     )
     .end()
     .findAll()
     .execute();

Mono<User> one =
  dsl.executeEntity(User.class, MongoTemplateName.FRONT)
     .fields(pair("_id", userId))
     .end()
     .find()
     .execute();
```

### Grouping AND / OR / NOT

```java
Flux<User> users =
  dsl.executeEntity(User.class, MongoTemplateName.FRONT)
     .fields()
       .and(f -> f.fields(
           pair("status", List.of("ACTIVE_1", "ACTIVE_2"), in),
           pair("age", 20, gte)
       ))
       .or(f -> f.fields(
           pair("name", "kim", like),
           pair("name", "lee", like)
       ))
       .notAny(f -> f.fields(
           pair("banned", true)
       ))
     .end()
     .findAll()
     .execute();
```

### Paging + total count via aggregation

```java
import org.springframework.data.domain.Sort.Order;

Mono<PageResult<User>> page =
  dsl.executeEntity(User.class, MongoTemplateName.FRONT)
     .fields(pair("status", "ACTIVE"))
     .end()
     .findAll()
       .paging(0, 20)
       .sorts(Order.desc("_id"))
     .executeAggregation();
```

---

## `$lookup` joins

### Build join conditions with `LookupSpec`

```java
import com.byeolnaerim.mongodsl.lookup.LookupSpec;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

LookupSpec spec = LookupSpec.builder()
  .as("orders")
  .bindConditionFields("_id", eq, "userId")
  .bindConditionConst("DONE", eq, "status")
  .limit(10)
  .build();
```

### Execute lookup

```java
var left = dsl.executeEntity(User.class, MongoTemplateName.FRONT)
  .fields(pair("status", "ACTIVE"))
  .end()
  .findAll()
  .paging(0, 20);

var right = dsl.executeEntity(Order.class, MongoTemplateName.FRONT)
  .fields(pair("status", "DONE"))
  .end()
  .findAll();

Flux<ResultTuple<User, List<Order>>> joined =
  left.executeLookup(right, spec);
```

---

## Atomic updates

`atomicUpdate()` supports both classic **document updates** (`Update`) and **pipeline updates** (`AggregationUpdate`).

```java
Mono<UpdateResult> updated =
  dsl.executeEntity(User.class, MongoTemplateName.FRONT)
     .fields(pair("_id", userId))
     .end()
     .atomicUpdate()
       .first()
       .inc("loginCount", 1)
       .set("lastLoginAt", Instant.now())
     .execute();
```

### Auditing note

Spring Data auditing annotations such as `@CreatedDate` / `@LastModifiedDate` are not automatically applied when you use `atomicUpdate()` because this path performs update operations directly instead of save/insert entity flows.

Set audit fields explicitly when needed.

---

# Atlas Search

## Search builder overview

Entry points:

```java
search()
search("indexName")
```

Root search styles:

- `text(...)`
- `phrase(...)`
- `autocomplete(...)`
- `equals(...)`
- `exists(...)`
- `in(...)`
- `range(...)`
- `compound(...)`
- `operator(...)` for directly passing a ready-made `AtlasSearchOperator`

Post-search features:

- `fields(...)` for **post-search ordinary Mongo `$match`**
- `addFieldsScore()`
- `addFieldsScoreDetails()`
- `addFieldsSequenceToken()`
- `sorts(SearchSortSpec...)`
- `firstSortScore()`
- `paging(pageNumber, pageSize)`
- `searchAfter(token)` / `searchBefore(token)`
- `countType(SearchCountType)`
- `scoreDetails(true)`
- `excludes(...)`

Terminal builders:

- `findAll().execute()`
- `findAll().executePage()`
- `find().execute()` / `find().executeFirst()`
- `count().execute()`
- `count().executeSearchMeta()`
- `existsQuery().execute()`

---

## Search semantics: clause vs post-filter

This distinction is important.

### Search clauses

Search clauses become part of the `$search` body and therefore participate in Atlas Search scoring / indexing behavior.

Examples:

- `TextClause`
- `PhraseClause`
- `AutocompleteClause`
- `RangeClause`
- clauses inside `compound.must / should / filter / mustNot`

### Post-search `fields(...)`

`search().fields(...)` does **not** become part of `$search`.
It is converted into an ordinary Mongo `$match` stage **after** `$search`.

Use this when you want to:

- reuse the existing `FieldsPair` DSL
- apply a regular Mongo match after Atlas Search has already selected candidates
- keep non-search conditions out of the search clause layer

Example:

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;

Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t
         .path(ArticleField.title)
         .query("spring data mongo")
     )
     .fields(
         pair("deleted", false),
         pair("status", "PUBLISHED")
     )
     .findAll()
     .execute();
```

---

## Search path philosophy

Atlas Search paths use the same philosophy as the rest of the DSL: callers can provide **enum paths or string paths**, and the DSL resolves them internally.

That is what `SearchPathResolver` does.

```java
public enum ArticleField {
  title,
  titleAutocomplete,
  status,
  publishedAt,
  scoreWeight
}
```

```java
TextClause<ArticleField> clause = SearchOperators.<ArticleField>text()
    .path(ArticleField.title)
    .query("mongodb");
```

This mirrors the same idea already used by `FieldsPair`: keep the public API strongly typed and expressive, and only convert to `Document` at render time.

---

## Clause factories

Use `SearchOperators` as the static entry point for building reusable clauses.

```java
import com.byeolnaerim.mongodsl.search.SearchOperators;

TextClause<ArticleField> titleClause = SearchOperators.<ArticleField>text()
    .path(ArticleField.title)
    .query("atlas search");
```

Available factories:

- `SearchOperators.text()`
- `SearchOperators.phrase()`
- `SearchOperators.autocomplete()`
- `SearchOperators.equals()`
- `SearchOperators.exists()`
- `SearchOperators.in()`
- `SearchOperators.range()`

These return **mutable fluent builders** that render themselves to `Document` only at execution time.

---

## `TextClause`

`TextClause` models the Atlas Search `text` operator.

Supported fluent options:

- `path(K path)`
- `paths(Collection<K> paths)`
- `query(String query)`
- `queries(Collection<String> queries)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `matchCriteria(SearchMatchCriteria)`
- `synonyms(String synonyms)`
- `score(SearchScoreSpec)`

### Example

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t
         .paths(List.of(ArticleField.title, ArticleField.summary))
         .query("reactive mongo dsl")
         .matchCriteria(SearchMatchCriteria.ALL)
         .fuzzy(1, 1, 50)
         .score(SearchScoreSpec.boost(3.0))
     )
     .findAll()
     .execute();
```

### Validation note

`TextClause` intentionally rejects using **both** `fuzzy(...)` and `synonyms(...)` together, because the implementation treats them as mutually exclusive.

---

## `PhraseClause`

`PhraseClause` models the Atlas Search `phrase` operator.

Supported fluent options:

- `path(K path)` / `paths(Collection<K> paths)`
- `query(String query)` / `queries(Collection<String> queries)`
- `slop(int slop)`
- `synonyms(String synonyms)`
- `score(SearchScoreSpec)`

### Example

```java
Flux<Article> exactish =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .phrase(p -> p
         .path(ArticleField.title)
         .query("reactive mongo dsl")
         .slop(2)
         .score(SearchScoreSpec.boost(2.0))
     )
     .findAll()
     .execute();
```

Use `phrase` when term order matters more than with ordinary `text` queries.

---

## `AutocompleteClause`

`AutocompleteClause` models the Atlas Search `autocomplete` operator.

Supported fluent options:

- `path(K path)`
- `query(String query)` / `queries(Collection<String> queries)`
- `tokenOrder(SearchTokenOrder)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `score(SearchScoreSpec)`

### Example

```java
Flux<Article> suggest =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_autocomplete")
     .autocomplete(a -> a
         .path(ArticleField.titleAutocomplete)
         .query("rea mon")
         .tokenOrder(SearchTokenOrder.SEQUENTIAL)
         .fuzzy(1, 1, 20)
     )
     .firstSortScore()
     .findAll()
     .execute();
```

### Important note

This DSL intentionally exposes only **single-path** `path(...)` for `AutocompleteClause`, because the implementation treats autocomplete as a single-path operator.

---

## `EqualsClause`

`EqualsClause` is intended for exact equality matching inside Atlas Search.

Supported value overloads include:

- `String`
- `Boolean`
- `Integer`
- `Long`
- `Double`
- `Float`
- `Instant`
- `ObjectId`
- `UUID`
- `valueNull()`

### Example

```java
Flux<Article> exactStatus =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .equals(e -> e
         .path(ArticleField.status)
         .value("PUBLISHED")
     )
     .findAll()
     .execute();
```

---

## `ExistsClause`

`ExistsClause` checks whether an indexed field path exists.

```java
Flux<Article> withImage =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .exists(e -> e
         .path(ArticleField.thumbnailUrl)
     )
     .findAll()
     .execute();
```

This is especially useful inside `compound.filter(...)` or `compound.mustNot(...)`.

---

## `InClause`

`InClause` provides a strongly typed wrapper around Atlas Search `in`.

Supported value methods include:

- `valuesStrings(...)`
- `valuesBooleans(...)`
- `valuesIntegers(...)`
- `valuesLongs(...)`
- `valuesDoubles(...)`
- `valuesInstants(...)`
- `valuesObjectIds(...)`
- `valuesUuids(...)`
- `valuesRaw(...)`

### Example

```java
Flux<Article> visibleStatuses =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .in(i -> i
         .path(ArticleField.status)
         .valuesStrings(List.of("PUBLISHED", "SCHEDULED"))
     )
     .findAll()
     .execute();
```

`valuesRaw(...)` exists as an escape hatch, but prefer the typed methods in ordinary application code.

---

## `RangeClause`

`RangeClause` supports numeric, date, tokenized string, and objectId range queries.

Supported fluent methods:

- `gt(...)`
- `gte(...)`
- `lt(...)`
- `lte(...)`
- `score(SearchScoreSpec)`

### Example: date range

```java
Flux<Article> recent =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .range(r -> r
         .path(ArticleField.publishedAt)
         .gte(Instant.parse("2025-01-01T00:00:00Z"))
         .lt(Instant.parse("2026-01-01T00:00:00Z"))
     )
     .findAll()
     .execute();
```

### Example: numeric range

```java
Flux<Article> weighted =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .range(r -> r
         .path(ArticleField.scoreWeight)
         .gte(10)
         .lt(100)
     )
     .findAll()
     .execute();
```

---

## `SearchScoreSpec`

`SearchScoreSpec` exists to keep score customization strongly typed and DSL-friendly.

Supported entry points:

- `SearchScoreSpec.boost(double value)`
- `SearchScoreSpec.boostByPath(path)`
- `SearchScoreSpec.boostByPath(path, undefined)`
- `SearchScoreSpec.constant(double value)`
- `SearchScoreSpec.function(spec -> ...)`

### Basic examples

```java
SearchScoreSpec boosted = SearchScoreSpec.boost(2.5);
SearchScoreSpec constant = SearchScoreSpec.constant(100);
SearchScoreSpec pathBoost = SearchScoreSpec.boostByPath(ArticleField.scoreWeight, 1.0);
```

### Function example

```java
SearchScoreSpec score = SearchScoreSpec.function(fn -> fn
    .multiply(expr -> expr
        .scoreRelevance()
        .path(ArticleField.scoreWeight, 1.0)
    )
);
```

### More advanced function example

```java
SearchScoreSpec score = SearchScoreSpec.function(fn -> fn
    .add(expr -> expr
        .scoreRelevance()
        .expression(nested -> nested.gauss(
            ArticleField.recencyWeight,
            0.0,
            7.0,
            0.0,
            0.5
        ))
    )
);
```

The builder currently supports:

- `constant(...)`
- `scoreRelevance()`
- `path(...)`
- `add(...)`
- `multiply(...)`
- `gauss(...)`
- `log(...)`
- `log1p(...)`

Use `SearchScoreSpec` on any clause that supports `.score(...)`.

---

## `compound(...)`

`compound(...)` is the main way to combine multiple Atlas Search clauses.

Supported clause groups:

- `must(...)`
- `mustNot(...)`
- `should(...)`
- `filter(...)`
- `minimumShouldMatch(int)`
- `score(SearchScoreSpec)`

Convenience helpers currently include:

- `mustText(...)`
- `shouldText(...)`
- `filterText(...)`
- `mustPhrase(...)`
- `shouldAutocomplete(...)`
- `filterEquals(...)`
- `filterIn(...)`
- `filterRange(...)`
- `mustNotExists(...)`

### Example

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .compound(c -> c
         .mustText(ArticleField.title, t -> t
             .query("reactive mongo")
             .matchCriteria(SearchMatchCriteria.ALL)
             .score(SearchScoreSpec.boost(5.0))
         )
         .shouldAutocomplete(ArticleField.titleAutocomplete, a -> a
             .query("reactive mon")
             .tokenOrder(SearchTokenOrder.SEQUENTIAL)
             .score(SearchScoreSpec.boost(2.0))
         )
         .filterEquals(ArticleField.status, e -> e
             .value("PUBLISHED")
         )
         .filterRange(ArticleField.publishedAt, r -> r
             .gte(Instant.parse("2025-01-01T00:00:00Z"))
         )
         .mustNotExists(ArticleField.deletedAt)
         .minimumShouldMatch(1)
     )
     .addFieldsScore()
     .firstSortScore()
     .findAll()
     .execute();
```

### When to use `filter` vs post-search `fields(...)`

Use `compound.filter(...)` when the condition should stay **inside the Atlas Search clause model**.
Use `search().fields(...)` when you intentionally want a **plain Mongo `$match` after `$search`**.

---

## Sort and score output

### Add score to the result document

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .addFieldsScore()                // alias: score
     .addFieldsScoreDetails()         // alias: scoreDetails
     .findAll()
     .execute();
```

### Sort by score first

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .firstSortScore()
     .sorts(SearchSortSpec.desc(ArticleField.publishedAt))
     .findAll()
     .execute();
```

`SearchSortSpec` supports:

- `SearchSortSpec.scoreDesc()`
- `SearchSortSpec.scoreAsc()`
- `SearchSortSpec.asc(path)`
- `SearchSortSpec.desc(path)`

### Stable ordering recommendation

If multiple results have the same score, Atlas Search result order may be non-deterministic. In real applications, consider appending a unique field sort after score sort.

---

## Count behavior

There are **two different count concerns** in Atlas Search.

### 1) Count final pipeline results

```java
Mono<Long> count =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .fields(pair("deleted", false))
     .count()
     .execute();
```

This counts the result **after** `$search` and after post-search `$match` / other pipeline logic.

### 2) Count Atlas Search metadata

```java
Mono<Long> total =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .countType(SearchCountType.TOTAL)
     .count()
     .executeSearchMeta();
```

This uses `$searchMeta` and returns the **Atlas Search metadata count**.

Supported count modes:

- `SearchCountType.TOTAL`
- `SearchCountType.LOWER_BOUND`

Use metadata count when you need search-engine result counts specifically. Use pipeline count when you need the final application-visible count.

---

## Pagination with sequence token

Atlas Search supports cursor-like pagination using a sequence token.

### Emit the token

```java
Flux<Article> page1 =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .firstSortScore()
     .addFieldsSequenceToken("nextToken")
     .findAll()
     .execute();
```

### Read after a token

```java
Flux<Article> page2 =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .firstSortScore()
     .searchAfter(previousToken)
     .findAll()
     .execute();
```

Or go backward with `searchBefore(token)`.

---

## Exists query terminal for search

For Atlas Search flows, use:

```java
Mono<Boolean> exists =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .existsQuery()
     .execute();
```

This is intentionally named `existsQuery()` to distinguish it from the ordinary query-builder `exists()` path.

---

## Notes and limitations

- Atlas Search requires a matching Search index for the fields you query.
- `$search` and `$searchMeta` must be the first stage in their pipelines.
- `$search` cannot appear inside `$facet`.
- `AutocompleteClause` intentionally exposes only a single path.
- `TextClause` currently rejects `fuzzy + synonyms` together.
- `count().execute()` and `count().executeSearchMeta()` serve different purposes; choose carefully.
- If you need deterministic ordering, do not rely on score alone.

---

## Result wrappers

- `PageResult<T>`: classic page object (`List<T> data`, `Long totalCount`)
- `PageStream<T>`: reactive-friendly wrapper (`Flux<T> data`, `Mono<Long> totalCount`)
- `ResultTuple<L, R>`: container for lookup/grouped results

---

## Tip

When in doubt, keep this rule in mind:

- use `FieldsPair` / `fields(...).end()` for ordinary Mongo querying
- use `search(...)` for Atlas Search querying
- use `search().fields(...)` only when you intentionally want a **post-search** ordinary `$match`
