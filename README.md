# Reactive Mongo DSL (reactive-mongo-dsl)

A fluent DSL built on the MongoDB **Reactive Streams Driver** and Project Reactor for **dynamic conditions / querying / paging / aggregation / `$lookup` / grouping / atomic updates / bulk operations / Atlas Search / Vector Search**.

The `reactive-mongo-dsl` core does not directly depend on Spring Data MongoDB. Mongo execution is abstracted through `MongoExecutionContext`, and the default `DriverMongoExecutionContext` implementation uses the MongoDB Reactive Streams Driver directly.

---

## Current version

The current source version in `build.gradle` is:

- `reactive-mongo-dsl`: `1.1.0-alpha.1`
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
- Change Stream availability in the target MongoDB environment when using cursor invalidation, query reservations, or embedded synchronization
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
    implementation 'com.byeolnaerim:reactive-mongo-dsl:1.0.0'
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

### Page-number cursor-anchor paging

Cursor features are selected under `paging()` instead of being exposed as unrelated terminals on `findAll()`. Choose `pageNumberCursor(...)` when the UI must keep page-number navigation while store-backed anchors reduce repeated deep skips.

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort
        .desc("createdAt")
        .desc("id")
    )
    .paging()
    .pageNumberCursor(20, 50)
    .execute();
```

`pageNumber` remains zero-based. Existing ordinary page-number paging is unchanged:

```java
.findAll()
.paging()
    .pageNumber(20)
    .pageSize(50)
    .and()
.execute();
```

The cursor strategy also supports the explicit builder form:

```java
.findAll()
.sorts(sort -> sort.desc("createdAt"))
.paging()
.pageNumberCursor()
    .pageNumber(20)
    .pageSize(50)
    .execute();
```

After a strategy is selected, autocomplete exposes only operations meaningful for that strategy. `skipPolicy()` exists only on `pageNumberCursor()`; it is not available on the page-number-free `cursor()` builder.

Internally the DSL finds the nearest earlier anchor for the query signature and skips only the remaining relative distance.

```text
pageNumber/pageSize + filter + sort + namespace version
    -> build cursor query key
    -> load nearest anchor at or before the target page
    -> anchor condition + relative skip
    -> fetch pageSize + 1 rows
    -> store current-page and next-page anchors
```

When no nearby anchor exists, `skipPolicy()` controls deep-page behavior. Defaults are `maxRelativeSkip=5,000` and `CursorSkipExceededAction.FAIL`.

```java
Flux<User> users = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort.desc("createdAt"))
    .paging()
    .pageNumberCursor(99999, 20)
    .skipPolicy()
        .maxRelativeSkip(10_000)
        .onExceeded(CursorSkipExceededAction.RETURN_EMPTY)
        .end()
    .execute();
```

`onExceeded(...)` supports:

- `FAIL` - reject before the business collection query with `CursorSkipLimitExceededException`; this is the default.
- `RETURN_EMPTY` - return an empty result without executing the business collection query.
- `EXECUTE_ANYWAY` - explicitly accept the calculated relative skip even above the configured limit.

The limit applies to the **actual row skip from the nearest anchor**, not to the numeric page number itself.

Sorting rules:

- if sort is omitted, `_id: -1` is used,
- if `_id` is missing from a user sort, `_id: -1` is appended as a stable tie-breaker,
- cursor sort values must use ordinary numeric ascending/descending (`1` / `-1`) semantics,
- meta/opaque sorts are not supported by cursor paging,
- `customizeQuery(...)` is not compatible because it makes filter/sort semantics opaque to the cursor engine.

Use `executePageStream()` on the selected page-number cursor builder when a `PageStream<T>` is needed.

```java
PageStream<User> page = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort.desc("createdAt"))
    .paging()
    .pageNumberCursor(20, 50)
    .executePageStream();
