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

###

# Atlas Search

## SearchBuilder overview

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
- `highlight(SearchHighlightSpec)`
- `highlight(spec -> ... )`
- `addFieldsHighlights()`
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

This DSL intentionally exposes only **single-path** `path(...)` for `AutocompleteClause`, because the current implementation treats autocomplete as a single-path operator.

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
            ArticleField.scoreWeight,
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

## Highlighting

Highlighting is modeled as a **stage-level option** on `SearchBuilder`, not as part of a specific clause.
That matches how Atlas Search defines `highlight`: it lives directly under `$search`, and you later expose the result with `$meta: "searchHighlights"`.

### Available API

- `highlight(SearchHighlightSpec)`
- `highlight(spec -> ...)`
- `addFieldsHighlights()`
- `addFieldsHighlights(String alias)`

### `SearchHighlightSpec`

`SearchHighlightSpec` currently supports:

- `builder().path(path)`
- `builder().paths(path1, path2, ...)`
- `builder().paths(Collection<K>)`
- `builder().maxCharsToExamine(int)`
- `builder().maxNumPassages(int)`

### Example

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t
         .path(ArticleField.title)
         .query("atlas search")
     )
     .highlight(h -> h
         .paths(ArticleField.title, ArticleField.summary)
         .maxCharsToExamine(200_000)
         .maxNumPassages(3)
     )
     .addFieldsHighlights()
     .addFieldsScore()
     .findAll()
     .execute();
```

### Result shape note

`addFieldsHighlights()` simply exposes Atlas Search metadata as a normal field on the output document.
If your mapped entity doesn't have a matching property, either:

- add a property to the mapped type,
- map to a projection/custom class, or
- omit `addFieldsHighlights()` and keep the entity mapping clean.

### Design note

`count().executeSearchMeta()` uses `$searchMeta` and does **not** include stage-level highlight output. Highlighting is only part of the `$search` stage path.

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

# Vector Search

## VectorSearchBuilder overview

Entry point:

```java
vectorSearch("indexName")
```

This flow is intentionally separated from both the classic Mongo flow and the Atlas Search flow because `$vectorSearch` is a distinct pipeline stage with its own constraints and options.

### Available builder methods

Core vector-search options:

- `path(K path)`
- `queryVector(VectorQueryVector)`
- `queryVector(float[] values)`
- `queryVector(double[] values)`
- `queryVector(Collection<Double> values)`
- `queryText(String queryText)`
- `limit(long limit)`
- `numCandidates(long numCandidates)`
- `exact(boolean)`
- `exact()`
- `approximate(long numCandidates)`

Filtering / shaping:

- `filterFields(...)` for **pre-filtering inside `$vectorSearch.filter`**
- `filter(block -> ...)` for nested pre-filter composition with the normal `FieldBuilder`
- `fields(...)` for **post-stage ordinary Mongo `$match`**
- `addFieldsVectorSearchScore()`
- `addFieldsVectorSearchScore(String alias)`
- `excludes(...)`

Terminal builders:

- `findAll().execute()`
- `find().execute()` / `find().executeFirst()`
- `count().execute()`
- `existsQuery().execute()`

### Important difference from `search()`

`vectorSearch()` does **not** currently expose:

- `executePage()`
- metadata count like `$searchMeta`
- sequence-token paging
- built-in sort customization

The current vector path is intentionally minimal and aligned with the actual code in `VectorSearchBuilder`.

---

## `VectorQueryVector`

`VectorQueryVector` is the DSL wrapper for the query embedding.

Available factories:

- `VectorQueryVector.ofFloatArray(float[])`
- `VectorQueryVector.ofDoubleArray(double[])`
- `VectorQueryVector.ofDoubleList(Collection<Double>)`

The public API intentionally stays independent from driver-specific vector classes.
The builder currently renders a BSON-ready `List<Double>` at stage-build time.

### Example

```java
float[] embedding = embeddingService.embed("reactive mongo dsl");

Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .addFieldsVectorSearchScore()
     .findAll()
     .execute();
```

---

## Query by text for auto-embedding indexes

When your vector index/query path is designed to accept text input, the current DSL also supports:

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryText("reactive mongo dsl")
     .limit(10)
     .approximate(200)
     .addFieldsVectorSearchScore()
     .findAll()
     .execute();
```

