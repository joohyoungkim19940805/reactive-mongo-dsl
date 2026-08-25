# Reactive Mongo DSL (reactive-mongo-dsl)

A fluent DSL built on the MongoDB **Reactive Streams Driver** and Project Reactor for **dynamic conditions / querying / paging / aggregation / `$lookup` / grouping / atomic updates / bulk operations / Atlas Search / Vector Search**.

The `reactive-mongo-dsl` core does not directly depend on Spring Data MongoDB. Mongo execution is abstracted through `MongoExecutionContext`, and the default `DriverMongoExecutionContext` implementation uses the MongoDB Reactive Streams Driver directly.

---

## Current version

The current source version in `build.gradle` is:

- `reactive-mongo-dsl`: `1.0.0`
- Java: 21+
- MongoDB Java Driver BOM: `5.10.0`
- Reactor BOM: `2025.0.7`

Gradle:

```gradle
dependencies {
    implementation 'com.byeolnaerim:reactive-mongo-dsl:1.0.0'
}
```

Maven:

```xml
<dependency>
    <groupId>com.byeolnaerim</groupId>
    <artifactId>reactive-mongo-dsl</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Requirements

- Java 21+
- An environment compatible with MongoDB Reactive Streams Driver 5.9.x (5.10.0 Available)
- Project Reactor
- A MongoDB Atlas Search index when using Atlas Search
- A MongoDB Vector Search index when using Vector Search
- Application-side Reactive MongoDB configuration (`ReactiveMongoTemplate`, `MongoClient`) when integrating with Spring Data MongoDB

Spring Data MongoDB is not a required dependency of the core library. The DSL can be used in a Spring application, but its execution contract is based on `MongoExecutionContext` and the MongoDB Reactive Streams Driver.

---

## Core concepts

### 1) Execution context routing: `MongoTemplateResolver<K>` + `MongoExecutionContext`

`ReactiveMongoDsl<K>` obtains a `MongoExecutionContext` for each key through `MongoTemplateResolver<K>`.

```java
public interface MongoTemplateResolver<K> {
    MongoExecutionContext getTemplate(K key);
}
```

`MongoExecutionContext` is responsible for:

- providing a `MongoDatabase`
- starting a `ClientSession`
- resolving collection names by entity type
- converting entities to and from BSON `Document`
- reading entity ids and applying generated ids back to entities
- `beforePersist` / `afterPersist` lifecycle hooks for save operations
- `getSessionScope()` for identifying contexts that share the same Mongo client/session scope

`DriverMongoExecutionContext` is provided as the default implementation.

This lets the same DSL be used across multiple databases, clusters, or tenants by changing only the resolver key.

---

### 2) Standard query flow

The normal query/update flow is:

```text
executeEntity(...) / executeCustomClass(...)
    -> fields(...) / driverFilter(...)
    -> end()
    -> findAll / find / count / distinct / delete / exists / atomicUpdate
    -> execute...
```

Example:

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(
        pair("status", "ACTIVE"),
        pair("age", 20, Condition.gte)
    )
    .end()
    .findAll()
    .execute();
```

Operations that do not need a condition builder, such as `save`, `saveAll`, bulk operations, and `createHistory`, can be called directly from `executeEntity(...)` or `executeCustomClass(...)`.

There is no separate public `update()` terminal. Conditional updates are executed through `atomicUpdate()` after selecting `first()`, `multi()`, or `upsertOne()`.

---

### 3) Atlas Search / Vector Search use separate entry points

Atlas Search and Vector Search are separated from the ordinary `fields(...).end()` flow because of their first aggregation stage constraints.

```text
executeEntity(...)
    -> search(...)
    -> Atlas Search operator
    -> findAll / find / count / existsQuery
```

```text
executeEntity(...)
    -> vectorSearch(...)
    -> vector query / filter
    -> findAll / find / count / existsQuery
```

The meaning and placement of ordinary Mongo conditions differ from Search/Vector conditions, so they are documented separately in the dedicated sections below.

---

## Quick start

### 1) Configure `DriverMongoExecutionContext`

```java
import com.byeolnaerim.mongodsl.ReactiveMongoDsl;
import com.byeolnaerim.mongodsl.spi.DriverMongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

public enum MongoTemplateName {
    FRONT,
    BACK
}

MongoClient frontClient = MongoClients.create(frontConnectionString);
MongoClient backClient = MongoClients.create(backConnectionString);

MongoDatabase frontDatabase = frontClient.getDatabase("front");
MongoDatabase backDatabase = backClient.getDatabase("back");

MongoExecutionContext frontContext =
    new DriverMongoExecutionContext(frontClient, frontDatabase);

MongoExecutionContext backContext =
    new DriverMongoExecutionContext(backClient, backDatabase);

MongoTemplateResolver<MongoTemplateName> resolver = key ->
    key == MongoTemplateName.BACK ? backContext : frontContext;

ReactiveMongoDsl<MongoTemplateName> dsl = new ReactiveMongoDsl<>(resolver);
```

By default, `DriverMongoExecutionContext` resolves collection names by decapitalizing the entity's simple class name.

Example:

```text
User -> user
AuctionHistory -> auctionHistory
```

If your application uses a different collection naming rule, provide a resolver:

```java
MongoExecutionContext context = new DriverMongoExecutionContext(
    mongoClient,
    mongoDatabase,
    entityClass -> collectionNameResolver(entityClass)
);
```

If collection-name resolution is completely stable for the lifetime of the application, you can use the caching helper:

```java
new DriverMongoExecutionContext(
    mongoClient,
    mongoDatabase,
    DriverMongoExecutionContext.cachedCollectionNameResolver(
        entityClass -> collectionNameResolver(entityClass)
    )
);
```

Do not use this caching helper when collection names can vary by tenant, request, time, or another runtime condition.

---

### 2) Using it in a Spring Data MongoDB project

The `reactive-mongo-dsl` core does not directly depend on Spring Data MongoDB, so the application provides a `MongoExecutionContext` adapter when it wants to use an existing `ReactiveMongoTemplate` configuration.

To retain the existing Spring application's **collection naming / `MongoConverter` / custom conversions / reactive auditing**, connect those facilities through the adapter and return that adapter from the resolver.

With Spring Boot, keep the normal Reactive MongoDB starter in the application:

```gradle
dependencies {
    implementation 'com.byeolnaerim:reactive-mongo-dsl:1.0.0-alpha.4'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
}
```

The Spring dependency belongs to the consuming application; it does not need to be added to the DSL core.

#### `SpringReactiveMongoExecutionContext` adapter

The following adapter connects a `ReactiveMongoTemplate` and its corresponding `MongoClient` to `MongoExecutionContext`:

```java
import java.util.Objects;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.callback.ReactiveEntityCallbacks;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.mapping.event.ReactiveAfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeConvertCallback;

import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class SpringReactiveMongoExecutionContext implements MongoExecutionContext {

    private final ReactiveMongoTemplate reactiveMongoTemplate;
    private final MongoClient mongoClient;
    private final MongoConverter mongoConverter;
    private final ReactiveEntityCallbacks entityCallbacks;

    public SpringReactiveMongoExecutionContext(
        ReactiveMongoTemplate reactiveMongoTemplate,
        MongoClient mongoClient,
        ReactiveEntityCallbacks entityCallbacks
    ) {
        this.reactiveMongoTemplate = Objects.requireNonNull(
            reactiveMongoTemplate,
            "reactiveMongoTemplate must not be null"
        );
        this.mongoClient = Objects.requireNonNull(
            mongoClient,
            "mongoClient must not be null"
        );
        this.mongoConverter = reactiveMongoTemplate.getConverter();
        this.entityCallbacks = Objects.requireNonNull(
            entityCallbacks,
            "entityCallbacks must not be null"
        );
    }

    @Override
    public Mono<MongoDatabase> getDatabase() {
        return reactiveMongoTemplate.getMongoDatabase();
    }

    @Override
    public Mono<ClientSession> startSession() {
        return Mono.from(mongoClient.startSession());
    }

    @Override
    public String getCollectionName(Class<?> entityClass) {
        return reactiveMongoTemplate.getCollectionName(entityClass);
    }

    @Override
    public Document write(Object source) {
        Document document = new Document();
        mongoConverter.write(source, document);
        return document;
    }

    @Override
    public <T> T read(Class<T> targetType, Document source) {
        return mongoConverter.read(targetType, source);
    }

    @Override
    public Object getId(Object entity) {
        MongoPersistentEntity<?> persistentEntity = getPersistentEntity(entity.getClass());

        if (persistentEntity == null || !persistentEntity.hasIdProperty()) {
            return null;
        }

        Object id = persistentEntity.getIdentifierAccessor(entity).getIdentifier();

        if (id == null) {
            return null;
        }

        Document document = new Document();
        mongoConverter.write(entity, document);
        return document.get("_id");
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void setId(Object entity, Object id) {
        MongoPersistentEntity persistentEntity = getPersistentEntity(entity.getClass());

        if (persistentEntity == null || !persistentEntity.hasIdProperty()) {
            return;
        }

        MongoPersistentProperty idProperty =
            (MongoPersistentProperty) persistentEntity.getRequiredIdProperty();

        Object targetId = id;

        if (id instanceof ObjectId objectId && idProperty.getType() == String.class) {
            targetId = objectId.toHexString();
        } else if (
            id != null
                && !idProperty.getType().isInstance(id)
                && mongoConverter.getConversionService()
                    .canConvert(id.getClass(), idProperty.getType())
        ) {
            targetId = mongoConverter.getConversionService()
                .convert(id, idProperty.getType());
        }

        PersistentPropertyAccessor accessor = persistentEntity.getPropertyAccessor(entity);
        accessor.setProperty(idProperty, targetId);
    }

    @Override
    public <T> Mono<T> beforePersist(T entity, String collectionName) {
        return entityCallbacks.callback(
            ReactiveBeforeConvertCallback.class,
            entity,
            collectionName
        );
    }

    @Override
    public <T> Mono<T> afterPersist(
        T entity,
        Document document,
        String collectionName
    ) {
        return entityCallbacks.callback(
            ReactiveAfterSaveCallback.class,
            entity,
            document,
            collectionName
        );
    }

    @Override
    public Object getSessionScope() {
        return mongoClient;
    }

    @Override
    public Object getNative() {
        return reactiveMongoTemplate;
    }

    @Override
    public <T> T getNative(Class<T> nativeType) {
        if (nativeType.isInstance(reactiveMongoTemplate)) {
            return nativeType.cast(reactiveMongoTemplate);
        }

        if (nativeType.isInstance(mongoClient)) {
            return nativeType.cast(mongoClient);
        }

        return MongoExecutionContext.super.getNative(nativeType);
    }

    private MongoPersistentEntity<?> getPersistentEntity(Class<?> entityClass) {
        return mongoConverter.getMappingContext().getPersistentEntity(entityClass);
    }
}
```

With this adapter, entity write/read conversion goes through Spring Data's `MongoConverter`, so Spring mapping configuration such as `@Document`, `@Id`, `@Field`, and `MongoCustomConversions` continues to apply to entity conversion.

Because `beforePersist(...)` is connected to `ReactiveBeforeConvertCallback`, reactive auditing callbacks such as `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, and `@LastModifiedBy` are also applied to `save()` operations when reactive auditing is enabled in the Spring application.

The DSL does not call Spring `ReactiveMongoTemplate` CRUD methods themselves. MongoDB operations are executed with the Reactive Streams Driver, while the adapter connects Spring **mapping / collection naming / selected entity lifecycle callbacks**.

#### Resolver configuration

For a single MongoDB, a resolver can still use a single key. Applications with multiple `ReactiveMongoTemplate` instances can create one `MongoExecutionContext` per template/client pair and route them by key.

```java
public enum MongoTemplateName {
    FRONT,
    BACK
}
```

```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mapping.callback.ReactiveEntityCallbacks;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;

import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;
import com.mongodb.reactivestreams.client.MongoClient;

@Component
public class MongoTemplateNameResolver
    implements MongoTemplateResolver<MongoTemplateName> {

    private final MongoExecutionContext frontExecutionContext;
    private final MongoExecutionContext backExecutionContext;

    public MongoTemplateNameResolver(
        @Qualifier("frontMongoTemplate") ReactiveMongoTemplate frontTemplate,
        @Qualifier("frontMongoClient") MongoClient frontMongoClient,
        @Qualifier("backMongoTemplate") ReactiveMongoTemplate backTemplate,
        @Qualifier("backMongoClient") MongoClient backMongoClient,
        ApplicationContext applicationContext
    ) {
        ReactiveEntityCallbacks entityCallbacks =
            ReactiveEntityCallbacks.create(applicationContext);

        this.frontExecutionContext = new SpringReactiveMongoExecutionContext(
            frontTemplate,
            frontMongoClient,
            entityCallbacks
        );

        this.backExecutionContext = new SpringReactiveMongoExecutionContext(
            backTemplate,
            backMongoClient,
            entityCallbacks
        );
    }

    @Override
    public MongoExecutionContext getTemplate(MongoTemplateName key) {
        return switch (key) {
            case FRONT -> frontExecutionContext;
            case BACK -> backExecutionContext;
        };
    }
}
```

Pass a `ReactiveMongoTemplate` and `MongoClient` that belong to the **same Mongo configuration**. Collection/database access is based on the template, while `ClientSession` is started from the provided `MongoClient`.

When multiple database contexts use the same `MongoClient`, their `getSessionScope()` values are the same and the DSL can share one session across them inside a transaction. Contexts backed by different `MongoClient` instances cannot be combined into one DSL transaction session.

#### Register `ReactiveMongoDsl` as a bean

After registering the resolver, register `ReactiveMongoDsl` itself as a Spring bean and inject it into application services.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.byeolnaerim.mongodsl.ReactiveMongoDsl;
import com.byeolnaerim.mongodsl.spi.MongoTemplateResolver;

@Configuration
public class ReactiveMongoDslConfiguration {

    @Bean
    public ReactiveMongoDsl<MongoTemplateName> reactiveMongoDsl(
        MongoTemplateResolver<MongoTemplateName> resolver
    ) {
        return new ReactiveMongoDsl<>(resolver);
    }
}
```

Then inject it into any Spring bean:

```java
import org.springframework.stereotype.Service;

import com.byeolnaerim.mongodsl.ReactiveMongoDsl;

@Service
public class UserService {

    private final ReactiveMongoDsl<MongoTemplateName> dsl;

    public UserService(ReactiveMongoDsl<MongoTemplateName> dsl) {
        this.dsl = dsl;
    }
}
```

#### What the Spring adapter applies

