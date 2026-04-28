# Reactive Mongo DSL (reactive-mongo-dsl)

Spring Data **ReactiveMongoTemplate** 기반으로, **동적 Criteria / Aggregation / `$lookup` 조인 / 원자적 업데이트 / Bulk 작업 / Atlas Search**를 반응형 파이프라인에서 자연스럽게 조합할 수 있도록 만든 체이닝 DSL입니다.

---

## 주요 컨셉

### 1) `MongoTemplateResolver<K>` 기반 템플릿 / 트랜잭션 라우팅

`ReactiveMongoDsl<K>`는 내부적으로 `MongoTemplateResolver<K>`를 통해 key 별 `ReactiveMongoTemplate` / 트랜잭션 리소스를 가져옵니다.
즉, 멀티 DB / 멀티 클러스터 / 멀티 테넌트 환경에서도 동일한 DSL 스타일을 유지할 수 있습니다.

### 2) 일반 쿼리 흐름과 Atlas Search 흐름은 분리되어 있습니다

#### 일반 Mongo 쿼리 흐름

`execute* -> fields(...) -> end() -> find/findAll/count/delete/exists/atomicUpdate`

1. `executeEntity(...)`, `executeRepository(...)`, `executeCustomClass(...)`로 실행 컨텍스트를 선택
2. `fields(...)`로 일반 Mongo where 조건 구성
3. `end()`로 terminal query builder 전환
4. `find()`, `findAll()`, `count()`, `delete()`, `exists()`, `atomicUpdate()` 실행

#### Atlas Search 흐름

`execute* -> search([index]) -> <search clause + post-filter> -> find/findAll/count/existsQuery`

Atlas Search는 일반 `fields(...).end()` 흐름과 분리되어 있습니다.
그 이유는 `$search` / `$searchMeta`가 **반드시 파이프라인 첫 stage**여야 하고, `$search`는 `$facet` 내부에 들어갈 수 없기 때문입니다.

### 3) 조건 표현 방식

- 일반 Mongo 조건은 `FieldsPair` (+ `Condition`) 로 표현합니다.
- Atlas Search 조건은 **강타입 search clause**로 표현합니다.

Atlas Search 관련 타입 이름은 다음과 같습니다.

- `TextClause`
- `PhraseClause`
- `AutocompleteClause`
- `EqualsClause`
- `ExistsClause`
- `InClause`
- `RangeClause`
- `SearchScoreSpec`

여기서 **Clause** 라는 이름을 쓴 이유는, 이 타입들이 단순 설정 객체가 아니라 **최종 `$search` 본문을 구성하는 검색 절**이기 때문입니다.

---

## 요구사항

- Java 17+
- Spring Data MongoDB Reactive / Project Reactor
- Atlas Search 인덱스(또는 MongoDB Search 호환 환경)

---

## 빠른 시작

### 1) `MongoTemplateResolver` 구현 예시

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

## 기본 Mongo 조회

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

### AND / OR / NOT 그룹핑

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

### 페이징 + total count

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

## `$lookup` 조인

### `LookupSpec` 구성

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

### 실행

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

## 원자적 업데이트

`atomicUpdate()`는 일반 **document update** (`Update`) 와 **pipeline update** (`AggregationUpdate`) 를 둘 다 지원합니다.

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

### Auditing 주의사항

`atomicUpdate()`는 엔티티 save/insert 흐름이 아니라 Mongo update 연산을 직접 사용하므로,
`@CreatedDate`, `@LastModifiedDate` 같은 Spring Data Auditing 이 자동 반영되지 않을 수 있습니다.

필요하면 `updatedAt`, `createdAt` 등을 직접 세팅하세요.

---

# Atlas Search

## SearchBuilder 개요

진입점:

```java
search()
search("indexName")
```

루트 검색 스타일:

- `text(...)`
- `phrase(...)`
- `autocomplete(...)`
- `equals(...)`
- `exists(...)`
- `in(...)`
- `range(...)`
- `compound(...)`
- `operator(...)`