```

#### Anchor admission / TTL defaults

The default in-memory state store does not retain anchors for every one-off query. It uses adaptive admission based on access behavior. `CursorCacheOptions.defaults()` uses:

| Option | Default | Meaning |
| --- | ---: | --- |
| `admissionWindow` | 10 seconds | Window for hot-query detection |
| `admissionThreshold` | 4 hits | Admit after this many hits in the window |
| `idleTtl` | 1 minute | Idle expiration for query/anchor state |
| `maxQueries` | 10,000 | In-memory query-state cap |
| `maxAnchorsPerQuery` | 256 | In-memory per-query anchor cap |
| `deepPageSkipThreshold` | 5,000 | Immediately admit when estimated skip reaches this value |
| `expirationTick` | 1 second | In-memory expiration-wheel tick |
| `expirationWheelSize` | 512 | Number of expiration-wheel slots |
| `maxRelativeSkip` | 5,000 | Maximum relative row skip allowed from the nearest anchor in page-number cursor paging |
| `skipExceededAction` | `FAIL` | Action on limit exceed: `FAIL`, `RETURN_EMPTY`, or `EXECUTE_ANYWAY` |
| `maxPageSize` | 500 | Maximum page size accepted by cursor APIs |
| `tokenTtl` | 10 minutes | TTL for store-backed opaque cursor tokens |

The MongoDB-backed state store uses the same admission options and idle TTL, but the current implementation does not immediately prune persisted anchors per query to `maxAnchorsPerQuery` on the server. Persisted MongoDB anchors are aged out by TTL.

To prevent stale anchors from being reused after external writes, the cursor query key includes collection namespace versions. When a Change Stream observes a collection change, the namespace version advances and subsequent requests use a new query key. State-store and Change Stream configuration are covered in the **Unified state store** and **Shared Change Stream** sections below.

To change only the safety limits while retaining the default admission/TTL behavior:

```java
CursorCacheOptions cursorOptions = CursorCacheOptions
    .defaults()
    .withSafety(
        50_000L,                         // maxRelativeSkip
        CursorSkipExceededAction.FAIL,  // action on limit exceed
        200,                             // maxPageSize
        Duration.ofMinutes(5)            // tokenTtl
    );
```

To change only the global page-number cursor skip policy while keeping admission/TTL settings intact:

```java
CursorCacheOptions cursorOptions = CursorCacheOptions
    .defaults()
    .withCursorSkipPolicy(
        20_000L,
        CursorSkipExceededAction.RETURN_EMPTY
    );
```

A per-query `paging().pageNumberCursor(...).skipPolicy()` overrides these store/global defaults. If it is omitted, the `CursorCacheOptions` values are used.

### Page-number-free store-backed opaque cursor

For infinite-scroll or load-more flows, select the `cursor()` strategy under the same `paging()` entry point. This typed builder does not expose page-number or skip-policy methods.

First page:

```java
CursorPage<User> first = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort.desc("createdAt"))
    .paging()
    .cursor(50)
    .execute()
    .block();
```

Continue by passing the previous opaque token to `after(...)`:

```java
CursorPage<User> second = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort.desc("createdAt"))
    .paging()
    .cursor(50)
    .after(first.nextCursor())
    .execute()
    .block();
```

The explicit builder form is also available:

```java
.paging()
.cursor()
    .pageSize(50)
    .after(token)
    .execute();