| Item | Applied | Description |
| --- | --- | --- |
| `@Document(collection = ...)` | Yes | Uses `ReactiveMongoTemplate#getCollectionName(...)` |
| `@Id` / id conversion | Yes | Uses `MongoConverter` and Spring mapping metadata |
| `@Field` / custom conversion | Yes | Uses `MongoConverter` for entity ↔ `Document` conversion |
| Reactive auditing | Yes (`save()` operations) | Applied through `ReactiveBeforeConvertCallback` |
| `ReactiveAfterSaveCallback` | Yes (`save()` operations) | Called immediately after a successful write |
| `ReactiveBeforeSaveCallback` | No | `MongoExecutionContext` currently has no post-conversion/pre-write hook |
| `ReactiveAfterConvertCallback` | No | The DSL `read(...)` contract is synchronous and does not invoke a separate reactive callback |
| Spring query field mapping | No | String field paths in the DSL use MongoDB document field names |
| Spring `DataAccessException` translation | No | MongoDB operations are executed by the Reactive Streams Driver |
| Automatic participation in Spring `@Transactional` | No | DSL transactions use their own `ClientSession` flow |

When a Java property and stored field name differ, such as `@Field("user_name")`, Spring mapping applies to entity write/read conversion, but DSL conditions should use the actual MongoDB field name.

```java
@Field("user_name")
private String userName;
```

```java
// O: MongoDB document field name
.fields(pair("user_name", "kim"))

// Not automatically remapped through Spring QueryMapper
.fields(pair("userName", "kim"))
```

The `id` path is normalized to `_id` by the DSL's own path rule.

Lifecycle hooks apply to `save()` operations. Bulk/history/remove paths do not invoke them, and `atomicUpdate()` does not pass through the entity save lifecycle, so auditing fields required by an atomic update should be included explicitly in the update itself.

---

### 3) Basic query

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.pair;
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(
        pair("status", "ACTIVE"),
        pair("age", 20, gte),
        pair("name", "kim", like)
    )
    .end()
    .findAll()
    .execute();
```

Single result:

```java
Mono<User> user = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .find()
    .execute();
```

Both `find().execute()` and `find().executeFirst()` return `Mono<E>`. `executeFirst()` explicitly applies `limit(1)` to the query spec before reading the first result.

---

## Execution context

### `executeEntity(...)`

Select the target entity type and resolver key.

```java
var userDsl = dsl.executeEntity(User.class, MongoTemplateName.FRONT);
```

The collection name is resolved by `MongoExecutionContext#getCollectionName(User.class)`.

---

### `executeCustomClass(...)`

Use this when the read/write type and the physical collection name need to be specified directly.

```java
Mono<Document> raw = dsl
    .executeCustomClass(Document.class, MongoTemplateName.FRONT, "user_archive")
    .fields(pair("status", "ACTIVE"))
    .end()
    .find()
    .execute();
```

---

## Field names and `id` rules

String field names in the DSL are treated as **physical MongoDB field names** by default.

Spring Data property mapping is not automatically applied to these field paths.

When an exact path segment is named `id`, however, it is normalized to MongoDB `_id`.

```java
pair("id", value)             // -> _id
pair("parent.id", value)      // -> parent._id
pair("incidentId", value)     // -> incidentId unchanged
pair("_id", value)            // -> _id unchanged
```

When the `id` alias is used and its value is a valid 24-character hexadecimal string, it is automatically converted to `ObjectId`.

```java
pair("id", "64f0...")
```

The condition above can therefore render as an `_id: ObjectId(...)` comparison.

When `_id` is specified directly, a String value is not automatically converted to `ObjectId`.

```java
pair("_id", new ObjectId(id))
```

Provide the correct value type explicitly when using `_id` directly.

Enum field names use `Enum#toString()`. If physical Mongo field names are represented by an enum, define `toString()` to return the actual stored field name.

---

## Conditions: `FieldsPair`

Basic forms:

```java
FieldsPair.pair(field, value)
FieldsPair.pair(field, value, condition)
FieldsPair.pair(field, condition)
```

Supported `Condition` values:

| Condition | Meaning |
|---|---|
| `eq` | equal |
| `notEq` | not equal |
| `between` | inclusive range |
| `gt` / `gte` | greater than / greater than or equal |
| `lt` / `lte` | less than / less than or equal |
| `in` / `notIn` | in / not in |
| `like` | case-insensitive regex |
| `regex` | regex |
| `exists` | field existence |
| `isNull` / `isNotNull` | null / not-null comparison |
| `all` | array `$all` |
| `near` | legacy 2d near |
| `nearSphere` | spherical near |
| `elemMatch` | array element condition |

Example:

```java
.fields(
    pair("status", List.of("READY", "ACTIVE"), in),
    pair("price", List.of(10_000L, 50_000L), between),
    pair("deletedAt", isNull)
)
```

`like` does not escape the input as an ordinary literal substring. It passes the input as a MongoDB regex pattern and applies the `i` option.

---

### `autoRangePair(...)`

Automatically selects `between`, `gte`, or `lte` depending on whether `from` and `to` are present.

```java
FieldsPair<String, Object> createdAt =
    FieldsPair.autoRangePair("createdAt", from, to);
```

Behavior:

```text
from != null && to != null -> between
from != null && to == null -> gte
from == null && to != null -> lte
from == null && to == null -> null
```

Overloads are provided for `Instant`, `LocalDateTime`, `LocalDate`, and `[from, to]` list forms.

When both values are absent, the method returns `null`, so it can be omitted naturally when assembling dynamic conditions.

---

## AND / OR / NOT grouping

`FieldBuilder` supports `and`, `or`, `not`, `notAny`, and `notAll`.

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
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

You can also choose the root logical operator directly.

```java
.fields(ReactiveMongoDsl.LogicalOperator.OR,
    pair("status", "READY"),
    pair("status", "ACTIVE")
)
```

---

## Driver-native filter escape hatch

MongoDB Driver filters that do not have a dedicated DSL `Condition` can be inserted directly.

```java
import com.mongodb.client.model.Filters;

Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .driverFilter(
        Filters.and(
            Filters.eq("status", "ACTIVE"),
            Filters.size("tags", 3)
        )
    )
    .end()
    .findAll()
    .execute();
```

Raw `Bson` passed this way is not reinterpreted or field-mapped by the DSL; the Driver definition is used as-is.

---

## Querying: `findAll()` / `find()`

### `findAll()`

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort
        .desc("createdAt")
        .asc("id")
    )
    .excludes("largePayload")
    .execute();
```

Main options:

- `paging(pageNumber, pageSize)`
- `sorts()` / `sorts(callback)`
- `excludes(...)`
- `readPreference(...)`
- `isAllowDiskUse(...)`
- `customizeQuery(...)`
- `customizeAggregation(...)`

---

### Sorting: `SortSpec`

Sort keys preserve the order in which they are added.

```java
.findAll()
.sorts(sort -> sort
    .desc("score")
    .asc("createdAt")
    .desc("id")
)
.execute();
```

Dynamic direction:

```java
.sorts(sort -> sort.of(direction, field))

.sorts( sortSpec -> sorts.stream().filter( e -> ! e.trim().isBlank() ).limit( 10 ).forEach( e -> {
				String[] paths = e.split( "=" );
				if (paths.length != 2 || Stream.of( paths ).anyMatch( String::isBlank ))
					return;

				sortSpec.of( paths[1], paths[0].trim() );

			} ) )
```

Supported direction values are `asc` and `desc`, case-insensitive.

Driver-native sorts can be mixed in as well.

```java
.sorts(sort -> sort
    .driver(Sorts.metaTextScore("score"))
    .desc("id")
)
```

---

### `find()`

```java
Mono<User> one = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .find()
    .sorts(sort -> sort.desc("createdAt"))
    .executeFirst();
```

`find()` also supports `sorts`, `excludes`, `readPreference`, `isAllowDiskUse`, and query/aggregation customizers.

---

## Paging

### Standard find paging

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .sorts(sort -> sort.desc("id"))
    .execute();
```