검색 후 기능:

- `fields(...)` : **post-search 일반 Mongo `$match`**
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

터미널 빌더:

- `findAll().execute()`
- `findAll().executePage()`
- `find().execute()` / `find().executeFirst()`
- `count().execute()`
- `count().executeSearchMeta()`
- `existsQuery().execute()`

---

## Search clause 와 post-filter 의 차이

이 구분은 중요합니다.

### Search clause

Search clause 는 `$search` 본문 안에 들어가며, Atlas Search 인덱스 / 스코어링 흐름에 직접 참여합니다.

예:

- `TextClause`
- `PhraseClause`
- `AutocompleteClause`
- `RangeClause`
- `compound.must / should / filter / mustNot` 안에 들어가는 clause

### post-search `fields(...)`

`search().fields(...)` 는 `$search` 안으로 들어가지 않습니다.
대신 `$search` **뒤에 붙는 일반 Mongo `$match`** 로 변환됩니다.

즉, 다음 용도에 적합합니다.

- 기존 `FieldsPair` DSL 재사용
- Atlas Search 로 후보를 먼저 추린 뒤, 일반 Mongo 조건을 추가 적용
- 검색 엔진 clause 와 일반 where 조건을 명확히 분리

예시:

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

## Search path 철학

Atlas Search path 도 이 라이브러리의 기존 철학을 그대로 따릅니다.
즉, **enum path / string path** 둘 다 받을 수 있고, 내부에서 문자열 path 로 해석합니다.

이 역할을 하는 것이 `SearchPathResolver` 입니다.

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

즉 public API 는 강타입으로 유지하고, 실제 `Document` 렌더링은 마지막 경계에서만 수행합니다.

---

## Clause 팩토리

재사용 가능한 clause 는 `SearchOperators` 를 정적 진입점으로 사용하는 것이 좋습니다.

```java
import com.byeolnaerim.mongodsl.search.SearchOperators;

TextClause<ArticleField> titleClause = SearchOperators.<ArticleField>text()
    .path(ArticleField.title)
    .query("atlas search");
```

제공되는 팩토리:

- `SearchOperators.text()`
- `SearchOperators.phrase()`
- `SearchOperators.autocomplete()`
- `SearchOperators.equals()`
- `SearchOperators.exists()`
- `SearchOperators.in()`
- `SearchOperators.range()`

이 팩토리들은 실행 시점까지 `Document` 로 렌더링되지 않는 **mutable fluent builder** 입니다.

---

## `TextClause`

`TextClause` 는 Atlas Search 의 `text` 연산자를 모델링합니다.

지원 옵션:

- `path(K path)`
- `paths(Collection<K> paths)`
- `query(String query)`
- `queries(Collection<String> queries)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `matchCriteria(SearchMatchCriteria)`
- `synonyms(String synonyms)`
- `score(SearchScoreSpec)`

### 예시

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

### 검증 규칙

`TextClause` 는 `fuzzy(...)` 와 `synonyms(...)` 를 동시에 사용하는 것을 막습니다.
현재 구현에서 두 옵션은 상호배타적으로 취급됩니다.

---

## `PhraseClause`

`PhraseClause` 는 Atlas Search 의 `phrase` 연산자를 모델링합니다.

지원 옵션:

- `path(K path)` / `paths(Collection<K> paths)`
- `query(String query)` / `queries(Collection<String> queries)`
- `slop(int slop)`
- `synonyms(String synonyms)`
- `score(SearchScoreSpec)`

### 예시

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

단어 순서가 중요한 검색이라면 일반 `text` 보다 `phrase` 가 더 적합합니다.

---

## `AutocompleteClause`

`AutocompleteClause` 는 Atlas Search 의 `autocomplete` 연산자를 모델링합니다.

지원 옵션:

- `path(K path)`
- `query(String query)` / `queries(Collection<String> queries)`
- `tokenOrder(SearchTokenOrder)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `score(SearchScoreSpec)`