```

`CursorPage<T>` exposes `data()`, `nextCursor()`, and `hasNext()`. This path never computes a page number and never uses MongoDB `skip`. The next-page sort tuple is stored in the state store and only an opaque id is exposed to the client. Client values that are not the exact 64-character lowercase hexadecimal token format issued by the library are rejected before any state-store lookup, preventing oversized arbitrary cursor strings from becoming store traffic.

Stored token state is bound to the physical database/collection namespace, query/filter/sort semantics, page size, and sort tuple. The DSL therefore rejects:

- arbitrary or expired tokens
- tokens issued for another database/collection or a different filter/sort query
- reuse with a different page size

Unlike page-number anchors, pure keyset tokens are not invalidated on every collection write. The token already represents a concrete sort position, so after data changes it continues from that position against the **current data**. This is standard keyset-pagination behavior, not snapshot isolation: rows inserted or removed in an already-passed region between requests are not replayed automatically.

The token id is deterministic for the same query and next position and is upserted in the store, so repeatedly reading the same page does not create a new token document on every request. Token documents expire after `tokenTtl`.

Even a token issued far into a result set executes as `keyset predicate + limit(pageSize + 1)` and does not incur a skip proportional to its logical depth. Replaying the same token thousands of times is still a **request-rate attack**, however, and a library without account/IP/API-key identity cannot fully solve that layer; applications should still apply HTTP/API rate limiting. Cursor sort fields should also be backed by an appropriate MongoDB index so the keyset predicate and sort remain efficient.

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

### Driver-native root aggregation

Use `aggregation()` when MongoDB Driver aggregation stages must be composed directly from the first pipeline stage. The DSL does not re-implement the Driver aggregation API; it executes Driver-provided `Bson` stages as-is. `Document` also implements `Bson` and can be passed directly.

```java
Flux<User> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .aggregation()
    .stage(Aggregates.match(Filters.eq("status", "ACTIVE")))
    .stage(Aggregates.sort(Sorts.descending("createdAt")))
    .stage(Aggregates.limit(20))
    .execute();
```

Stages preserve insertion order. Use `execute(ResultType.class)` when the result shape differs from the source entity, or `executeDocument()` for raw `Document` results.

MongoDB Driver 5.10.0 `$score` / `$scoreFusion` stages are passed through directly without DSL-specific wrappers. For features such as `$scoreFusion`, where first-stage placement matters, use `aggregation()` to control pipeline order explicitly.

```java
Bson searchStage = Aggregates.search(
    SearchOperator.text(SearchPath.fieldPath("title"), "mongodb"),
    SearchOptions.searchOptions().index("search-index")
);

Bson vectorStage = Aggregates.vectorSearch(
    SearchPath.fieldPath("embedding"),
    List.of(0.1D, 0.2D, 0.3D),
    "vector-index",
    10L,
    VectorSearchOptions.exactVectorSearchOptions()
);

Flux<Document> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .aggregation()
    .stage(
        Aggregates.scoreFusion(
            List.of(
                FusionPipeline.of("text", searchStage),
                FusionPipeline.of("vector", vectorStage)
            ),
            ScoreNormalization.SIGMOID
        )
    )
    .executeDocument();
```

Driver API availability and server-stage support are separate concerns. `$score` / `$scoreFusion` require MongoDB 8.2+, while the nested/array Vector Search options added in Driver 5.10 require MongoDB 8.3+.

`readPreference(...)`, `isAllowDiskUse(...)`, `customizeAggregation(...)`, `preview()`, and `explain()` are available on `aggregation()` as well.

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

## Embedded snapshot synchronization

When a MongoDB document stores a denormalized snapshot of another entity, changes to the canonical source may need to be propagated to that embedded copy. `EmbeddedSyncConfig<K>` defines those relations when the DSL singleton is created and uses the shared Change Stream to apply source changes to target documents.

This is intentionally a separate configuration object rather than a stateful `syncEmbedded(...)` query-builder method. Build the relation configuration and inject it into the `ReactiveMongoDsl` constructor.

### Basic relation definition

```java
EmbeddedSyncConfig<MongoTemplateName> embeddedSync =
    new EmbeddedSyncConfig<MongoTemplateName>()
        .forKeys(MongoTemplateName.FRONT)
        .from(Order.class)
        .into(User.class, "orders")
        .linkBy()
            .fromField("userId")
            .intoField("id")
            .end()
        .build();

ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, embeddedSync);
```

The direction means:

```text
from(Order.class)       = canonical source
into(User.class, ...)   = target that stores the source snapshot
```

In this example `Order.userId` is linked to `User._id`, and the current Order BSON snapshot is synchronized into the User `orders` field. `intoField("id")` follows the normal DSL path rule and is normalized to `_id`. When a source link value targeting an id alias is a valid 24-character hexadecimal String, it can be converted for an ObjectId comparison.

A relation can use multiple link pairs:

```java
.linkBy()
    .fromField("tenantId")
    .intoField("tenantId")
    .fromField("userId")
    .intoField("id")
    .end()