`pageNumber` is zero-based.

The nested builder style is also supported.

```java
.findAll()
.paging()
    .pageNumber(0)
    .pageSize(20)
    .and()
.execute();
```

---

### `PageStream<T>`

Use `executePageStream()` when you want to keep page data as a `Flux` instead of collecting it into a `List` first.

```java
PageStream<User> page = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .executePageStream();

Flux<User> data = page.data();
Mono<Long> totalCount = page.totalCount();
```

For batch or streaming processing, `PageStream` can be preferable to collecting immediately into `PageResult`.

---

## Aggregation execution

Ordinary condition builders can also be executed as aggregation pipelines.

```java
Flux<User> stream = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .sorts(sort -> sort.desc("id"))
    .executeAggregationStream();
```

Reactive page:

```java
PageStream<User> page = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .executeAggregationPageStream();
```

When the traditional `PageResult<T>` form is needed:

```java
Mono<PageResult<User>> page = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .executeAggregation();
```

`executeAggregation()` collects the reactive page data into a final `List` and creates a `PageResult`.

For a single result, use `find().executeAggregation()`.

---

## `count()` / `exists()` / `delete()` / `distinct()`

### count

```java
Mono<Long> count = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .count()
    .execute();
```

Aggregation count:

```java
Mono<Long> count = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .count()
    .executeAggregation();
```

---

### exists

```java
Mono<Boolean> exists = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .exists()
    .execute();
```

Aggregation-based existence checks are also supported.

```java
.exists().executeAggregation();
```

---

### Conditional delete

```java
Mono<DeleteResult> deleted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "REMOVED"))
    .end()
    .delete()
    .execute();
```

This `delete().execute()` terminal deletes all documents matching the current criteria through `deleteMany` semantics.

To delete one entity by its id, use the separate `executeEntity(...).delete(entity)` API.

---

### distinct

`distinct` returns `Flux<R>` and does not collect results into a `List` internally.

```java
Flux<String> statuses = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("caseYear", 2026))
    .end()
    .distinct("status", String.class)
    .execute();
```

Collect only when the caller actually needs a list.

```java
Mono<List<String>> statusList = statuses.collectList();
```

Enum-backed field names are also supported.

```java
.distinct(UserField.STATUS, String.class)
```

---

## Saving: `save()` / `saveAll()`

### `save()`

```java
Mono<User> saved = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .save(user);
```

Current behavior:

- no id: `insertOne`
- id present: `replaceOne(..., upsert=true)` by `_id`
- when MongoDB generates `_id`, it is applied back to the entity through `MongoExecutionContext#setId(...)` when possible

`save()` passes through the `MongoExecutionContext.beforePersist(...)` and `afterPersist(...)` lifecycle hooks.

---

### `saveAll()`

```java
Flux<User> saved = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAll(users);
```

Overloads are provided for `Iterable`, `Collection`, and `Flux`.

`saveAll()` executes `save()` for each entity, so the save lifecycle hooks are applied to each entity as well.

---

## Bulk operations

### `saveAllBulk()`

```java
Flux<User> inserted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAllBulk(users);
```

The current implementation collects the entities and executes `insertMany`.

- this is a **bulk insert**, not an update by existing id
- generated `_id` values are applied back to original entities when possible
- `beforePersist` / `afterPersist` hooks are not invoked

---

### `saveAllBulkUpsert()`

Bulk upserts by entity id.

```java
Mono<BulkWriteResult> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAllBulkUpsert(users);
```

Behavior:

- no id: `InsertOneModel`
- id present: `_id` filter + `$set` + `upsert(true)`

Bulk writes run with `ordered(false)`.

---

### `saveAllBulkUpsertByKey()`

Upsert by business composite key:

```java
Mono<BulkWriteResult> result = dsl
    .executeEntity(Auction.class, MongoTemplateName.FRONT)
    .saveAllBulkUpsertByKey(
        auctions,
        "caseNo",
        "caseYear",
        "court"
    );
```

If any specified key is missing, that entity is inserted instead.

When all keys are present:

- key fields: `$setOnInsert`
- all document fields except `_id` and key fields: `$set`
- `upsert(true)`

Bulk APIs do not invoke save lifecycle hooks.

---

## Entity deletion and remove backup

An entity instance can be deleted directly.

```java
Mono<DeleteResult> deleted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .delete(user);
```

When the entity has an id, deletion uses `_id`. When it has no id, the entity is converted to BSON and that document is used as the filter.

To keep a copy in a remove collection:

```java
dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .delete(user, true);
```

Backup collection name:

```text
<sourceCollection>_remove
```

Bulk delete:

```java
Mono<BulkWriteResult> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .deleteBulk(users, true);
```

In the current implementation, `delete(entity, true)` performs the actual delete and then writes the backup to the remove collection. `deleteBulk(..., true)` writes the backup first and then performs the bulk delete. The actual deletion set of `deleteBulk(...)` consists of entities that have an id.

If deletion and backup need to be atomic for `delete(..., true)` or `deleteBulk(..., true)`, wrap the operation in a caller-controlled `getTxJob(...)` transaction.

---

## History snapshots

`createHistory(entity[, prefix])` deep-copies the current `MongoExecutionContext.write(...)` result, removes `_id`, and inserts the snapshot into a separate collection.

```java
Mono<Void> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .createHistory(user);
```

Default collection:

```text
<sourceCollection>_history
```

Custom prefix:

```java
.createHistory(user, "snapshot")
```

Result:

```text
<sourceCollection>_snapshot
```

A leading underscore in a prefix such as `_snapshot` is normalized so duplicate underscores are not produced.

History writes do not invoke save lifecycle hooks.

---

## Transactions: `getTxJob(...)`

Transactions use MongoDB `ClientSession` directly.

```java
Mono<String> result = dsl.getTxJob(
    MongoTemplateName.FRONT,
    () -> dsl
        .executeEntity(User.class, MongoTemplateName.FRONT)
        .save(user)
        .then(
            dsl.executeEntity(Audit.class, MongoTemplateName.FRONT)
                .save(audit)
        )
        .thenReturn("committed")
);
```

`getTxJob(...)` performs the following flow:

1. `MongoExecutionContext.startSession()`
2. `session.startTransaction()`
3. place the session in Reactor Context
4. commit on normal completion
5. abort an active transaction on error/cancel
6. close the session

A transaction session is propagated only to DSL executions whose `MongoExecutionContext#getSessionScope()` is the same.

`DriverMongoExecutionContext` uses its `MongoClient` as the default session scope, so contexts sharing the same client can participate in the same transaction session.

Contexts using different `MongoClient` instances cannot be combined into the same transaction.

`getTxJob(...)` does not automatically retry MongoDB transaction errors such as `TransientTransactionError`. Retry policy belongs to the calling application and should be applied explicitly according to the operation's business semantics.

### Transactions in Spring applications

Using the Spring adapter does not change how DSL transactions work.

`@Transactional`, `ReactiveMongoTransactionManager`, and `TransactionalOperator` define transaction boundaries managed by Spring Data MongoDB. `ReactiveMongoDsl#getTxJob(...)` instead starts a `ClientSession` through `MongoExecutionContext.startSession()` and propagates that session through Reactor Context.

Do not assume that DSL operations automatically join a Spring-bound Mongo session just because they are called inside a Spring `@Transactional` block. Likewise, do not assume that `ReactiveMongoTemplate` or Spring Data Repository operations called inside `getTxJob(...)` automatically use the DSL session.

Use `getTxJob(...)` for transactions composed of DSL operations, and Spring's transaction infrastructure for transactions composed of Spring Data Template/Repository operations. If both execution models must participate in one transaction, the application must explicitly design the session integration.