### 예시

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

### 중요한 점

현재 DSL 구현은 `AutocompleteClause` 에 대해 **단일 path** 만 노출합니다.
즉 `path(...)` 만 있고 `paths(...)` 는 제공하지 않습니다.

---

## `EqualsClause`

`EqualsClause` 는 Atlas Search 내부 exact equality 용입니다.

지원 value 오버로드:

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

### 예시

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

`ExistsClause` 는 인덱싱된 필드 path 가 존재하는지를 검사합니다.

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

특히 `compound.filter(...)`, `compound.mustNot(...)` 안에서 많이 유용합니다.

---

## `InClause`

`InClause` 는 Atlas Search `in` 을 강타입으로 감싼 wrapper 입니다.

지원 value 메서드:

- `valuesStrings(...)`
- `valuesBooleans(...)`
- `valuesIntegers(...)`
- `valuesLongs(...)`
- `valuesDoubles(...)`
- `valuesInstants(...)`
- `valuesObjectIds(...)`
- `valuesUuids(...)`
- `valuesRaw(...)`

### 예시

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

`valuesRaw(...)` 는 escape hatch 용이고, 일반 애플리케이션 코드에서는 가능한 typed method 를 우선 사용하는 것을 권장합니다.

---

## `RangeClause`

`RangeClause` 는 숫자 / 날짜 / tokenized string / objectId 범위 질의를 지원합니다.

지원 메서드:

- `gt(...)`
- `gte(...)`
- `lt(...)`
- `lte(...)`
- `score(SearchScoreSpec)`

### 날짜 범위 예시

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

### 숫자 범위 예시

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

`SearchScoreSpec` 는 score 커스터마이징을 강타입으로 표현하기 위한 타입입니다.

지원 entry point:

- `SearchScoreSpec.boost(double value)`
- `SearchScoreSpec.boostByPath(path)`
- `SearchScoreSpec.boostByPath(path, undefined)`
- `SearchScoreSpec.constant(double value)`
- `SearchScoreSpec.function(spec -> ...)`

### 기본 예시

```java
SearchScoreSpec boosted = SearchScoreSpec.boost(2.5);
SearchScoreSpec constant = SearchScoreSpec.constant(100);
SearchScoreSpec pathBoost = SearchScoreSpec.boostByPath(ArticleField.scoreWeight, 1.0);
```

### function 예시

```java
SearchScoreSpec score = SearchScoreSpec.function(fn -> fn
    .multiply(expr -> expr
        .scoreRelevance()
        .path(ArticleField.scoreWeight, 1.0)
    )
);
```

### 조금 더 복합적인 예시

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

현재 builder 는 다음을 지원합니다.

- `constant(...)`
- `scoreRelevance()`
- `path(...)`
- `add(...)`
- `multiply(...)`
- `gauss(...)`
- `log(...)`
- `log1p(...)`

점수 커스터마이징이 필요한 clause 에 `.score(...)` 로 연결해서 사용하면 됩니다.

---

## `compound(...)`

`compound(...)` 는 여러 Atlas Search clause 를 결합하는 핵심 entry point 입니다.

지원 그룹:

- `must(...)`
- `mustNot(...)`
- `should(...)`
- `filter(...)`
- `minimumShouldMatch(int)`
- `score(SearchScoreSpec)`

편의 헬퍼:

- `mustText(...)`
- `shouldText(...)`
- `filterText(...)`
- `mustPhrase(...)`
- `shouldAutocomplete(...)`
- `filterEquals(...)`
- `filterIn(...)`
- `filterRange(...)`
- `mustNotExists(...)`

### 예시

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

### `filter` 와 post-search `fields(...)` 의 차이

- `compound.filter(...)` : Atlas Search clause 계층 안에서 필터링
- `search().fields(...)` : `$search` 뒤에 붙는 일반 Mongo `$match`