```

`linkBy()` can be omitted, but the semantics are narrower. Without an explicit link, the engine can update/delete target fields that already contain the source `_id`, but a new source insert does not provide enough information to discover a brand-new target association. Use `linkBy()` when inserts must create new associations automatically.

### Target field and cardinality

The target field can be explicit:

```java
.from(Profile.class)
.into(Account.class, "profile")
.build();
```

Or it can be omitted when exactly one compatible target field can be inferred:

```java
.from(Profile.class)
.into(Account.class)
.build();
```

If no compatible field exists, or more than one field is compatible, the configuration fails instead of selecting an ambiguous field automatically.

Cardinality is inferred from target-field Java metadata:

| Target field shape | Cardinality | Synchronization style |
| --- | --- | --- |
| `SourceType field` | SINGLE | `$set` / `$unset` |
| `Collection<SourceType> field` | COLLECTION | array upsert/update / `$pull` |
| `Map<String, SourceType> field`, etc. | MAP | map-entry upsert/remove pipeline |

MAP relations must specify which source field supplies the map key:

```java
.from(Address.class)
.into(User.class, "addresses")
.mapKey("type")
.linkBy()
    .fromField("userId")
    .intoField("id")
    .end()
.build();
```

The runtime map key must be usable as a MongoDB field key; blank values, values containing `.`, and values beginning with `$` are rejected.

### INSERT / UPDATE / REPLACE / DELETE handling

Embedded sync handles these source Change Stream operations:

- `INSERT`
- `UPDATE`
- `REPLACE`
- `DELETE`

For UPDATE/REPLACE, the current source document is re-read by `_id` so the target receives the latest snapshot. Short bursts for the same relation/source id are coalesced internally.

Target changes use MongoDB atomic updates/pipelines rather than loading the full target entity and calling `save()`. Unrelated target fields are therefore not overwritten through a read-modify-write cycle.

When link values move a source from target A to target B, the current target is updated and stale references left in the old target are cleaned up.

The default source-delete policy is `EmbeddedDeletePolicy.REMOVE`:

```java
.onDelete(EmbeddedDeletePolicy.REMOVE)
```

To keep the embedded snapshot after a source delete:

```java
.onDelete(EmbeddedDeletePolicy.IGNORE)
```

### Multi-hop propagation and graph validation

For relations such as:

```text
C -> B.children
B -> A.child
```

a C change can update B, and that B update appears on the Change Stream and can propagate to the downstream A relation.

The configuration validates a directed graph per resolver key:

- DAGs such as `A -> B -> C` are allowed.
- Real directed cycles (`A -> B -> A`, `A -> B -> C -> A`) are rejected.
- Unrelated edges are not rejected merely because they point in opposite directions.
- One target class + target field path cannot have multiple different source owners.
- A relation registered for a resolver key operates inside the Mongo execution context resolved by that key; it is not a cross-database replication feature.

### Existing data and startup behavior

Registering a relation does not automatically run a full collection scan/reconciliation at startup. Synchronization is driven by changes observed after registration.

If existing embedded snapshots are already stale, or data created before a new relation must be aligned, run an explicit application backfill/reconciliation job. The library does not hide a large startup scan behind relation registration.

### Multiple instances and leases

Embedded sync uses `EmbeddedSyncLeaseStore` so multiple application instances do not intentionally process the same relation batch concurrently. Unless `EmbeddedSyncConfig` overrides it, the DSL's unified state store is also used for leases.

```java
EmbeddedSyncConfig<MongoTemplateName> embeddedSync =
    new EmbeddedSyncConfig<>(customLeaseStore);