---

## `$lookup` joins

### `LookupSpec`

`LookupSpec` supports both simple `localField / foreignField` joins and `let + pipeline + $expr` joins.

Simple join:

```java
LookupSpec spec = LookupSpec.builder()
    .as("orders")
    .localField("id")
    .foreignField("userId")
    .build();
```

Condition-based join:

```java
import static com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition.*;

LookupSpec spec = LookupSpec.builder()
    .as("orders")
    .bindConditionFields("id", eq, "userId")
    .bindConditionConst("DONE", eq, "status")
    .limit(10)
    .sorts(sort -> sort.desc("createdAt"))
    .build();
```

Main builder methods:

- `localField(...)`
- `foreignField(...)`
- `bindConditionFields(leftField, condition, rightField)`
- `bindConditionFieldsLeftToObjectId(leftField, condition, rightField)`
- `bindConditionConst(value, condition, rightField)`
- `bindConditionBetween(low, high, rightField)`
- `bindConditionLike(pattern, rightField, options)`
- `bindConditionExists(rightField, exists)`
- `bindConditionIsNull(rightField)`
- `bindConditionIsNotNull(rightField)`
- `limit(...)`
- `sorts(...)`
- `rawStage(Bson)`
- `unwind(preserveNullAndEmptyArrays)`
- `outerStage(Bson)` / `outerStages(...)`
- `outerMatchExpr(Document)`

`bindConditionFieldsLeftToObjectId(...)` converts the left field through `$convert: { to: "objectId" }` before comparing it with the right field.

Field-name rules are the same as in the normal DSL, so `id` is normalized to `_id`.

---

### `findAll().executeLookup(...)`

```java
var left = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20);

var right = dsl
    .executeEntity(Order.class, MongoTemplateName.FRONT)
    .fields(pair("status", "DONE"))
    .end()
    .findAll();

Flux<ResultTuple<User, List<Order>>> joined =
    left.executeLookup(right, spec);
```

Without `LookupSpec.unwind(...)`, the default lookup returns the right side as `List<R>`.

---

### `executeLookupAndCount(...)`

```java
Mono<PageResult<ResultTuple<User, List<Order>>>> page =
    left.executeLookupAndCount(right, spec);
```

This returns data and `totalCount` together using a `$facet(data, count)` pipeline.

---

### `find().executeLookup(...)`

Use the `find()` path when you need a single left result and a single right result.

```java
Mono<ResultTuple<User, Order>> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .find()
    .executeLookup(rightFindBuilder, spec);
```

---

### count / exists lookup

Lookup-based left/right counts or existence results can be returned together as well.

```java
Mono<ResultTuple<Long, Long>> counts = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .count()
    .executeLookup(right, spec);
```

```java
Mono<ResultTuple<Boolean, Boolean>> exists = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .exists()
    .executeLookup(right, spec);
```

---

## Grouping aggregation

Group from a query terminal builder with `group(keyType, valueType)`.

```java
Mono<Map<String, GroupResult>> grouped = dsl
    .executeEntity(Order.class, MongoTemplateName.FRONT)
    .fields(pair("status", "DONE"))
    .end()
    .findAll()
    .group(String.class, GroupResult.class)
    .by("userId")
    .count()
    .sum("amount", "totalAmount")
    .execute();
```

Supported accumulator helpers:

- `count()` / `countAs(alias)`
- `sum(field, alias)`
- `avg(field, alias)`
- `min(field, alias)`
- `max(field, alias)`
- `addToSet(field, alias)`
- `push(field, alias)`
- `accumulator(BsonField)`

Use `accumulator(...)` for a Driver-native accumulator.

To customize key/value mapping directly:

```java
.group(String.class, GroupResult.class)
.keyConverter(document -> ...)
.valueConverter(document -> ...)
```

Grouping also supports `executeLookup(rightBuilder, spec)`.

---

## Atomic updates: `atomicUpdate()`

The API makes both the target scope and update representation explicit before update operations are added.

```text
atomicUpdate()
    -> first() / multi() / upsertOne()
    -> document() / pipeline()
    -> update operations
    -> execute()
```

---

### Document update

Single document:

```java
Mono<UpdateResult> updated = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .atomicUpdate()
    .first()
    .document()
    .inc("loginCount", 1)
    .set("lastLoginAt", Instant.now())
    .execute();
```

Multiple documents:

```java
.atomicUpdate()
.multi()
.document()
.set("status", "ARCHIVED")
.execute();
```

Single-document upsert:

```java
.atomicUpdate()
.upsertOne()
.document()
.set("status", "ACTIVE")
.setOnInsert("createdAt", Instant.now())
.execute();
```

Document update helpers:

- `inc(field, delta)`
- `set(field, value)`
- `unset(field)`
- `push(field, value)`
- `addToSet(field, value)`
- `pull(field, value)`
- `driverUpdate(Bson)`

`setOnInsert(...)` is available only with `upsertOne().document()`.

---

### Pipeline update

```java
Mono<UpdateResult> updated = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .atomicUpdate()
    .first()
    .pipeline()
    .set("updatedAt", "$$NOW")
    .inc("loginCount", 1)
    .nextStage()
    .unset("legacyField")
    .execute();
```

Supported helpers:

- `set(field, valueOrExpression)`
- `inc(field, delta)`
- `unset(fields...)`
- `stage(Bson)`
- `nextStage()`

`nextStage()` flushes the currently pending `$set` operations into one pipeline stage.

To append a pipeline stage directly:

```java
.stage(Aggregates.set(new Field<>("normalized", ...)))
```

---

### Auditing / lifecycle note

`atomicUpdate()` is not an entity save; it executes Driver update operations directly.

It therefore does not pass through `MongoExecutionContext.beforePersist(...)` or `afterPersist(...)`.

Even when a Spring adapter provides auditing hooks, automatic entity-save auditing should not be expected for atomic updates.

Set required values explicitly:

```java
.atomicUpdate()
.first()
.document()
.set("updatedAt", Instant.now())
.execute();
```

---

## Query / Aggregation Driver customizers

Caller code can add Driver publisher options that are not directly exposed by the standard DSL.

Find publisher:

```java
.findAll()
.customizeQuery(publisher -> publisher.batchSize(500))
.execute();
```

Aggregation publisher:

```java
.findAll()
.customizeAggregation(publisher -> publisher.batchSize(500))
.executeAggregationStream();
```

Common options also include:

```java
.readPreference(ReadPreference.secondaryPreferred())
.isAllowDiskUse(true)
```

---

## `preview()` / `explain()`

### `preview()`

`preview()` does not execute a MongoDB query. It renders the current DSL state to a diagnostic `Document`.

Example:

```java
Mono<Document> preview = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .sorts(sort -> sort.desc("id"))
    .preview();
```

In addition to ordinary queries, preview is supported by:

- classic `findAll`
- classic `find`
- classic `count`
- classic `exists`
- `distinct`
- Atlas Search `findAll/find/count/existsQuery`
- Vector Search `findAll/find/count/existsQuery`

`preview()` is a **local diagnostic representation of the query/pipeline built by the DSL**, not a database execution plan.

---

### `explain()`

`explain()` invokes the actual MongoDB Driver explain operation.

```java
Mono<Document> explain = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .explain();
```

With explicit verbosity:

```java
import com.mongodb.ExplainVerbosity;

.explain(ExplainVerbosity.QUERY_PLANNER)
```

Supported paths pass Driver `ExplainVerbosity` values such as `QUERY_PLANNER`, `EXECUTION_STATS`, and `ALL_PLANS_EXECUTIONS` through directly.