즉, search engine clause 와 일반 Mongo 조건을 의도적으로 분리하고 싶을 때 `search().fields(...)` 를 사용하면 됩니다.

---

## 정렬과 score 출력

### score 필드를 결과에 추가

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

### score 우선 정렬

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

`SearchSortSpec` 지원 항목:

- `SearchSortSpec.scoreDesc()`
- `SearchSortSpec.scoreAsc()`
- `SearchSortSpec.asc(path)`
- `SearchSortSpec.desc(path)`

### 안정적인 ordering 권장

동일 score 인 문서가 여러 개면 결과 순서는 비결정적일 수 있습니다.
실서비스에서는 score 뒤에 unique field 정렬을 추가하는 것을 권장합니다.

---

## Count 동작

Atlas Search 에서는 count 의미가 둘로 나뉩니다.

### 1) 최종 파이프라인 결과 count

```java
Mono<Long> count =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .fields(pair("deleted", false))
     .count()
     .execute();
```

이것은 `$search` 이후, 그리고 post-search `$match` 이후의 **최종 결과 수** 입니다.

### 2) Atlas Search metadata count

```java
Mono<Long> total =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .countType(SearchCountType.TOTAL)
     .count()
     .executeSearchMeta();
```

이것은 `$searchMeta` 를 사용한 **Atlas Search metadata count** 입니다.

지원 count 타입:

- `SearchCountType.TOTAL`
- `SearchCountType.LOWER_BOUND`

검색 엔진 기준 결과 수가 필요하면 metadata count 를,
애플리케이션 최종 표시 수가 필요하면 pipeline count 를 사용하세요.

---

## Sequence token 기반 페이지 탐색

Atlas Search 는 sequence token 기반 페이지 탐색을 지원합니다.

### token 출력

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

### token 이후 조회

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

반대로 `searchBefore(token)` 도 사용할 수 있습니다.

---

## Atlas Search 전용 exists terminal

Atlas Search 흐름에서는 다음처럼 사용합니다.

```java
Mono<Boolean> exists =
  dsl.executeEntity(Article.class, MongoTemplateName.FRONT)
     .search("articles_default")
     .text(t -> t.path(ArticleField.title).query("atlas"))
     .existsQuery()
     .execute();
```

이 이름을 `existsQuery()` 로 둔 이유는, 일반 query builder 의 `exists()` 와 의미를 분리하기 위해서입니다.

---

## 주의사항 / 제한

- Atlas Search 는 질의 대상 필드에 맞는 Search index 가 필요합니다.
- `$search`, `$searchMeta` 는 각각 파이프라인 첫 stage 여야 합니다.
- `$search` 는 `$facet` 내부에 들어갈 수 없습니다.
- `AutocompleteClause` 는 의도적으로 single path 만 지원합니다.
- `TextClause` 는 현재 `fuzzy + synonyms` 동시 사용을 막습니다.
- `count().execute()` 와 `count().executeSearchMeta()` 는 의미가 다르므로 구분해서 써야 합니다.
- 안정적인 ordering 이 필요하면 score 만 믿지 말고 unique field 정렬을 추가하세요.

---

## 결과 래퍼

- `PageResult<T>` : 전통적인 페이지 객체 (`List<T> data`, `Long totalCount`)
- `PageStream<T>` : reactive 친화 래퍼 (`Flux<T> data`, `Mono<Long> totalCount`)
- `ResultTuple<L, R>` : lookup / grouping 결과를 함께 담는 컨테이너

---

## 마지막 팁

헷갈릴 때는 다음 기준으로 생각하면 됩니다.

- 일반 Mongo 조회/필터링이면 `FieldsPair` + `fields(...).end()`
- Atlas Search 기반 검색이면 `search(...)`
- Atlas Search 뒤에 일반 Mongo `$match` 를 의도적으로 넣고 싶을 때만 `search().fields(...)`