```

The default in-memory state store is process-local and is sufficient for a single process. In load-balanced/multi-instance deployments, use a shared MongoDB-backed state store or another distributed implementation when leases must coordinate across nodes. In that case every node must return the same stable `MongoExecutionContext#getDistributedStateScopeKey()` for the same logical Mongo scope.

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

## Unified state store: cursor / Change Stream / embedded lease

Cursor anchors/namespace versions, Change Stream resume checkpoints, and embedded-sync leases serve different features but are all long-lived DSL state. The default constructors use one `InMemoryReactiveMongoDslStateStore` for all three areas.

```java
ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver);
```

This default is **process-local**. It requires no additional infrastructure for a single process, but it does not share cursor state, checkpoints, or leases across application instances behind a load balancer.

### Inject one unified state store

Provide one `ReactiveMongoDslStateStore` when the features should share an external backend:

```java
ReactiveMongoDslStateStore stateStore = ...;

ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, stateStore);
```

With embedded synchronization:

```java
ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, embeddedSync, stateStore);
```

By default the same store handles:

- cursor anchor reads/writes
- opaque cursor token storage/resolution/TTL
- collection namespace versions and invalidation
- Change Stream resume checkpoints
- embedded-sync distributed leases

Advanced applications that intentionally use different backends can compose the individual SPIs with `ReactiveMongoDslStateStore.of(...)`:

```java
ReactiveMongoDslStateStore stateStore = ReactiveMongoDslStateStore.of(
    cursorAnchorStore,
    changeStreamCheckpointStore,
    embeddedSyncLeaseStore
);
```

The API is therefore not restricted to in-memory or MongoDB. Implement `ReactiveMongoDslStateStore`, or the individual SPIs and compose them, to connect Redis or another backend. The built-in unified implementations currently provided by the core are in-memory and MongoDB. A custom `CursorAnchorStore` only needs the existing `floor/put` contract for page-number anchors, but it must also implement `putToken(...)` / `resolveToken(...)` to support the `paging().cursor(...)` opaque-token strategy.

### MongoDB-backed unified state store

Use `MongoReactiveMongoDslStateStore` when state should live in MongoDB:

```java
MongoExecutionContext context = resolver.getTemplate(MongoTemplateName.FRONT);

ReactiveMongoDslStateStore stateStore =
    new MongoReactiveMongoDslStateStore(context);

ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, stateStore);
```

The default state collection is:

```text
__reactive_mongo_dsl_state
```

One collection stores cursor anchors, opaque cursor tokens, namespace versions, Change Stream checkpoints, and embedded-sync leases as separate document kinds.

By default it ensures:

- an `expiresAt` TTL index (`expireAfter=0`)
- a `(kind, queryKey, pageNumber desc)` compound index for cursor floor lookups

Options can be supplied explicitly:

```java
MongoReactiveMongoDslStateStoreOptions options =
    new MongoReactiveMongoDslStateStoreOptions(
        "__reactive_mongo_dsl_state",
        CursorCacheOptions.defaults(),
        true,
        "front-consumer-a"
    );

ReactiveMongoDslStateStore stateStore =
    new MongoReactiveMongoDslStateStore(context, options);
```

`changeStreamConsumerId` isolates resume tokens by logical consumer. When it is `null`, each store instance uses a process-unique UUID so concurrently running nodes do not overwrite the same checkpoint id. If one logical consumer must resume from the same token after a process restart, supply an id that is **stable across that consumer's restarts but distinct from other concurrently active consumers**.

Checkpoint documents currently use a 7-day TTL. Cursor anchors use `CursorCacheOptions.idleTtl()`. Namespace invalidation stores the Change Stream `clusterTime` so duplicate or older replayed events do not advance the namespace version again.