Unlike `preview()`, `explain()` requires a real database connection.

---

## Result types

### `PageResult<T>`

Traditional page shape:

```text
List<T> data
Long totalCount
```

Use this when the final result should be collected into memory.

---

### `PageStream<T>`

Reactive-friendly page shape:

```text
Flux<T> data
Mono<Long> totalCount
```

This is suitable for batch/streaming work that needs to preserve the data stream.

If needed:

```java
page.collectToPageResult()
```

converts it to `PageResult<T>`.

---

### `ResultTuple<L, R>`

A container used by lookup/group operations to carry left/right results or a name/value pair together.

---

## Lifecycle / mapping extension

The default `DriverMongoExecutionContext` uses the MongoDB Driver POJO codec.

For framework-specific or application-specific mapping, implement or extend `MongoExecutionContext` and customize:

```java
Document write(Object source)
<T> T read(Class<T> targetType, Document source)
Object getId(Object entity)
void setId(Object entity, Object id)
<T> Mono<T> beforePersist(T entity, String collectionName)
<T> Mono<T> afterPersist(T entity, Document document, String collectionName)
```

When using Spring Data MongoDB, connect `ReactiveMongoTemplate` conversion, collection naming, and reactive lifecycle behavior through the adapter shown in **Using it in a Spring Data MongoDB project**.

The core library itself remains framework-independent.

---

## Driver-first design

This DSL is an application-level convenience layer, not a replacement for the MongoDB Java Driver.

The main principles are:

1. Prefer Driver typed builders when the Driver already provides them.
2. Let the DSL simplify repetitive composition and Reactor integration.
3. When the Driver introduces a capability first, expose a raw/driver escape hatch so it can be used immediately.

Representative escape hatches:

- `driverFilter(Bson)`
- `SortSpec.driver(Bson)`
- `LookupSpec.rawStage(Bson)`
- `Grouping.accumulator(BsonField)`
- `atomicUpdate().*.document().driverUpdate(Bson)`
- `atomicUpdate().*.pipeline().stage(Bson)`
- `SearchOperators.driver(String operatorName, SearchOperator operator)`
- `search().operator(SearchOperator)`
- `search().driverOptions(...)`
- `vectorSearch().query(VectorSearchQuery)`
- `vectorSearch().driverOptions(...)`
- `customizeQuery(...)`
- `customizeAggregation(...)`

---

## Notes

### Standard queries

- String field names refer to physical MongoDB fields, not Java property names.
- Only an exact `id` path segment is treated as an `_id` alias.
- A String passed directly to `_id` is not automatically converted to `ObjectId`.
- `like` is a case-insensitive regex and does not escape the input into a literal substring search.
- `near` / `nearSphere` use `Double[]{longitude, latitude, maxDistance[, minDistance]}`. `nearSphere` accepts distance values in meters and converts them internally to the earth-radius-based unit expected by the legacy operator; the MongoDB geo index and stored coordinate format must also match.
- `saveAllBulk()` is `insertMany`, not an upsert operation.
- Bulk/history/remove paths do not invoke save lifecycle hooks.
- `atomicUpdate()` does not invoke save lifecycle or auditing hooks.
- `getTxJob(...)` does not provide a transaction retry policy.

### `$lookup`

- Ordinary conditions in the lookup sub-pipeline are composed from the right-side builder criteria and the `LookupSpec` pipeline.
- `bindConditionFieldsLeftToObjectId(...)` converts the left-side value to `ObjectId`.
- Lookup `$expr` helpers do not support `near`, `nearSphere`, or `elemMatch`; use `rawStage(Bson)` when these are needed.

### Atlas Search

- An Atlas Search index must already be configured.
- `$search` / `$searchMeta` must satisfy the first-stage pipeline constraint.
- `autocomplete` currently supports a single path.
- `text.fuzzy` and `text.synonyms` cannot be used together.
- `search().fields(...)` is a normal `$match` after `$search`, not a filter inside `$search`.
- `count().execute()` and `count().executeSearchMeta()` have different count semantics.
- Deterministic sorting is recommended for stable sequence-token pagination.

### Vector Search

- `vectorSearch(index)` requires an explicit vector index name.
- `$vectorSearch` must be the first pipeline stage.
- ANN requires `numCandidates(...)` / `approximate(...)`.
- ENN uses `exact()`.
- `filterFields(...)` / `filter(...)` are `$vectorSearch.filter` pre-filters.
- `fields(...)` is a normal `$match` post-filter after `$vectorSearch`.
- `count()` counts pipeline results after the vector `limit`.
- Vector Search currently does not provide Atlas Search-style `executePage()`, sequence-token pagination, or metadata count terminals.

---

## Which API should I use?

A simple decision guide:

- ordinary Mongo condition query: `fields(...).end()`
- pass a Driver filter directly: `driverFilter(...)`
- multiple results: `findAll()`
- single result: `find()`
- count: `count()`
- existence check: `exists()`
- distinct stream: `distinct(field, resultClass)`
- conditional multi-delete: `end().delete()`
- entity save: `save()` / `saveAll()`
- bulk insert: `saveAllBulk()`
- id-based bulk upsert: `saveAllBulkUpsert()`
- business-key bulk upsert: `saveAllBulkUpsertByKey()`
- atomic update: `atomicUpdate()`
- join: `executeLookup(...)`
- grouped aggregation: `group(...)`
- inspect a query/pipeline before execution: `preview()`
- inspect the real execution plan: `explain()`
- Atlas Search: `search(...)`
- Vector Search: `vectorSearch(...)`
- transaction: `getTxJob(...)`

Search/Vector are additional search layers on top of the normal Mongo query DSL. They do not replace the existing query/save/update flow.

---

## Extensions: Atlas Search / Vector Search

Atlas Search and Vector Search do not replace the existing `fields(...).end()` DSL. Keep ordinary query/save/update/lookup flows as-is, and use the dedicated entry points only when a search-specific first aggregation stage is required.

## Atlas Search

Atlas Search uses a separate entry flow because `$search` / `$searchMeta` must be the first stage of the aggregation pipeline.

### Basic entry points

Default index:
```java
.search()
```

Explicit index:

```java
.search("articles_default")
```

Example:

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text
        .path("title")
        .query("reactive mongo dsl")
    )
    .findAll()
    .execute();
```

---

### Search root operators

`SearchBuilder` can configure the following operators directly:

- `text(...)`
- `phrase(...)`
- `autocomplete(...)`
- `equals(...)`
- `exists(...)`
- `in(...)`
- `range(...)`
- `compound(...)`
- `operator(AtlasSearchOperator)`
- `operator(SearchOperator)`

When the MongoDB Driver supports a new operator before the DSL adds a convenience wrapper, a Driver-native operator can be passed directly.

```java
SearchOperator driverSearchOperator = ...;

.search("articles_default")
.operator(driverSearchOperator)
.findAll()
.execute();
```

To wrap and reuse it with an explicit operator name:

```java
AtlasSearchOperator custom = SearchOperators.driver(
    "custom",
    driverSearchOperator
);

.search("articles_default")
.operator(custom)
.findAll()
.execute();
```

---

### Search path

Search clause paths accept:

- `String`
- `Enum<?>`
- Driver-native `SearchPath` / `FieldSearchPath`
- custom wrappers through `toString()` fallback

Search-specific helpers:

```java
SearchPaths.field("title")
SearchPaths.wildcard("content.*")
```

As with the ordinary DSL, String/Enum paths normalize an exact `id` segment to `_id`.

---

### `SearchOperators`

Reusable clauses can be created through `SearchOperators`.

```java
TextClause titleClause = SearchOperators.text()
    .path("title")
    .query("atlas search");