`queryVector(...)` and `queryText(...)` are mutually exclusive in the current implementation.

---

## ANN vs ENN

The current DSL models vector-search mode like this:

### ANN

```java
Flux<Article> ann =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .numCandidates(200)
     .findAll()
     .execute();
```

Or equivalently:

```java
.approximate(200)
```

### ENN

```java
Flux<Article> enn =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .exact()
     .findAll()
     .execute();
```

Validation rules in the current code:

- `index` is required
- `path` is required
- `limit` is required
- either `queryVector(...)` or `queryText(...)` is required
- for ANN mode, `numCandidates(...)` is required

---

## Pre-filter vs post-filter in vector search

This distinction matters just as much as in Atlas Search.

### Pre-filter: `$vectorSearch.filter`

Use `filterFields(...)` or `filter(...)` when you want the condition rendered into the vector-search stage itself.

```java
Flux<Article> filtered =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .filterFields(
         pair("status", "PUBLISHED"),
         pair("deleted", false)
     )
     .findAll()
     .execute();
```

Nested pre-filter composition is also supported:

```java
Flux<Article> filtered =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .filter(f -> f
         .and(x -> x.fields(
             pair("status", "PUBLISHED"),
             pair("deleted", false)
         ))
         .or(x -> x.fields(
             pair("category", "JAVA"),
             pair("category", "MONGODB")
         ))
     )
     .findAll()
     .execute();
```

### Post-filter: ordinary aggregation `$match`

Use `fields(...)` on `VectorSearchBuilder` when you intentionally want a normal Mongo `$match` **after** `$vectorSearch`.

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .fields(
         pair("visible", true)
     )
     .findAll()
     .execute();
```

---

## Vector score metadata

Expose the vector-search similarity score with:

- `addFieldsVectorSearchScore()`
- `addFieldsVectorSearchScore(String alias)`

### Example

```java
Flux<Article> results =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .addFieldsVectorSearchScore("score")
     .findAll()
     .execute();
```

As with `addFieldsHighlights()`, make sure your mapped result type can actually accept the additional field if you expect it to be populated after mapping.

---

## Vector count semantics

Vector count is intentionally narrower than Atlas Search metadata count.

```java
Mono<Long> count =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(10)
     .approximate(200)
     .count()
     .execute();
```

This counts the documents returned by the **current pipeline output** after `$vectorSearch`, so it is effectively bounded by your configured `limit` and any post-stage `fields(...)` filtering.

It is **not** a corpus-wide metadata count.

---

## Vector exists terminal

```java
Mono<Boolean> exists =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .vectorSearch("articles_vector_index")
     .path(ArticleField.embedding)
     .queryVector(embedding)
     .limit(1)
     .exact()
     .existsQuery()
     .execute();
```

---

## Notes and limitations

### Atlas Search

- Atlas Search requires a matching Search index for the fields you query.
- `$search` and `$searchMeta` must be the first stage in their pipelines.
- `$search` cannot appear inside `$facet`.
- `AutocompleteClause` intentionally exposes only a single path.
- `TextClause` currently rejects `fuzzy + synonyms` together.
- `count().execute()` and `count().executeSearchMeta()` serve different purposes; choose carefully.
- If you need deterministic ordering, do not rely on score alone.
- Highlighting is stage-level and only participates in the `$search` path, not the `$searchMeta` count path.

### Vector Search

- `vectorSearch("indexName")` currently requires an explicit index name.
- `$vectorSearch` must be the first stage of the pipeline.
- `$vectorSearch` can't be used inside `$facet` or inside a `$lookup` sub-pipeline.
- ANN mode requires `numCandidates(...)`.
- `count()` only counts the limited pipeline output; there is no metadata count terminal today.
- There is no built-in page token, `executePage()`, or sort DSL for vector search in the current implementation.
- For advanced analyzed text filtering together with vectors, this DSL currently keeps that concern separate rather than trying to blend it into a single stage.

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
- use `vectorSearch(...)` for MongoDB Vector Search querying
- use `vectorSearch().filter(...)` / `filterFields(...)` for stage-level vector pre-filters
- use `vectorSearch().fields(...)` only when you intentionally want a **post-vector-search** ordinary `$match`