When the state store is **actually in the same watched Mongo scope**, the internal state collection is excluded from the database Change Stream pipeline to prevent a feedback loop such as:

```text
state write
 -> Change Stream event
 -> state invalidation/checkpoint write
 -> another Change Stream event
 -> ...
```

When state is stored in the same database, the `MongoReactiveMongoDslStateStore(MongoExecutionContext, ...)` constructor provides the clearest same-scope detection because the store can compare the session scope as well as the database.

### `distributedStateScopeKey`

A distributed state store needs a stable namespace shared across processes rather than a process identity. `MongoExecutionContext#getDistributedStateScopeKey()` supplies that namespace.

All nodes serving the same logical Mongo scope should return the same value, while different clusters/tenants/logical databases should use different values so their state cannot collide accidentally.

For a custom `MongoExecutionContext`:

```java
@Override
public String getDistributedStateScopeKey() {
    return "auction-front-prod";
}
```

`DriverMongoExecutionContext` also provides a constructor that accepts an explicit `distributedStateScopeKey`.

When a distributed cursor/checkpoint/lease store requires this key and it is absent, the DSL fails initialization/use of that feature rather than guessing a process-local namespace silently.

---

## Shared Change Stream

`ReactiveMongoDsl` shares a `ChangeStreamHub` so cursor invalidation, embedded sync, query reservations, and direct watchers do not each open independent MongoDB Change Streams for the same database scope.

Public facade:

```java
Flux<ChangeStreamDocument<Document>> databaseChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT);

Flux<ChangeStreamDocument<Document>> userChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT, User.class);

Flux<ChangeStreamDocument<Document>> rawCollectionChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT, "user");
```

Collection watchers that share the same session scope + database receive filtered views of one database-wide physical stream.

### Checkpoints and the first-subscription boundary

When a shared stream is prepared for the first time, the DSL captures a MongoDB operation time as its safe starting boundary.

- If a saved checkpoint exists, it uses `resumeAfter(resumeToken)`.
- Otherwise it uses `startAtOperationTime(initialOperationTime)`.

This avoids losing a write that occurs after logical subscription setup but before the server-side Change Stream cursor has physically opened.

Internal processing is micro-batched. The current implementation uses up to 256 events or a 10ms window. Internal work that can be coalesced by collection, such as cursor namespace invalidation, runs as a batch observer, and the checkpoint is saved once using the last resumable token in the batch rather than once per event.

This batching does **not** collapse the public Change Stream event sequence. After internal side effects complete, `changeStreams().watch(...)` subscribers still receive the original events individually and in order.

Internal observers execute before the batch checkpoint advances. If an observer fails, the checkpoint is not moved past that work first, allowing the event to be replayed after reconnection instead of being hidden behind a newer resume token.

### `reservationChangeStream()`: re-run a finite query on invalidation

Use a query reservation when you want a fresh snapshot whenever a dependency collection changes:

```java
Flux<List<User>> snapshots = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .sorts(sort -> sort.desc("createdAt"))
    .reservationChangeStream()
    .coalesce(Duration.ofMillis(100))
    .execute();
```

The stream:

1. emits one initial finite-query snapshot,
2. waits for Change Stream events from dependency collections,
3. re-executes the same finite query and emits a new snapshot after an invalidation.

The default coalescing window is 50ms and can be disabled with `Duration.ZERO`.

Additional dependencies can be declared explicitly:

```java
.reservationChangeStream()
.watch(Profile.class)
.watch(MongoTemplateName.BACK, Audit.class)
.watch(MongoTemplateName.FRONT, "external_status")
.execute();
```

Available terminals:

- `.changes()` / `.invalidations()` - dependency Change Stream events themselves
- `.execute()` - re-run the ordinary finite query
- `.executeLookup(right, spec)` - re-run a lookup finite query