```

Available factories:

```java
SearchOperators.text()
SearchOperators.phrase()
SearchOperators.autocomplete()
SearchOperators.equals()
SearchOperators.in()
SearchOperators.exists()
SearchOperators.range()
SearchOperators.driver(operatorName, operator)
```

---

### `text`

Supported options:

- `path(...)`
- `paths(...)`
- `query(...)`
- `queries(...)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `matchCriteria(SearchMatchCriteria.ANY/ALL)`
- `synonyms(mappingName)`
- `score(SearchScoreSpec)`
- `score(SearchScore)`

Example:

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text
        .paths("title", "summary")
        .query("reactive mongo dsl")
        .matchCriteria(SearchMatchCriteria.ALL)
        .fuzzy(1, 1, 50)
        .score(SearchScoreSpec.boost(3.0))
    )
    .findAll()
    .execute();
```

`fuzzy(...)` currently allows only 1 or 2 for `maxEdits`.

`fuzzy(...)` and `synonyms(...)` cannot be used together.

For `matchCriteria(...)`, the DSL narrowly fills the BSON option only where Driver 5.9.x does not yet provide a dedicated typed method.

---

### `phrase`

Supported options:

- `path(...)` / `paths(...)`
- `query(...)` / `queries(...)`
- `slop(...)`
- `synonyms(...)`
- `score(...)`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .phrase(phrase -> phrase
        .path("title")
        .query("reactive mongo dsl")
        .slop(2)
        .score(SearchScoreSpec.boost(2.0))
    )
    .findAll()
    .execute();
```

---

### `autocomplete`

Autocomplete currently uses a single path.

Supported options:

- `path(...)`
- `query(...)` / `queries(...)`
- `tokenOrder(SearchTokenOrder.ANY/SEQUENTIAL)`
- `fuzzy(...)`
- `score(...)`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_autocomplete")
    .autocomplete(auto -> auto
        .path("titleAutocomplete")
        .query("rea mon")
        .tokenOrder(SearchTokenOrder.SEQUENTIAL)
        .fuzzy(1, 1, 20)
    )
    .findAll()
    .execute();
```

---

### `equals`

`equals` compares one path with one value.

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .equals(eq -> eq
        .path("status")
        .value("PUBLISHED")
    )
    .findAll()
    .execute();
```

The current clause provides typed value overloads supported by the Driver for String, Boolean, integral/floating-point numbers, `Instant`, `ObjectId`, UUID, and others, plus `valueNull()`.

---

### `exists`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .exists(exists -> exists.path("publishedAt"))
    .findAll()
    .execute();
```

The name is similar to the ordinary Mongo `end().exists()` terminal, but the meanings differ:

- `search().exists(...)`: Atlas Search operator
- `search()....existsQuery()`: terminal that checks whether the search result contains at least one document

---

### `in`

`InClause` provides APIs that distinguish the value type explicitly.

Example:

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .in(in -> in
        .path("status")
        .valuesStrings(List.of("PUBLISHED", "ARCHIVED"))
    )
    .findAll()
    .execute();
```

Main value helpers:

- `valuesStrings(...)`
- `valuesBooleans(...)`
- `valuesIntegers(...)`
- `valuesLongs(...)`
- `valuesDoubles(...)`
- `valuesInstants(...)`
- `valuesObjectIds(...)`
- `valuesUuids(...)`
- `valuesRaw(...)`

---

### `range`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .range(range -> range
        .path("publishedAt")
        .gte(from)
        .lt(to)
    )
    .findAll()
    .execute();
```

Supported boundaries:

- `gt(...)`
- `gte(...)`
- `lt(...)`
- `lte(...)`

Typed range values supported by the Driver, including numbers, dates, and `ObjectId`, can be used.

---

### `compound`

Build compound searches with `compound(...)`.

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .compound(compound -> compound
        .mustText("title", text -> text
            .query("mongodb")
        )
        .shouldText("summary", text -> text
            .query("mongodb")
        )
        .filterEquals("status", eq -> eq
            .value("PUBLISHED")
        )
        .mustNotExists("deletedAt")
        .minimumShouldMatch(0)
    )
    .findAll()
    .execute();
```

Operators themselves can also be passed directly.

```java
.compound(compound -> compound
    .must(SearchOperators.text().path("title").query("mongo"))
    .should(SearchOperators.phrase().path("title").query("reactive mongo"))
    .filter(SearchOperators.equals().path("status").value("PUBLISHED"))
)
```

Supported groups:

- `must(...)`
- `mustNot(...)`
- `should(...)`
- `filter(...)`
- `minimumShouldMatch(...)`
- `score(...)`

Convenience helpers:

- `mustText(...)`
- `shouldText(...)`
- `filterText(...)`
- `mustPhrase(...)`
- `shouldAutocomplete(...)`
- `filterEquals(...)`
- `filterIn(...)`
- `filterRange(...)`
- `mustNotExists(...)`

---

### Search clauses vs post-search `fields(...)`

This distinction is important.

#### Search clauses

`text`, `phrase`, `autocomplete`, `compound.filter`, and similar operators are placed inside the `$search` stage.

They participate directly in Search indexing and scoring.

#### `search().fields(...)`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text
        .path("title")
        .query("mongodb")
    )
    .fields(
        pair("deleted", false),
        pair("status", "PUBLISHED")
    )
    .findAll()
    .execute();
```

The `fields(...)` above is not a filter inside `$search`.

Conceptually the stage order is:

```text
$search
-> $match (fields)
-> metadata addFields / score match
-> paging
-> projection
```

Use `compound.filter(...)` for conditions that should restrict candidates at the search-index stage. Use `search().fields(...)` only when an ordinary MongoDB condition should be applied after Search has produced candidates.

---

### Search score

#### Score adjustment

`SearchScoreSpec` helpers:

```java
SearchScoreSpec.boost(3.0)
SearchScoreSpec.boostByPath("popularity")
SearchScoreSpec.constant(1.0)
SearchScoreSpec.function(...)
```

Driver-native `SearchScore` can also be passed directly to a clause.

---

#### Add score to result documents

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsScore()
.findAll()
.execute();
```

The default alias is `score`.

```java
.addFieldsScore("searchScore")
```

---

#### Post-filter by score range

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.matchScoreGte(1.5)
.findAll()
.execute();
```

Supported helpers:

```java
matchScoreGte(min)
matchScoreLte(max)
matchScoreBetween(min, max)
```

These are not conditions inside `$search`. The score is first exposed through `$addFields`, then filtered by a later `$match`.

As a result, `executePage()` and `count().execute()` use the same score threshold.

---

### Search sorting

Ordinary field sorting:

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.sorts(sort -> sort
    .desc("publishedAt")
    .desc("id")
)
.findAll()
.execute();
```

Score sorting is provided through `scoreDesc()` / `scoreAsc()`. Sort priority follows the order in which sort entries are added.

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.scoreDesc()
.sorts(sort -> sort.desc("publishedAt"))
.findAll()
.execute();
```

Or:

```java
.sorts(sort -> sort.desc("publishedAt"))
.scoreDesc()
```

Changing the order changes sort priority.

For stable pagination when scores tie, add a unique/stable field sort in addition to score sorting.

---

### Search Highlight

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text
        .path("title")
        .query("mongodb")
    )
    .highlight(highlight -> highlight
        .path("title")
        .maxCharsToExamine(100_000)
        .maxNumPassages(3)
    )
    .addFieldsHighlights()
    .findAll()
    .execute();
```