For page-number cursor snapshot refreshes, select the strategy first and then use `.reservationChangeStream().execute()` or `.executeLookup(right, spec)`.

```java
.paging()
.pageNumberCursor(20, 50)
.reservationChangeStream()
.execute();
```

Lookup reservations automatically include the right collection and nested `$lookup` dependencies found in the `LookupSpec`.

A reservation does not translate the query filter into a document-level MongoDB Change Stream `$match`. It intentionally uses an **invalidation -> pull** model: any observed change in a dependency collection can cause the finite query to be run again. For high-change collections or expensive queries, choose `coalesce(...)`, dependency scope, and query cost accordingly.

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

### Lookup cursor paging

Lookup cursor strategy is also selected from the left builder's `paging()` entry point.

Page-number cursor lookup:

```java
Flux<ResultTuple<User, List<Order>>> joined = left
    .paging()
    .pageNumberCursor(20, 50)
    .executeLookup(right, spec);
```

When total count is needed:

```java
Mono<PageResult<ResultTuple<User, List<Order>>>> page = left
    .paging()
    .pageNumberCursor(20, 50)
    .executeLookupAndCount(right, spec);
```

Anchors use the left builder's `sorts(...)` and the selected page number/page size. The same tie-breaker, admission, namespace invalidation, and `skipPolicy()` rules apply. Lookup query identity also includes the right collection/criteria, `LookupSpec` pipeline semantics, and nested `$lookup` dependency namespaces.

For page-number-free lookup pagination, select `cursor()` and keep the same terminal name `executeLookup(...)`:

```java
CursorPage<ResultTuple<User, List<Order>>> first = left
    .paging()
    .cursor(50)
    .executeLookup(right, spec)
    .block();

CursorPage<ResultTuple<User, List<Order>>> second = left
    .paging()
    .cursor(50)
    .after(first.nextCursor())
    .executeLookup(right, spec)
    .block();
```

This path stores the left sort tuple behind an opaque state-store token and uses no offset skip. The token fingerprint includes right-side criteria, lookup semantics, and left/right physical namespace identity, but it is not bound to Change Stream namespace versions like page-number anchors are.

`customizeAggregation(...)` is not compatible with lookup cursor paging because it makes the final pipeline semantics opaque.

Internal lookup projections use dedicated private aliases rather than left/right class simple names, so same-class and `Document`/`Document` lookups do not collide.

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

### Cursor paging / state store / Change Stream

- When page-number cursor paging exceeds `maxRelativeSkip`, it follows `skipExceededAction`. The default `FAIL` blocks before the business-collection query; the page-number cursor builder's `skipPolicy()` or global `CursorCacheOptions` can choose `RETURN_EMPTY` or `EXECUTE_ANYWAY`. Page-number-free opaque cursors do not use `skip` at all.
- Cursor sorts must use deterministic numeric ascending/descending fields. `_id: -1` is appended as a tie-breaker when `_id` is absent.
- The default state store is process-local. Use a distributed store plus a stable `distributedStateScopeKey` when cursor/checkpoint/embedded lease state must be shared across application instances.
- Do not share one `changeStreamConsumerId` across concurrently active Mongo-backed consumers. Use a stable/unique id only for a logical consumer that needs checkpoint continuity across restarts.
- `reservationChangeStream()` does not translate the query filter into a Change Stream `$match`; it treats dependency writes as invalidations and re-runs the finite query.
- Shared Change Stream internal state side effects can be batched, while public watch events are still delivered as the original individual events.

### Embedded snapshot sync