`highlight(...)` configures an option on the `$search` stage, while `addFieldsHighlights(...)` exposes highlight metadata as a result-document field.

Default alias:

```text
highlights
```

Driver-native `SearchHighlight` can also be passed directly.

---

### Search score details

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsScoreDetails()
.findAll()
.execute();
```

`addFieldsScoreDetails()` also enables `scoreDetails(true)` internally.

The default alias is `scoreDetails`.

---

### Search sequence-token pagination

Expose a sequence token in each result:

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsSequenceToken()
.findAll()
.execute();
```

Default alias:

```text
searchSequenceToken
```

Next page:

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.searchAfter(token)
.findAll()
.execute();
```

Previous direction:

```java
.searchBefore(token)
```

Setting `searchAfter(...)` clears an existing `searchBefore(...)` value, and vice versa.

---

### Search paging / page result

Offset paging:

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .paging(0, 20)
    .findAll()
    .execute();
```

`PageResult`:

```java
Mono<PageResult<Article>> page = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .paging(0, 20)
    .findAll()
    .executePage();
```

Reactive page:

```java
PageStream<Article> page = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .paging(0, 20)
    .findAll()
    .executePageStream();
```

---

### Search count

Atlas Search provides two different count paths.

#### 1) Final pipeline count: `count().execute()`

```java
Mono<Long> count = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .fields(pair("status", "PUBLISHED"))
    .matchScoreGte(1.5)
    .count()
    .execute();
```

This is the **final pipeline result count**, including post-search `fields(...)` and score matching after `$search`.

---

#### 2) Atlas Search metadata count: `count().executeSearchMeta()`

```java
Mono<Long> count = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .countType(SearchCountType.TOTAL)
    .count()
    .executeSearchMeta();
```

`executeSearchMeta()` uses `$searchMeta`.

`SearchCountType`:

- `TOTAL`
- `LOWER_BOUND`

If `executeSearchMeta()` is called without an explicit `countType(...)`, the default is `TOTAL`.

Because `$searchMeta` reports Search metadata count, it does not execute the ordinary post-search `$match` produced by `fields(...)`.

Choose between these two count paths according to the semantics you need.

---

### Search exists terminal

```java
Mono<Boolean> exists = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .existsQuery()
    .execute();
```

This terminal checks whether the Search pipeline count is greater than zero.

---

### Driver-native Search extensions

Stage options can be supplemented directly.

```java
.search("articles_default")
.driverOptions(options -> options.option("someNewOption", value))
```

When a Search feature appears in the MongoDB Driver before a matching DSL convenience API, escape hatches such as `operator(...)`, `driverOptions(...)`, `SortSpec.driver(...)`, and `customizeAggregation(...)` can still be used.

---

## Vector Search

Vector Search builds a MongoDB `$vectorSearch` stage.

An index name is required.

```java
.vectorSearch("articles_vector_index")
```

Basic form:

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(20)
    .approximate(200)
    .findAll()
    .execute();
```

---

### Vector path

`path(...)` accepts:

- String
- Enum
- Driver-native `FieldSearchPath`
- custom wrappers

For a manually managed vector index, specify the embedding vector field. For a MongoDB Automated Embedding index, specify the indexed text field.

---

### Query vector

Overloads for passing vectors directly:

```java
.queryVector(float[] values)
.queryVector(double[] values)
.queryVector(Collection<Double> values)
.queryVector(BinaryVector values)
```

Example:

```java
float[] embedding = ...;

Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(20)
    .approximate(200)
    .findAll()
    .execute();
```

The DSL core does not call external embedding providers to generate vectors. Generate the embedding in the application and pass it to `queryVector(...)`.

---

### MongoDB Automated Embedding text query

With an Automated Embedding vector index, the query text itself can be supplied.

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_auto_embedding")
    .path("content")
    .query("reactive mongodb search")
    .limit(20)
    .approximate(200)
    .findAll()
    .execute();
```

Optional model override:

```java
.query("reactive mongodb search")
.model("model-name")
```

`model(...)` is an option used together with the text `query(String)` form.

---

### Driver-native vector query

A MongoDB Driver `VectorSearchQuery` can be passed directly.

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.query(driverVectorSearchQuery)
.limit(20)
.findAll()
.execute();
```

This is the escape hatch for new Driver query types that may appear before DSL convenience overloads.

---

### ANN / ENN

#### ANN

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.queryVector(embedding)
.limit(20)
.approximate(200)
.findAll()
.execute();
```

`approximate(n)` is a convenience alias for `numCandidates(n)`.

```java
.numCandidates(200)
```

ANN mode requires `numCandidates`.

---

#### ENN

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.queryVector(embedding)
.limit(20)
.exact()
.findAll()
.execute();
```

Or:

```java
.exact(true)
```

Setting `exact(true)` clears any existing `numCandidates` value.

---

### Vector pre-filter / post-filter

These two forms have different semantics and should be kept distinct.

#### Pre-filter: `$vectorSearch.filter`

```java
Flux<Article> results = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(20)
    .approximate(200)
    .filterFields(
        pair("status", "PUBLISHED"),
        pair("deleted", false)
    )
    .findAll()
    .execute();
```

Nested conditions:

```java
.filter(filter -> filter
    .and(f -> f.fields(
        pair("status", "PUBLISHED"),
        pair("deleted", false)
    ))
)
```

These conditions are placed inside `$vectorSearch.filter` and therefore affect candidate selection itself.

---

#### Post-filter: `vectorSearch().fields(...)`

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.queryVector(embedding)
.limit(20)
.approximate(200)
.fields(pair("category", "TECH"))
.findAll()
.execute();
```

This condition becomes an ordinary aggregation `$match` after `$vectorSearch`.

Conceptually:

```text
$vectorSearch
-> $match (fields)
-> addFields vector score
-> projection
```

Use a pre-filter when candidate selection itself must be restricted, and a post-filter when an ordinary Mongo condition should be applied after vector results are produced.

---

### Vector score

Vector Search score can be exposed as a result field.

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.queryVector(embedding)
.limit(20)
.approximate(200)
.addFieldsVectorSearchScore()
.findAll()
.execute();
```

Default alias:

```text
vectorSearchScore
```

Custom alias:

```java
.addFieldsVectorSearchScore("similarity")
```

---

### Vector projection

For example, exclude a large embedding field from the final result:

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.queryVector(embedding)
.limit(20)
.approximate(200)
.excludes("embedding")
.findAll()
.execute();
```

---

### Vector count

```java
Mono<Long> count = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(20)
    .approximate(200)
    .count()
    .execute();
```

Vector count appends `$count` after a `$vectorSearch` stage that already has a `limit`.

It therefore represents the **limited number of results returned by the current Vector Search pipeline**, not a count of the entire corpus.

Vector Search does not have an Atlas Search `$searchMeta`-style metadata count terminal.

---

### Vector exists terminal

```java
Mono<Boolean> exists = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(1)
    .exact()
    .existsQuery()
    .execute();
```

---

### Vector Search Driver options

```java
.vectorSearch("articles_vector_index")
.driverOptions(options -> ...)
```

Driver-native `VectorSearchOptions` can be adjusted at the end.

---

## `preview()` and `explain()` for Search / Vector

Atlas Search and Vector Search terminals expose the same diagnostic APIs.

```java
Mono<Document> preview = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .search("articles_default")
    .text(text -> text.path("title").query("mongodb"))
    .findAll()
    .preview();
```

```java
Mono<Document> explain = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("embedding")
    .queryVector(embedding)
    .limit(20)
    .approximate(200)
    .findAll()
    .explain(ExplainVerbosity.QUERY_PLANNER);
```

---