- `from` is the canonical source and `into` is the snapshot target; reversing them reverses the meaning of the relation.
- Use `linkBy()` when a new insert must discover which target should receive a new association.
- Directed relation cycles and multiple source owners for the same target field are rejected during registration.
- Registration is not a startup full reconciliation. Existing data backfill must be performed by an explicit application job when required.
- Multi-instance coordination depends on the lease store; the default in-memory lease is process-local.

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
- ordinary page-number paging: existing `findAll().paging(pageNumber, pageSize)` or `paging().pageNumber(...).pageSize(...).and()`
- page-number cursor paging: `findAll().paging().pageNumberCursor(pageNumber, pageSize).execute()`
- page-number-free opaque cursor: `findAll().paging().cursor(pageSize).after(token).execute()`
- page-number lookup cursor: `left.paging().pageNumberCursor(...).executeLookup(...)` / `executeLookupAndCount(...)`
- opaque lookup cursor: `left.paging().cursor(...).after(token).executeLookup(...)`
- refresh a finite-query snapshot on dependency changes: `findAll().reservationChangeStream()`
- direct Change Stream subscription: `changeStreams().watch(...)`
- embedded snapshot synchronization: `EmbeddedSyncConfig`
- shared cursor/checkpoint/lease backend: `ReactiveMongoDslStateStore`
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

Use `stage(Bson)` / `stages(...)` when a Driver-native aggregation stage is needed after `$search`. Added stages run immediately after `$search` and before post-search `fields(...)`, metadata additions, score filters, paging, and projection.

Added stages apply to normal `$search` result/count pipelines. `executeSearchMeta()` uses the dedicated `$searchMeta` metadata-count path and does not apply `stage(...)`.

```java
Flux<User> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .search("search-index")
    .text(text -> text.path("title").query("mongodb"))
    .stage(Aggregates.score("$rating"))
    .findAll()
    .execute();
```

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

### Vector Search post stage

Use `stage(Bson)` / `stages(...)` when a Driver-native aggregation stage is needed after `$vectorSearch`. Added stages run immediately after `$vectorSearch` and before post-filters, metadata additions, and projection.

```java
Flux<User> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .vectorSearch("vector-index")
    .path("embedding")
    .queryVector(vector)
    .limit(20)
    .exact()
    .stage(Aggregates.score("$rating"))
    .findAll()
    .execute();
```

### Nested / array embedding options (Driver 5.10+)

The core nested Vector Search options added in MongoDB Driver 5.10.0 are connected directly to the existing Vector DSL.

- `filter(...)` / `filterFields(...)`: the `filter` applied to nested embedding leaf documents
- `parentFilter(...)` / `parentFilterFields(...)`: the `parentFilter` applied to root documents
- `nestedScoreMode(...)`: how scores from multiple matching embeddings inside one document are combined (`AVG` / `MAX`)

```java
Flux<Article> result = dsl
    .executeEntity(Article.class, MongoTemplateName.FRONT)
    .vectorSearch("articles_vector_index")
    .path("chunks.embedding")
    .queryVector(embedding)
    .limit(20)
    .exact()
    .filter(f -> f.fields(
        pair("chunks.kind", "BODY")
    ))
    .parentFilter(f -> f.fields(
        pair("tenantId", tenantId)
    ))
    .nestedScoreMode(VectorSearchScoreMode.AVG)
    .findAll()
    .execute();
```

These convenience methods do not re-implement the MongoDB Driver. They only connect the existing `FieldsPair` / `FieldBuilder` criteria DSL to the Driver 5.10 `VectorSearchOptions`. Nested Vector Search requires MongoDB 8.3+.

### Vector Search Driver options

```java
.vectorSearch("articles_vector_index")
.driverOptions(options -> ...)
```

`driverOptions(...)` remains the escape hatch for current or future Driver options that the convenience DSL does not expose yet. The same nested options can still be used directly through the Driver API.

```java
.vectorSearch("articles_vector_index")
.driverOptions(options -> options
    .parentFilter(Filters.eq("tenantId", tenantId))
    .nestedOptions(
        VectorSearchNestedOptions
            .vectorSearchNestedOptions()
            .scoreMode(VectorSearchScoreMode.AVG)
    )
)
```

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
