# Reactive Mongo DSL (reactive-mongo-dsl)

MongoDB **Reactive Streams Driver**와 Project Reactor 기반으로, **동적 조건 / 조회 / 페이징 / Aggregation / `$lookup` / 그룹핑 / 원자적 업데이트 / Bulk 작업 / Atlas Search / Vector Search**를 하나의 체이닝 DSL로 묶은 라이브러리입니다.

`reactive-mongo-dsl` 코어는 Spring Data MongoDB에 직접 의존하지 않습니다. Mongo 실행 환경은 `MongoExecutionContext`로 추상화되어 있으며, 기본 구현인 `DriverMongoExecutionContext`는 MongoDB Reactive Streams Driver를 직접 사용합니다.

---

## 현재 버전 기준

현재 소스의 `build.gradle` 기준 버전은 다음과 같습니다.

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

## 요구사항

- Java 21+
- MongoDB Reactive Streams Driver 5.9.x 호환 환경 (5.10.0 사용 가능)
- Project Reactor
- Atlas Search 기능 사용 시 MongoDB Atlas Search index
- Vector Search 기능 사용 시 MongoDB Vector Search index
- Change Stream 기반 cursor invalidation / reservation / embedded sync 사용 시 해당 MongoDB 환경에서 Change Stream 사용 가능
- Spring Data MongoDB 연동 시 애플리케이션 측 Reactive MongoDB 구성 (`ReactiveMongoTemplate`, `MongoClient`)

Spring Data MongoDB는 코어 필수 의존성이 아닙니다. Spring 애플리케이션에서도 사용할 수 있지만, DSL 자체의 실행 계약은 `MongoExecutionContext`와 MongoDB Reactive Streams Driver를 기준으로 합니다.

---

## 주요 컨셉

### 1) 실행 환경 라우팅: `MongoTemplateResolver<K>` + `MongoExecutionContext`

`ReactiveMongoDsl<K>`는 `MongoTemplateResolver<K>`를 통해 key별 `MongoExecutionContext`를 가져옵니다.

```java
public interface MongoTemplateResolver<K> {
    MongoExecutionContext getTemplate(K key);
}
```

`MongoExecutionContext`가 담당하는 것은 다음과 같습니다.

- `MongoDatabase` 제공
- `ClientSession` 시작
- 엔티티별 collection 이름 결정
- 엔티티 ↔ BSON `Document` 변환
- 엔티티 id 조회 / 생성 id 반영
- save 계열의 `beforePersist` / `afterPersist` lifecycle hook
- 같은 Mongo client/session 범위를 식별하기 위한 `getSessionScope()`

기본 구현으로 `DriverMongoExecutionContext`가 제공됩니다.

따라서 멀티 DB / 멀티 클러스터 / 멀티 테넌트 환경에서도 key만 바꿔 동일한 DSL을 사용할 수 있습니다.

---

### 2) 일반 쿼리 흐름

기본 조회/수정 흐름은 다음과 같습니다.

```text
executeEntity(...) / executeCustomClass(...)
    -> fields(...) / driverFilter(...)
    -> end()
    -> findAll / find / count / distinct / delete / exists / atomicUpdate
    -> execute...
```

예:

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

`save`, `saveAll`, Bulk 작업, `createHistory`처럼 조건 builder가 필요 없는 작업은 `executeEntity(...)` 또는 `executeCustomClass(...)`에서 바로 실행할 수 있습니다.

현재 버전에는 별도의 public `update()` terminal이 없습니다. 조건 기반 update는 `atomicUpdate()`에서 `first()`, `multi()`, `upsertOne()` 중 범위를 선택해 실행합니다.

---

### 3) Atlas Search / Vector Search는 별도 진입점

Atlas Search와 Vector Search는 첫 aggregation stage 제약이 있으므로 일반 `fields(...).end()` 흐름과 분리되어 있습니다.

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

일반 Mongo 조건과 Search/Vector의 조건 위치는 의미가 다르므로 뒤의 전용 섹션에서 별도로 설명합니다.

---

## 빠른 시작

### 1) `DriverMongoExecutionContext` 구성

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

`DriverMongoExecutionContext` 기본 collection 이름은 엔티티 simple class name을 decapitalize해서 결정합니다.

예:

```text
User -> user
AuctionHistory -> auctionHistory
```

프로젝트의 실제 collection naming 규칙이 다르면 resolver를 넘길 수 있습니다.

```java
MongoExecutionContext context = new DriverMongoExecutionContext(
    mongoClient,
    mongoDatabase,
    entityClass -> collectionNameResolver(entityClass)
);
```

collection 이름 결정이 애플리케이션 수명 동안 완전히 고정이라면 다음 캐시 helper를 사용할 수도 있습니다.

```java
new DriverMongoExecutionContext(
    mongoClient,
    mongoDatabase,
    DriverMongoExecutionContext.cachedCollectionNameResolver(
        entityClass -> collectionNameResolver(entityClass)
    )
);
```

테넌트/요청/시간 등에 따라 collection 이름이 달라지는 resolver에는 이 캐시 helper를 사용하면 안 됩니다.

---

### 2) Spring Data MongoDB 프로젝트에서 사용하기

`reactive-mongo-dsl` 코어는 Spring Data MongoDB에 직접 의존하지 않으므로 `ReactiveMongoTemplate` 자체를 `MongoTemplateResolver`에 반환하지 않습니다.

Spring 애플리케이션에서 기존 `ReactiveMongoTemplate`의 **collection naming / `MongoConverter` / custom conversion / reactive auditing**을 그대로 활용하려면 `MongoExecutionContext` adapter를 하나 두고, resolver가 그 adapter를 반환하도록 구성합니다.

Spring Boot를 사용하는 경우 애플리케이션 쪽에 기존처럼 Reactive MongoDB starter를 두면 됩니다.

```gradle
dependencies {
    implementation 'com.byeolnaerim:reactive-mongo-dsl:1.0.0'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
}
```

DSL 코어에 Spring 의존성을 추가하는 방식이 아니라 **Spring 애플리케이션이 adapter를 제공하는 구조**입니다.

#### `SpringReactiveMongoExecutionContext` adapter

다음처럼 `ReactiveMongoTemplate`과 같은 `MongoClient`를 `MongoExecutionContext`에 연결할 수 있습니다.

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

이 adapter를 사용하면 엔티티 저장/조회 변환은 Spring Data의 `MongoConverter`를 사용하므로 `@Document`, `@Id`, `@Field`, `MongoCustomConversions` 등의 Spring mapping 설정을 엔티티 변환에 그대로 활용할 수 있습니다.

또한 `beforePersist(...)`를 `ReactiveBeforeConvertCallback`에 연결하므로 Spring Data의 reactive auditing이 활성화된 애플리케이션에서는 `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` 같은 auditing callback도 `save()` 계열에서 적용됩니다.

다만 DSL이 Spring `ReactiveMongoTemplate`의 CRUD 메서드 자체를 호출하는 것은 아닙니다. 실제 Mongo 연산은 Reactive Streams Driver로 실행하고, adapter를 통해 Spring의 **mapping / collection naming / 일부 entity lifecycle**을 연결하는 구조입니다.

#### resolver 구성

하나의 MongoDB만 사용하는 경우에도 key 하나를 두고 resolver를 만들 수 있고, 여러 `ReactiveMongoTemplate`을 사용하는 애플리케이션은 template/client 쌍마다 `MongoExecutionContext`를 하나씩 만든 뒤 key로 라우팅하면 됩니다.

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

`ReactiveMongoTemplate`과 `MongoClient`는 반드시 **서로 대응하는 같은 Mongo 설정의 bean**을 넘기는 것이 좋습니다. DSL의 collection/database 접근은 template을 기준으로 하지만 `ClientSession`은 전달받은 `MongoClient`에서 시작하기 때문입니다.

같은 `MongoClient`로 여러 database/context를 구성한 경우 `getSessionScope()`가 같은 client를 반환하므로 DSL transaction에서 같은 session을 공유할 수 있습니다. 서로 다른 `MongoClient`를 사용하는 context는 하나의 DSL transaction session으로 묶을 수 없습니다.

#### `ReactiveMongoDsl` Bean 등록

resolver를 등록한 뒤 `ReactiveMongoDsl` 자체를 Spring Bean으로 등록하면 일반 서비스에서 주입해서 사용할 수 있습니다.

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

이후에는 일반 Spring Bean에서 그대로 주입해서 사용합니다.

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

#### Spring adapter에서 적용되는 범위

| 항목 | 적용 여부 | 설명 |
| --- | --- | --- |
| `@Document(collection = ...)` | O | `ReactiveMongoTemplate#getCollectionName(...)` 사용 |
| `@Id` / id 변환 | O | `MongoConverter`와 Spring mapping metadata 사용 |
| `@Field` / custom conversion | O | entity ↔ `Document` 변환에 `MongoConverter` 사용 |
| Reactive auditing | O (`save()` 계열) | `ReactiveBeforeConvertCallback`을 통해 적용 |
| `ReactiveAfterSaveCallback` | O (`save()` 계열) | write 성공 직후 호출 |
| `ReactiveBeforeSaveCallback` | X | 현재 `MongoExecutionContext`에는 변환 후/write 전 hook이 없음 |
| `ReactiveAfterConvertCallback` | X | DSL의 `read(...)` 계약은 동기 변환이므로 별도 reactive callback을 호출하지 않음 |
| Spring query field mapping | X | DSL의 문자열 field path는 MongoDB document field 이름 기준 |
| Spring `DataAccessException` 변환 | X | 실제 operation은 MongoDB Reactive Streams Driver가 수행 |
| Spring `@Transactional` 자동 참여 | X | DSL transaction은 별도의 `ClientSession` 방식 |

특히 `@Field("user_name")`처럼 Java property와 저장 field 이름이 다른 경우, 엔티티 저장/조회 변환에는 Spring mapping이 적용되지만 DSL 조건에는 실제 MongoDB field 이름을 사용해야 합니다.

```java
@Field("user_name")
private String userName;
```

```java
// O: MongoDB document field 이름
.fields(pair("user_name", "kim"))

// Spring QueryMapper가 자동으로 바꿔주는 구조가 아니므로 권장하지 않음
.fields(pair("userName", "kim"))
```

`id` path는 DSL 자체 규칙에 따라 `_id`로 정규화됩니다.

또한 lifecycle hook은 `save()` 계열에 대한 hook입니다. Bulk/history/remove 경로는 호출하지 않으며, `atomicUpdate()`도 엔티티 save lifecycle을 거치지 않으므로 auditing 필드가 필요하면 update에 직접 포함해야 합니다.

---

### 3) 기본 조회


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

단건 조회:

```java
Mono<User> user = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("id", userId))
    .end()
    .find()
    .execute();
```

`find().execute()`와 `find().executeFirst()` 모두 `Mono<E>`를 반환합니다. `executeFirst()`는 query spec에 명시적으로 `limit(1)`을 적용한 첫 건 조회입니다.

---

## 실행 컨텍스트

### `executeEntity(...)`

엔티티 클래스와 resolver key로 실행 대상을 선택합니다.

```java
var userDsl = dsl.executeEntity(User.class, MongoTemplateName.FRONT);
```

collection 이름은 `MongoExecutionContext#getCollectionName(User.class)`에서 결정됩니다.

---

### `executeCustomClass(...)`

조회/저장 타입과 실제 collection 이름을 직접 지정할 때 사용합니다.

```java
Mono<Document> raw = dsl
    .executeCustomClass(Document.class, MongoTemplateName.FRONT, "user_archive")
    .fields(pair("status", "ACTIVE"))
    .end()
    .find()
    .execute();
```

---

## 필드명과 `id` 처리 규칙

DSL의 String 필드명은 기본적으로 **MongoDB physical field name**으로 취급됩니다.

별도의 Spring Data property mapping을 자동 적용하지 않습니다.

단, path segment가 정확히 `id`이면 MongoDB의 `_id`로 정규화합니다.

```java
pair("id", value)             // -> _id
pair("parent.id", value)      // -> parent._id
pair("incidentId", value)     // -> incidentId 그대로
pair("_id", value)            // -> _id 그대로
```

그리고 `id` alias를 사용한 경우 값이 유효한 24자리 hex String이면 `ObjectId`로 자동 변환합니다.

```java
pair("id", "64f0...")
```

위 조건은 `_id: ObjectId(...)` 비교로 렌더링될 수 있습니다.

반대로 `_id`를 직접 지정하면 String 값을 자동으로 `ObjectId`로 바꾸지 않습니다.

```java
pair("_id", new ObjectId(id))
```

처럼 타입을 직접 맞추면 됩니다.

Enum 필드명은 `Enum#toString()` 값을 사용합니다. physical field name을 enum으로 관리하려면 `toString()`을 실제 Mongo 필드명으로 정의할 수 있습니다.

---

## 조건 표현: `FieldsPair`

기본 형태:

```java
FieldsPair.pair(field, value)
FieldsPair.pair(field, value, condition)
FieldsPair.pair(field, condition)
```

지원 `Condition`:

| Condition | 의미 |
|---|---|
| `eq` | 같음 |
| `notEq` | 같지 않음 |
| `between` | inclusive 범위 |
| `gt` / `gte` | 초과 / 이상 |
| `lt` / `lte` | 미만 / 이하 |
| `in` / `notIn` | 포함 / 미포함 |
| `like` | case-insensitive regex |
| `regex` | regex |
| `exists` | 필드 존재 여부 |
| `isNull` / `isNotNull` | null / not-null 비교 |
| `all` | 배열 `$all` |
| `near` | legacy 2d near |
| `nearSphere` | sphere near |
| `elemMatch` | 배열 element 조건 |

예:

```java
.fields(
    pair("status", List.of("READY", "ACTIVE"), in),
    pair("price", List.of(10_000L, 50_000L), between),
    pair("deletedAt", isNull)
)
```

`like`는 입력 문자열을 일반 문자열 contains로 escape하지 않고 Mongo regex pattern으로 전달하며 `i` 옵션을 사용합니다.

---

### `autoRangePair(...)`

from/to 존재 여부에 따라 `between`, `gte`, `lte`를 자동 선택합니다.

```java
FieldsPair<String, Object> createdAt =
    FieldsPair.autoRangePair("createdAt", from, to);
```

동작:

```text
from != null && to != null -> between
from != null && to == null -> gte
from == null && to != null -> lte
from == null && to == null -> null
```

`Instant`, `LocalDateTime`, `LocalDate` 및 `[from, to]` 형태 list overload를 제공합니다.

둘 다 비어 있으면 `null`을 반환하므로 동적 조건 목록을 만들 때 그대로 제외할 수 있습니다.

---

## AND / OR / NOT 그룹핑

`FieldBuilder`는 `and`, `or`, `not`, `notAny`, `notAll`을 지원합니다.

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

루트 logical operator를 직접 정할 수도 있습니다.

```java
.fields(ReactiveMongoDsl.LogicalOperator.OR,
    pair("status", "READY"),
    pair("status", "ACTIVE")
)
```

---

## Driver-native filter escape hatch

DSL에 전용 `Condition`이 없는 MongoDB Driver filter를 그대로 넣을 수 있습니다.

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

Raw `Bson`은 DSL이 다시 해석하거나 field mapping하지 않고 driver 정의를 그대로 사용합니다.

---

## 조회: `findAll()` / `find()`

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

주요 옵션:

- `paging(pageNumber, pageSize)`
- `sorts()` / `sorts(callback)`
- `excludes(...)`
- `readPreference(...)`
- `isAllowDiskUse(...)`
- `customizeQuery(...)`
- `customizeAggregation(...)`

---

### 정렬: `SortSpec`

정렬은 입력 순서를 그대로 유지합니다.

```java
.findAll()
.sorts(sort -> sort
    .desc("score")
    .asc("createdAt")
    .desc("id")
)
.execute();
```

동적 방향:

```java
.sorts(sort -> sort.of(direction, field))

.sorts( sortSpec -> sorts.stream().filter( e -> ! e.trim().isBlank() ).limit( 10 ).forEach( e -> {
				String[] paths = e.split( "=" );
				if (paths.length != 2 || Stream.of( paths ).anyMatch( String::isBlank ))
					return;

				sortSpec.of( paths[1], paths[0].trim() );

			} ) )
```

지원 direction은 대소문자 구분 없이 `asc`, `desc`입니다.

Driver-native sort도 섞을 수 있습니다.

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

`find()`도 `sorts`, `excludes`, `readPreference`, `isAllowDiskUse`, query/aggregation customizer를 사용할 수 있습니다.

---

## 페이징

### 일반 find 페이징

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

`pageNumber`는 0-based입니다.

builder 스타일도 지원합니다.

```java
.findAll()
.paging()
    .pageNumber(0)
    .pageSize(20)
    .and()
.execute();
```

---

### 페이지 번호를 유지하는 cursor anchor 페이징

`findAll()`의 cursor 기능은 일반 페이징 terminal과 한 레벨에 섞지 않고 `paging()`에서 전략을 먼저 선택합니다. 페이지 번호 UI를 유지하면서 store-backed anchor로 deep skip 비용을 줄이려면 `pageNumberCursor(...)`를 선택합니다.

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

`pageNumber`는 기존 일반 페이징과 동일하게 0-based입니다. 기존 일반 페이징 API는 바뀌지 않습니다.

```java
// 기존 일반 page-number paging 그대로 유지
.findAll()
.paging()
    .pageNumber(20)
    .pageSize(50)
    .and()
.execute();
```

page-number cursor도 builder 형태로 각각 지정할 수 있습니다.

```java
.findAll()
.sorts(sort -> sort.desc("createdAt"))
.paging()
.pageNumberCursor()
    .pageNumber(20)
    .pageSize(50)
    .execute();
```

전략을 선택한 뒤에는 해당 전략에 필요한 API만 노출됩니다. `skipPolicy()`는 `pageNumberCursor()`에서만 사용할 수 있고, 아래의 page-number 없는 `cursor()` builder에는 노출되지 않습니다.

내부적으로는 현재 query signature에 대해 가장 가까운 이전 anchor를 찾고, 그 anchor부터 필요한 상대 구간만 이동합니다.

```text
pageNumber/pageSize + filter + sort + namespace version
    -> cursor query key 생성
    -> 현재 page 이하에서 가장 가까운 anchor 조회
    -> anchor가 있으면 anchor 조건 + 상대 skip
    -> pageSize + 1건 조회
    -> 현재 page 시작점 / 다음 page 시작점 anchor 저장
```

처음 요청하는 deep page에 가까운 anchor가 없을 때의 동작은 `skipPolicy()`로 제어합니다. 기본값은 `maxRelativeSkip=5,000` + `CursorSkipExceededAction.FAIL`이며, 상대 이동량이 한도를 넘으면 business collection query를 실행하기 전에 `CursorSkipLimitExceededException`을 반환합니다.

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

`onExceeded(...)` 선택지는 다음과 같습니다.

- `FAIL`: business collection query를 실행하지 않고 `CursorSkipLimitExceededException`을 반환합니다. 기본값입니다.
- `RETURN_EMPTY`: business collection query를 실행하지 않고 빈 결과를 반환합니다. 실제 데이터가 존재해도 빈 결과가 될 수 있으므로 이 의미를 의도한 API에서만 사용하는 것이 좋습니다.
- `EXECUTE_ANYWAY`: 한도를 넘어도 nearest anchor부터 계산된 relative skip을 그대로 실행합니다. 큰 skip 비용은 이 정책을 명시적으로 선택한 호출 측 책임이 됩니다.

따라서 `pageNumber=99999` 자체를 금지하는 것이 아니라 **nearest anchor부터 실제로 필요한 row skip**을 기준으로 정책을 적용합니다. 이미 가까운 anchor가 있으면 높은 page number도 낮은 비용으로 실행할 수 있습니다.

정렬 규칙:

- sort를 생략하면 `_id: -1`을 사용합니다.
- 사용자가 지정한 sort에 `_id`가 없으면 안정적인 tie-breaker로 `_id: -1`을 뒤에 자동 추가합니다.
- cursor anchor를 만들 수 있도록 각 sort 값은 일반적인 numeric ascending/descending (`1` / `-1`) 형태여야 합니다.
- meta sort처럼 cursor 값 비교 의미가 불명확한 sort는 page-number cursor에서 지원하지 않습니다.
- `customizeQuery(...)`는 filter/sort 의미를 DSL이 확정할 수 없으므로 cursor paging과 함께 사용할 수 없습니다.

`PageStream<T>` 형태가 필요하면 선택된 page-number cursor builder에서 `executePageStream()`을 사용합니다.

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

#### anchor admission / TTL 기본값

기본 in-memory state store는 모든 일회성 query를 무조건 캐시에 보관하지 않고 접근 패턴을 보고 anchor 저장을 admission합니다. 기본 `CursorCacheOptions.defaults()`는 다음과 같습니다.

| 옵션 | 기본값 | 의미 |
| --- | ---: | --- |
| `admissionWindow` | 10초 | hot query 판정 window |
| `admissionThreshold` | 4회 | window 안에서 이 횟수 이상 접근하면 admission |
| `idleTtl` | 1분 | 사용되지 않은 anchor/query state 만료 기준 |
| `maxQueries` | 10,000 | in-memory query state 상한 |
| `maxAnchorsPerQuery` | 256 | in-memory query별 anchor 상한 |
| `deepPageSkipThreshold` | 5,000 | 예상 skip이 이 값 이상이면 즉시 admission |
| `expirationTick` | 1초 | in-memory expiration wheel tick |
| `expirationWheelSize` | 512 | expiration wheel slot 수 |
| `maxRelativeSkip` | 5,000 | page-number cursor에서 nearest anchor부터 허용하는 최대 상대 row skip |
| `skipExceededAction` | `FAIL` | 상대 skip 한도 초과 시 `FAIL` / `RETURN_EMPTY` / `EXECUTE_ANYWAY` 중 수행할 동작 |
| `maxPageSize` | 500 | cursor API가 한 요청에서 허용하는 최대 page size |
| `tokenTtl` | 10분 | store-backed opaque cursor token TTL |

MongoDB-backed state store도 같은 admission 옵션과 idle TTL을 사용하지만, 현재 구현은 MongoDB에 저장된 query별 anchor를 `maxAnchorsPerQuery` 기준으로 서버에서 즉시 prune하지는 않습니다. MongoDB 쪽 오래된 anchor 정리는 TTL을 기준으로 이루어집니다.

외부 변경으로 stale anchor가 재사용되지 않도록 cursor query key에는 collection namespace version이 포함됩니다. Change Stream이 collection 변경을 관측하면 해당 namespace version이 증가하고, 이후 요청은 새로운 query key를 사용합니다. state store와 Change Stream 구성은 뒤의 **공통 상태 저장소**와 **공유 Change Stream** 섹션에서 설명합니다.

안전 제한만 조정하려면 기존 admission/TTL 설정을 유지한 채 다음처럼 복사할 수 있습니다.

```java
CursorCacheOptions cursorOptions = CursorCacheOptions
    .defaults()
    .withSafety(
        50_000L,                         // maxRelativeSkip
        CursorSkipExceededAction.FAIL,  // 한도 초과 동작
        200,                             // maxPageSize
        Duration.ofMinutes(5)            // tokenTtl
    );
```

page-number cursor의 skip 정책만 전역 기본값으로 바꾸려면 admission/TTL 설정을 유지한 채 다음처럼 설정할 수 있습니다.

```java
CursorCacheOptions cursorOptions = CursorCacheOptions
    .defaults()
    .withCursorSkipPolicy(
        20_000L,
        CursorSkipExceededAction.RETURN_EMPTY
    );
```

`paging().pageNumberCursor(...).skipPolicy()`를 지정하면 이 store/global 기본값보다 query 설정이 우선합니다. 지정하지 않으면 `CursorCacheOptions`의 값이 그대로 적용됩니다.

### 페이지 번호 없는 store-backed opaque cursor

무한스크롤/더보기처럼 임의 page number 이동이 필요하지 않다면 같은 `paging()` 진입점에서 `cursor()` 전략을 선택합니다. 이 builder에는 `pageNumber(...)`나 `skipPolicy()`가 노출되지 않습니다.

첫 요청:

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

다음 요청은 이전 응답의 opaque token을 `after(...)`에 그대로 전달합니다.

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

`cursor()`도 page size를 builder에서 나중에 지정할 수 있습니다.

```java
.paging()
.cursor()
    .pageSize(50)
    .after(token)
    .execute();
```

`CursorPage<T>`는 `data()`, `nextCursor()`, `hasNext()`를 제공합니다. 이 방식은 page number를 계산하거나 MongoDB `skip`을 사용하지 않습니다. token은 다음 페이지 시작점의 sort tuple 자체를 클라이언트에 노출하지 않고 state store에 저장한 뒤 opaque id만 반환합니다. 클라이언트가 임의로 매우 긴 값을 보내 state store lookup 비용으로 증폭시키지 못하도록 라이브러리가 발급하는 64자리 lowercase hex token 형식이 아니면 store 조회 전에 거부합니다.

token state에는 physical DB/collection namespace, query/filter/sort 의미, page size, sort tuple이 함께 묶입니다. 따라서 DSL은 다음을 거부합니다.

- 임의의 문자열/만료된 token
- 다른 DB/collection 또는 다른 filter/sort query에서 발급된 token
- 발급 당시와 다른 page size로 재사용하는 token

순수 keyset token은 page-number anchor와 달리 collection write마다 무효화하지 않습니다. token 자체가 특정 sort 위치를 가리키므로 데이터가 변경된 뒤에도 그 위치부터 **현재 데이터 기준으로** 계속 진행합니다. 따라서 이 API는 snapshot isolation을 제공하는 cursor가 아닙니다. 요청 사이에 이미 지나간 영역에 데이터가 추가/삭제되면 그 영역을 자동으로 다시 보여주지는 않는 일반적인 keyset pagination 의미를 가집니다.

token id는 같은 query/같은 다음 위치에 대해 deterministic하게 생성되고 store에는 upsert됩니다. 따라서 같은 페이지를 반복 조회한다고 매번 새로운 token document가 추가되지는 않습니다. token document는 `tokenTtl` 이후 만료됩니다.

높은 위치에서 발급된 token도 DB에서는 `sort tuple 이후 조건 + limit(pageSize + 1)`로 실행되므로 높은 page number에 비례하는 `skip` 비용이 생기지 않습니다. 다만 동일 token을 초당 수천 번 호출하는 **요청량 자체의 공격**은 계정/IP/API key 정보를 모르는 라이브러리가 완전히 해결할 수 없으므로 HTTP/API rate limiting은 사용하는 애플리케이션에서도 적용해야 합니다. 또한 keyset filter/sort가 효율적으로 동작하도록 cursor sort에 맞는 MongoDB index를 구성해야 합니다.

---

### `PageStream<T>`

data를 `List`로 먼저 모으지 않고 `Flux` 상태로 유지하고 싶으면 `executePageStream()`을 사용합니다.

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

배치/스트리밍 처리에서는 `PageResult`로 수집하는 방식보다 `PageStream`을 우선 고려할 수 있습니다.

---

## Aggregation 실행

일반 조건 builder를 aggregation pipeline으로 실행하는 API도 제공합니다.

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

기존 `PageResult<T>` 형태가 필요하면:

```java
Mono<PageResult<User>> page = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .paging(0, 20)
    .executeAggregation();
```

`executeAggregation()`은 내부적으로 reactive page를 최종 `List`로 collect해서 `PageResult`를 만듭니다.

단건은 `find().executeAggregation()`을 사용할 수 있습니다.

### Driver-native root aggregation

MongoDB Driver가 제공하는 aggregation stage를 pipeline 첫 단계부터 직접 조합해야 할 때는 `aggregation()`을 사용할 수 있습니다. DSL이 Driver aggregation API를 다시 구현하지 않고, Driver가 반환하는 `Bson` stage를 그대로 실행하는 escape hatch입니다. `Document` 역시 `Bson`이므로 그대로 전달할 수 있습니다.

```java
Flux<User> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .aggregation()
    .stage(Aggregates.match(Filters.eq("status", "ACTIVE")))
    .stage(Aggregates.sort(Sorts.descending("createdAt")))
    .stage(Aggregates.limit(20))
    .execute();
```

여러 stage는 호출 순서 그대로 보존됩니다. 결과 shape가 원본 entity와 다르면 `execute(ResultType.class)`를 사용하고, raw 결과가 필요하면 `executeDocument()`를 사용합니다.

MongoDB Driver 5.10.0의 `$score` / `$scoreFusion`도 전용 DSL wrapper 없이 Driver API를 그대로 전달합니다. 특히 `$scoreFusion`처럼 첫 stage 위치가 중요한 기능은 `aggregation()`에서 pipeline 순서를 직접 제어합니다.

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

Driver에 API가 존재하는 것과 서버에서 해당 stage를 지원하는 것은 별개입니다. `$score` / `$scoreFusion`은 MongoDB 8.2+, 5.10의 nested/array Vector Search 옵션은 MongoDB 8.3+ 기능이므로 실제 실행 환경의 서버 버전도 맞아야 합니다.

`aggregation()`에서도 `readPreference(...)`, `isAllowDiskUse(...)`, `customizeAggregation(...)`, `preview()`, `explain()`을 사용할 수 있습니다.

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

Aggregation exists도 지원합니다.

```java
.exists().executeAggregation();
```

---

### 조건 기반 delete

```java
Mono<DeleteResult> deleted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "REMOVED"))
    .end()
    .delete()
    .execute();
```

이 terminal의 `delete().execute()`는 현재 criteria에 맞는 문서를 `deleteMany` 방식으로 삭제합니다.

엔티티 하나를 id 기준으로 삭제하는 API는 별도로 `executeEntity(...).delete(entity)`를 사용합니다.

---

### distinct

`distinct`는 결과를 내부에서 `List`로 collect하지 않고 `Flux<R>`로 반환합니다.

```java
Flux<String> statuses = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("caseYear", 2026))
    .end()
    .distinct("status", String.class)
    .execute();
```

필요한 쪽에서 직접 모으면 됩니다.

```java
Mono<List<String>> statusList = statuses.collectList();
```

Enum-backed field도 사용할 수 있습니다.

```java
.distinct(UserField.STATUS, String.class)
```

---

## 저장: `save()` / `saveAll()`

### `save()`

```java
Mono<User> saved = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .save(user);
```

현재 동작은 다음과 같습니다.

- id 없음: `insertOne`
- id 있음: `_id` 기준 `replaceOne(..., upsert=true)`
- MongoDB가 `_id`를 생성하면 가능한 경우 `MongoExecutionContext#setId(...)`로 엔티티에 반영

`save()`는 `MongoExecutionContext.beforePersist(...)`와 `afterPersist(...)` lifecycle hook을 거칩니다.

---

### `saveAll()`

```java
Flux<User> saved = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAll(users);
```

`Iterable`, `Collection`, `Flux` overload를 제공합니다.

`saveAll()`은 각 엔티티에 대해 `save()`를 수행하므로 save lifecycle hook도 각 엔티티에 적용됩니다.

---

## Bulk 작업

### `saveAllBulk()`

```java
Flux<User> inserted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAllBulk(users);
```

현재 구현은 엔티티들을 모은 뒤 `insertMany`를 실행합니다.

- 기존 id 기준 update가 아니라 **bulk insert**
- generated `_id`는 가능한 경우 원본 엔티티에 반영
- `beforePersist` / `afterPersist` hook은 호출하지 않음

---

### `saveAllBulkUpsert()`

엔티티 id를 기준으로 bulk upsert합니다.

```java
Mono<BulkWriteResult> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .saveAllBulkUpsert(users);
```

동작:

- id 없음: `InsertOneModel`
- id 있음: `_id` 조건 + `$set` + `upsert(true)`

Bulk write는 `ordered(false)`로 실행됩니다.

---

### `saveAllBulkUpsertByKey()`

업무 복합키 기준 upsert:

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

지정한 key 중 하나라도 값이 없으면 해당 엔티티는 insert로 처리됩니다.

key가 모두 있으면:

- key 필드: `$setOnInsert`
- `_id`와 key를 제외한 나머지 document: `$set`
- `upsert(true)`

Bulk 계열은 save lifecycle hook을 호출하지 않습니다.

---

## 엔티티 삭제와 remove 백업

엔티티 자체를 삭제할 수 있습니다.

```java
Mono<DeleteResult> deleted = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .delete(user);
```

id가 있으면 `_id` 기준으로 삭제하고, id가 없으면 엔티티를 BSON으로 변환한 document를 filter로 사용합니다.

삭제 전후 보관용 remove collection을 사용하려면:

```java
dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .delete(user, true);
```

백업 collection 이름:

```text
<sourceCollection>_remove
```

Bulk 삭제:

```java
Mono<BulkWriteResult> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .deleteBulk(users, true);
```

현재 구현에서 `delete(entity, true)`는 실제 delete 후 remove collection에 백업하고, `deleteBulk(..., true)`는 remove collection에 먼저 백업한 뒤 bulk delete를 수행합니다. 또한 `deleteBulk(...)`의 실제 삭제 대상은 id가 존재하는 엔티티입니다.

`delete(..., true)` / `deleteBulk(..., true)`에서 삭제와 백업을 하나의 원자 작업으로 묶어야 한다면 호출 측에서 `getTxJob(...)`으로 transaction 범위를 잡는 것이 안전합니다.

---

## 히스토리 스냅샷

`createHistory(entity[, prefix])`는 현재 `MongoExecutionContext.write(...)` 결과를 deep copy한 뒤 `_id`를 제거하고 별도 collection에 insert합니다.

```java
Mono<Void> result = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .createHistory(user);
```

기본 collection:

```text
<sourceCollection>_history
```

prefix 지정:

```java
.createHistory(user, "snapshot")
```

결과:

```text
<sourceCollection>_snapshot
```

prefix에 `_snapshot`처럼 앞쪽 underscore가 있어도 중복 underscore가 생기지 않도록 정규화합니다.

History 저장은 save lifecycle hook을 호출하지 않습니다.

---

## Embedded snapshot 동기화

MongoDB 문서 안에 다른 엔티티의 스냅샷을 denormalize해서 저장하는 구조에서는 canonical source가 바뀔 때 embedded copy도 같이 갱신해야 할 수 있습니다. `EmbeddedSyncConfig<K>`는 이 관계를 DSL singleton 생성 시 등록하고, shared Change Stream을 기준으로 source 변경을 target 문서에 반영합니다.

이 기능은 query builder에 `syncEmbedded(...)` 같은 상태ful API를 추가하지 않습니다. 관계 정의는 별도 configuration object로 구성해서 `ReactiveMongoDsl` 생성자에 주입합니다.

### 기본 관계 정의

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

관계 방향은 다음 의미입니다.

```text
from(Order.class)       = canonical source
into(User.class, ...)   = source snapshot을 보관하는 target
```

위 예에서는 `Order.userId`와 `User._id`를 연결하고, Order의 현재 BSON snapshot을 User의 `orders` 필드에 동기화합니다. `intoField("id")`처럼 `id` path를 사용하면 일반 DSL과 동일하게 `_id`로 정규화됩니다. id alias에 연결되는 source 값이 유효한 24자리 hex String이면 ObjectId 비교가 가능하도록 변환됩니다.

한 관계에 link pair를 여러 개 둘 수도 있습니다.

```java
.linkBy()
    .fromField("tenantId")
    .intoField("tenantId")
    .fromField("userId")
    .intoField("id")
    .end()
```

`linkBy()`를 생략할 수도 있지만 의미가 다릅니다. explicit link가 없으면 source `_id`를 이미 포함하고 있는 target embedded field를 찾아 **기존 참조의 update/delete**는 할 수 있지만, 새 source insert가 어느 target에 새로 연결되어야 하는지는 알 수 없습니다. 새로운 association을 insert 시점부터 자동 생성해야 한다면 `linkBy()`를 명시해야 합니다.

### target field와 cardinality

명시적으로 field를 지정할 수 있습니다.

```java
.from(Profile.class)
.into(Account.class, "profile")
.build();
```

또는 target 타입에 source를 담을 수 있는 field가 정확히 하나라면 field 이름을 생략할 수 있습니다.

```java
.from(Profile.class)
.into(Account.class)
.build();
```

field를 생략했는데 호환되는 후보가 없거나 여러 개라면 build 단계에서 실패하므로 애매한 관계를 임의로 선택하지 않습니다.

cardinality는 target field의 Java metadata에서 추론합니다.

| target field 형태 | cardinality | 동기화 방식 |
| --- | --- | --- |
| `SourceType field` | SINGLE | `$set` / `$unset` |
| `Collection<SourceType> field` | COLLECTION | array upsert/update / `$pull` |
| `Map<String, SourceType> field` 등 | MAP | map entry upsert/remove pipeline |

MAP 관계는 source의 어느 값을 map key로 사용할지 반드시 지정해야 합니다.

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

실제 map key 값은 MongoDB field key로 사용할 수 있어야 하므로 blank, `.` 포함, `$`로 시작하는 값은 허용되지 않습니다.

### INSERT / UPDATE / REPLACE / DELETE 처리

Embedded sync는 source collection의 다음 Change Stream operation을 처리합니다.

- `INSERT`
- `UPDATE`
- `REPLACE`
- `DELETE`

UPDATE/REPLACE에서는 현재 source 문서를 `_id`로 다시 읽어 최신 snapshot을 사용합니다. 같은 relation/source id의 짧은 연속 변경은 내부 coalesce window에서 합쳐집니다.

Target 갱신은 target 엔티티 전체를 읽어서 `save()`하는 방식이 아니라 MongoDB atomic update/pipeline으로 수행합니다. 따라서 target의 unrelated field를 read-modify-write로 덮어쓰지 않습니다.

link 값이 바뀌어 source가 target A에서 target B로 이동하는 경우에는 현재 target에 snapshot을 반영한 뒤 이전 target에 남아 있는 stale reference도 정리합니다.

source delete의 기본 정책은 `EmbeddedDeletePolicy.REMOVE`입니다.

```java
.onDelete(EmbeddedDeletePolicy.REMOVE)
```

삭제 이벤트를 embedded snapshot에 반영하지 않으려면:

```java
.onDelete(EmbeddedDeletePolicy.IGNORE)
```

를 사용할 수 있습니다.

### multi-hop과 관계 graph 검증

예를 들어:

```text
C -> B.children
B -> A.child
```

처럼 여러 관계를 등록하면 C 변경으로 B가 갱신되고, 그 B 변경이 다시 Change Stream에 나타나 downstream A 관계까지 이어질 수 있습니다.

등록 시에는 resolver key별 directed graph를 검사합니다.

- `A -> B -> C` 같은 DAG는 허용됩니다.
- 실제 directed cycle (`A -> B -> A`, `A -> B -> C -> A`)은 거부됩니다.
- 관계가 없는 별도 edge가 반대 방향을 가진다는 이유만으로 거부하지 않습니다.
- 같은 target class + target field path에는 서로 다른 source owner를 동시에 등록할 수 없습니다.
- 같은 resolver key에 등록한 관계는 그 key가 가리키는 Mongo execution context 안에서 동작하며 다른 DB로 자동 복사하는 기능이 아닙니다.

### 기존 데이터와 startup 동작

관계를 등록했다고 기존 collection 전체를 startup 시 자동 full-scan/reconciliation하지는 않습니다. 등록 이후 Change Stream으로 관측되는 변경을 기준으로 동기화합니다.

이미 오래된 embedded snapshot이 존재하거나 신규 관계 도입 전에 생성된 데이터를 모두 맞춰야 한다면, 애플리케이션에서 별도의 명시적 backfill/reconciliation job을 실행해야 합니다. 라이브러리가 startup마다 대규모 collection scan을 숨겨서 실행하지 않습니다.

### 멀티 인스턴스와 lease

동일 relation을 여러 애플리케이션 인스턴스가 동시에 처리하지 않도록 embedded sync는 `EmbeddedSyncLeaseStore`를 사용합니다. `EmbeddedSyncConfig`에 별도 lease store를 주지 않으면 `ReactiveMongoDsl`의 unified state store가 lease에도 사용됩니다.

```java
EmbeddedSyncConfig<MongoTemplateName> embeddedSync =
    new EmbeddedSyncConfig<>(customLeaseStore);
```

기본 in-memory state store는 process-local이므로 단일 프로세스에서는 충분하지만, load balancing / multi-instance 환경에서 노드 간 lease를 공유해야 하면 MongoDB-backed state store 또는 직접 구현한 distributed store를 사용해야 합니다. 이 경우 모든 노드가 같은 logical Mongo scope에 대해 동일한 `MongoExecutionContext#getDistributedStateScopeKey()`를 반환해야 합니다.

---

## 트랜잭션: `getTxJob(...)`

현재 버전은 Spring `TransactionalOperator`가 아니라 MongoDB `ClientSession` transaction을 직접 사용합니다.

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

`getTxJob(...)`은:

1. `MongoExecutionContext.startSession()`
2. `session.startTransaction()`
3. Reactor Context에 session 전달
4. 정상 완료 시 commit
5. error/cancel 시 active transaction abort
6. session close

순서로 동작합니다.

트랜잭션 session은 `MongoExecutionContext#getSessionScope()`가 같은 DSL 실행에만 전파됩니다.

`DriverMongoExecutionContext`는 기본적으로 `MongoClient`를 session scope로 사용하므로 같은 client를 공유하는 context끼리는 같은 transaction session을 사용할 수 있습니다.

서로 다른 MongoClient를 사용하는 context는 같은 transaction으로 묶이지 않습니다.

`getTxJob(...)` 자체는 `TransientTransactionError` 같은 MongoDB transaction 오류를 자동 retry하지 않습니다. 재시도 정책은 호출 애플리케이션에서 업무 특성에 맞게 명시적으로 적용해야 합니다.

### Spring 환경에서의 트랜잭션

Spring adapter를 사용해도 DSL transaction의 동작 방식은 바뀌지 않습니다.

`@Transactional`, `ReactiveMongoTransactionManager`, `TransactionalOperator`는 Spring Data MongoDB가 관리하는 transaction 경계이고, `ReactiveMongoDsl#getTxJob(...)`은 DSL이 직접 `MongoExecutionContext.startSession()`으로 시작한 `ClientSession`을 Reactor Context에 전달하는 방식입니다.

따라서 Spring `@Transactional` 블록 안에서 DSL을 호출했다고 해서 DSL operation이 Spring이 바인딩한 Mongo session에 자동 참여한다고 가정하면 안 됩니다. 반대로 `getTxJob(...)` 안에서 `ReactiveMongoTemplate`이나 Spring Data Repository를 호출한다고 해서 해당 Spring operation이 DSL session을 자동 사용한다고 가정해서도 안 됩니다.

DSL operation끼리 transaction을 구성할 때는 `getTxJob(...)`을 사용하고, Spring Data Template/Repository operation끼리 transaction을 구성할 때는 Spring의 transaction infrastructure를 사용합니다. 두 방식을 하나의 transaction으로 혼합해야 하는 경우에는 session 연동을 애플리케이션에서 명시적으로 설계해야 합니다.

---

## 공통 상태 저장소: cursor / Change Stream / embedded lease

Cursor anchor/namespace version, Change Stream resume checkpoint, embedded-sync lease는 서로 다른 기능이지만 운영 관점에서는 모두 DSL의 장기 상태입니다. 기본 생성자는 이 세 기능에 하나의 `InMemoryReactiveMongoDslStateStore`를 사용합니다.

```java
ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver);
```

이 기본값은 **process-local**입니다. 단일 프로세스에서는 별도 인프라 없이 사용할 수 있지만, load balancer 뒤에 여러 인스턴스가 있는 환경에서 노드 간 cursor state/checkpoint/lease를 공유하지는 않습니다.

### 하나의 unified state store 주입

공유 backend를 사용할 때는 `ReactiveMongoDslStateStore` 하나를 주입할 수 있습니다.

```java
ReactiveMongoDslStateStore stateStore = ...;

ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, stateStore);
```

Embedded sync도 함께 사용하면:

```java
ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, embeddedSync, stateStore);
```

기본적으로 이 state store 하나가 다음 역할을 모두 담당합니다.

- cursor anchor 저장 / 조회
- opaque cursor token 저장 / 조회 / TTL
- collection namespace version 저장 / invalidation
- Change Stream resume checkpoint 저장
- embedded-sync distributed lease

고급 사용자가 기능별 backend를 의도적으로 분리하고 싶다면 각 SPI 구현을 `ReactiveMongoDslStateStore.of(...)`로 합칠 수 있습니다.

```java
ReactiveMongoDslStateStore stateStore = ReactiveMongoDslStateStore.of(
    cursorAnchorStore,
    changeStreamCheckpointStore,
    embeddedSyncLeaseStore
);
```

즉 backend는 in-memory/MongoDB로 고정된 API가 아닙니다. `ReactiveMongoDslStateStore` 또는 개별 SPI를 구현하면 Redis나 별도 저장소 등 다른 backend도 연결할 수 있습니다. 현재 코어에 내장된 unified 구현은 in-memory와 MongoDB입니다. 직접 `CursorAnchorStore`를 구현하는 경우 page-number anchor 기능만 필요하면 기존 `floor/put` 계약으로 충분하지만, `paging().cursor(...)` opaque token 기능까지 사용하려면 `putToken(...)` / `resolveToken(...)`도 구현해야 합니다.

### MongoDB-backed unified state store

동일 MongoDB 또는 별도 state DB에 상태를 두려면 `MongoReactiveMongoDslStateStore`를 사용할 수 있습니다.

```java
MongoExecutionContext context = resolver.getTemplate(MongoTemplateName.FRONT);

ReactiveMongoDslStateStore stateStore =
    new MongoReactiveMongoDslStateStore(context);

ReactiveMongoDsl<MongoTemplateName> dsl =
    new ReactiveMongoDsl<>(resolver, stateStore);
```

기본 state collection 이름은:

```text
__reactive_mongo_dsl_state
```

이며 하나의 collection 안에서 cursor anchor, opaque cursor token, namespace version, Change Stream checkpoint, embedded-sync lease를 kind별 document로 저장합니다.

기본적으로 다음 index를 보장합니다.

- `expiresAt` TTL index (`expireAfter=0`)
- cursor floor 조회용 `(kind, queryKey, pageNumber desc)` compound index

옵션을 직접 지정할 수 있습니다.

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

`changeStreamConsumerId`는 Change Stream resume token을 logical consumer별로 분리합니다. `null`이면 store instance마다 process-unique UUID를 사용하므로 동시에 실행 중인 여러 노드가 하나의 checkpoint id를 서로 덮어쓰지 않습니다. 특정 logical consumer가 process restart 후에도 이전 token에서 resume해야 한다면 **재시작 전후에는 안정적이면서 동시에 활성화된 다른 consumer와는 겹치지 않는 값**을 명시적으로 사용해야 합니다.

Checkpoint document는 현재 7일 TTL을 사용합니다. cursor anchor는 `CursorCacheOptions.idleTtl()`을 기준으로 만료됩니다. namespace invalidation은 Change Stream `clusterTime`을 함께 저장해 동일하거나 더 오래된 이벤트가 재전달되어도 version을 다시 증가시키지 않도록 처리합니다.

State store가 **실제로 같은 watched Mongo scope**에 있을 때는 내부 state collection을 database Change Stream pipeline에서 제외해 다음과 같은 feedback loop를 막습니다.

```text
state write
 -> Change Stream event
 -> state invalidation/checkpoint write
 -> 다시 Change Stream event
 -> ...
```

같은 DB에 둘 경우 `MongoExecutionContext`를 받는 `MongoReactiveMongoDslStateStore` 생성자를 사용하면 session scope까지 비교할 수 있어 이 판별이 가장 명확합니다.

### `distributedStateScopeKey`

Distributed state store는 프로세스 identity가 아니라 여러 노드에서 공유 가능한 stable namespace가 필요합니다. `MongoExecutionContext#getDistributedStateScopeKey()`가 그 역할을 합니다.

같은 logical Mongo scope를 처리하는 모든 노드는 같은 값을 반환해야 하고, 서로 다른 cluster/tenant/logical DB가 우연히 같은 state를 공유하지 않도록 범위별로 다른 값을 사용해야 합니다.

Custom `MongoExecutionContext`에서는 예를 들어:

```java
@Override
public String getDistributedStateScopeKey() {
    return "auction-front-prod";
}
```

처럼 정의할 수 있습니다. `DriverMongoExecutionContext`도 explicit `distributedStateScopeKey`를 받는 생성자를 제공합니다.

Distributed cursor/checkpoint/lease store를 사용하면서 이 값이 없으면 DSL은 조용히 process-local key로 추측하지 않고 해당 기능 초기화 시 오류를 발생시킵니다.

---

## 공유 Change Stream

`ReactiveMongoDsl`은 cursor invalidation, embedded sync, query reservation, 직접 watch가 각각 MongoDB Change Stream을 따로 열지 않도록 `ChangeStreamHub`를 공유합니다.

Public facade:

```java
Flux<ChangeStreamDocument<Document>> databaseChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT);

Flux<ChangeStreamDocument<Document>> userChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT, User.class);

Flux<ChangeStreamDocument<Document>> rawCollectionChanges =
    dsl.changeStreams().watch(MongoTemplateName.FRONT, "user");
```

같은 session scope + database의 collection watch들은 하나의 database-wide physical stream을 공유하고 collection별 filtered view를 받습니다.

### checkpoint와 최초 구독 경계

Shared stream이 처음 준비될 때 MongoDB operation time을 기준점으로 확보합니다.

- 저장된 checkpoint가 있으면 `resumeAfter(resumeToken)`을 사용합니다.
- checkpoint가 없으면 `startAtOperationTime(initialOperationTime)`을 사용합니다.

따라서 subscriber 등록과 실제 MongoDB server-side Change Stream cursor open 사이에 write가 발생하는 초기화 race에서 이벤트를 놓치지 않도록 합니다.

내부 처리는 작은 batch로 묶습니다. 현재 구현은 최대 256 events 또는 10ms window를 사용하며, cursor namespace invalidation처럼 collection 단위로 합칠 수 있는 내부 작업은 batch observer에서 coalesce합니다. checkpoint도 event마다 쓰지 않고 batch에서 마지막으로 resume 가능한 token을 저장합니다.

중요한 점은 **사용자에게 노출되는 Change Stream event 자체를 합치는 것이 아니라 내부 state side effect만 batch 처리한다는 것**입니다. `changeStreams().watch(...)` subscriber에는 원래 event가 다시 개별 순서대로 전달됩니다.

Internal observer/batch observer가 실패하면 그 batch의 checkpoint를 먼저 앞으로 보내지 않습니다. 재연결 시 이미 checkpoint 뒤로 숨은 이벤트가 되지 않도록 observer 처리가 checkpoint보다 먼저 완료됩니다.

### `reservationChangeStream()`: 변경 시 finite query 재실행

`findAll()` query 결과를 변경 시점마다 다시 받고 싶으면 query reservation을 사용할 수 있습니다.

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

이 stream은:

1. 최초 finite query snapshot을 한 번 emit하고
2. dependency collection의 Change Stream event를 기다린 뒤
3. 변경이 발생하면 같은 finite query를 다시 실행해 새 snapshot을 emit합니다.

기본 coalesce window는 50ms이며 `Duration.ZERO`로 비활성화할 수 있습니다.

추가 dependency도 지정할 수 있습니다.

```java
.reservationChangeStream()
.watch(Profile.class)
.watch(MongoTemplateName.BACK, Audit.class)
.watch(MongoTemplateName.FRONT, "external_status")
.execute();
```

제공 terminal:

- `.changes()` / `.invalidations()` : dependency Change Stream event 자체
- `.execute()` : 일반 finite query 재실행
- `.executeLookup(right, spec)` : lookup finite query 재실행

Page-number cursor snapshot 재조회는 cursor 전략을 선택한 뒤 `.reservationChangeStream().execute()` 또는 `.executeLookup(right, spec)`를 사용합니다.

```java
.paging()
.pageNumberCursor(20, 50)
.reservationChangeStream()
.execute();
```

Lookup reservation은 right collection과 `LookupSpec` 안의 nested `$lookup` dependency도 자동으로 watch 대상에 포함합니다.

Reservation은 현재 query filter를 MongoDB Change Stream의 document-level `$match`로 자동 변환하는 기능이 아닙니다. **dependency collection에서 변경이 발생하면 query를 다시 실행하는 invalidation → pull 모델**입니다. 따라서 collection 변경 빈도가 높고 finite query가 무거운 경우에는 적절한 `coalesce(...)`, dependency 범위, query 비용을 함께 고려해야 합니다.

---

## `$lookup` 조인

### `LookupSpec`

`LookupSpec`은 간단한 `localField / foreignField` 방식과 `let + pipeline + $expr` 방식을 모두 지원합니다.

간단한 join:

```java
LookupSpec spec = LookupSpec.builder()
    .as("orders")
    .localField("id")
    .foreignField("userId")
    .build();
```

조건 기반 join:

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

주요 builder:

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

`bindConditionFieldsLeftToObjectId(...)`는 left field를 `$convert: { to: "objectId" }`로 변환한 뒤 right field와 비교합니다.

필드명 규칙은 일반 DSL과 동일하므로 `id`는 `_id`로 정규화됩니다.

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

`LookupSpec.unwind(...)`를 사용하지 않은 기본 lookup은 right 결과가 `List<R>` 형태입니다.

---

### `executeLookupAndCount(...)`

```java
Mono<PageResult<ResultTuple<User, List<Order>>>> page =
    left.executeLookupAndCount(right, spec);
```

`$facet(data, count)` 형태로 data와 totalCount를 같이 반환합니다.

---

### lookup cursor 페이징

Lookup도 left builder의 `paging()`에서 cursor 전략을 먼저 선택합니다.

페이지 번호를 유지하는 lookup cursor:

```java
Flux<ResultTuple<User, List<Order>>> joined = left
    .paging()
    .pageNumberCursor(20, 50)
    .executeLookup(right, spec);
```

`PageResult`와 total count가 같이 필요하면 같은 typed builder에서 `executeLookupAndCount(...)`를 사용합니다.

```java
Mono<PageResult<ResultTuple<User, List<Order>>>> page = left
    .paging()
    .pageNumberCursor(20, 50)
    .executeLookupAndCount(right, spec);
```

anchor는 left builder의 `sorts(...)`와 선택한 `pageNumber/pageSize`를 기준으로 만들며, 기본 sort/tie-breaker/admission/skip policy 규칙은 일반 page-number cursor와 같습니다. `skipPolicy()`도 같은 builder에서 사용할 수 있습니다.

Lookup cursor query signature에는 left filter/sort/page size뿐 아니라 right collection/criteria, `LookupSpec` pipeline/outer stage/unwind, nested `$lookup` dependency namespace version까지 포함됩니다. 따라서 join된 right collection 변경으로 left 결과 집합이나 lookup 결과가 달라져도 기존 page-number anchor를 stale 상태로 재사용하지 않습니다.

page number가 필요 없는 lookup은 `cursor()` 전략에서 동일한 terminal 이름 `executeLookup(...)`을 사용합니다.

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

이 경로는 left sort tuple을 store-backed token으로 저장하며 `skip`을 사용하지 않습니다. token query fingerprint에는 right criteria, lookup pipeline 의미, left/right physical namespace identity도 포함되지만 page-number anchor처럼 Change Stream namespace version에는 묶지 않습니다.

`customizeAggregation(...)`은 최종 lookup pipeline 의미를 DSL이 확정할 수 없으므로 lookup cursor와 함께 사용할 수 없습니다.

내부 aggregation projection은 left/right class simple name을 BSON field alias로 직접 사용하지 않습니다. 따라서 left와 right가 동일한 class이거나 둘 다 `Document.class`인 lookup에서도 내부 projection key가 충돌하지 않으며, 최종 `ResultTuple`의 logical left/right name은 기존 방식대로 유지됩니다.

---

### `find().executeLookup(...)`

단건 left + 단건화된 right 결과가 필요하면 `find()` 경로를 사용할 수 있습니다.

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

lookup 결과를 기반으로 좌/우 count 또는 존재 여부를 함께 받을 수도 있습니다.

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

## 그룹핑 Aggregation

query terminal builder에서 `group(keyType, valueType)`로 그룹핑할 수 있습니다.

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

지원 accumulator helper:

- `count()` / `countAs(alias)`
- `sum(field, alias)`
- `avg(field, alias)`
- `min(field, alias)`
- `max(field, alias)`
- `addToSet(field, alias)`
- `push(field, alias)`
- `accumulator(BsonField)`

Driver-native accumulator가 필요하면 `accumulator(...)`를 사용합니다.

key/value mapping을 직접 바꾸고 싶으면:

```java
.group(String.class, GroupResult.class)
.keyConverter(document -> ...)
.valueConverter(document -> ...)
```

그룹핑에도 `executeLookup(rightBuilder, spec)`를 사용할 수 있습니다.

---

## 원자적 업데이트: `atomicUpdate()`

현재 API는 업데이트 대상 범위와 업데이트 종류를 먼저 명확하게 선택합니다.

```text
atomicUpdate()
    -> first() / multi() / upsertOne()
    -> document() / pipeline()
    -> update operations
    -> execute()
```

---

### Document update

단건:

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

다건:

```java
.atomicUpdate()
.multi()
.document()
.set("status", "ARCHIVED")
.execute();
```

upsert 단건:

```java
.atomicUpdate()
.upsertOne()
.document()
.set("status", "ACTIVE")
.setOnInsert("createdAt", Instant.now())
.execute();
```

Document update helper:

- `inc(field, delta)`
- `set(field, value)`
- `unset(field)`
- `push(field, value)`
- `addToSet(field, value)`
- `pull(field, value)`
- `driverUpdate(Bson)`

`setOnInsert(...)`는 `upsertOne().document()`에서만 제공합니다.

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

지원 helper:

- `set(field, valueOrExpression)`
- `inc(field, delta)`
- `unset(fields...)`
- `stage(Bson)`
- `nextStage()`

`nextStage()`는 현재 pending `$set`을 하나의 stage로 flush합니다.

직접 pipeline stage를 추가하려면:

```java
.stage(Aggregates.set(new Field<>("normalized", ...)))
```

---

### Auditing / lifecycle 주의

`atomicUpdate()`는 엔티티 save가 아니라 Driver update 연산을 직접 수행합니다.

따라서 `MongoExecutionContext.beforePersist(...)` / `afterPersist(...)`를 거치지 않습니다.

Spring adapter가 별도로 auditing hook을 구현한 환경이라도 atomic update에서는 자동 auditing을 기대하면 안 됩니다.

필요한 값은 직접 넣어야 합니다.

```java
.atomicUpdate()
.first()
.document()
.set("updatedAt", Instant.now())
.execute();
```

---

## Query / Aggregation Driver customizer

기본 DSL에서 직접 노출하지 않은 Driver publisher 옵션을 호출 측에서 추가할 수 있습니다.

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

또한 공통 옵션으로:

```java
.readPreference(ReadPreference.secondaryPreferred())
.isAllowDiskUse(true)
```

를 지원합니다.

---

## `preview()` / `explain()`

### `preview()`

`preview()`는 실제 Mongo query를 실행하지 않고 현재 DSL 상태를 진단용 `Document`로 렌더링합니다.

예:

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

일반 query 외에도 다음 terminal들이 preview를 지원합니다.

- classic `findAll`
- classic `find`
- classic `count`
- classic `exists`
- `distinct`
- Atlas Search `findAll/find/count/existsQuery`
- Vector Search `findAll/find/count/existsQuery`

`preview()`는 실행 계획이 아니라 **DSL이 만들 query/pipeline의 로컬 진단 표현**입니다.

---

### `explain()`

실제 MongoDB Driver explain을 호출합니다.

```java
Mono<Document> explain = dsl
    .executeEntity(User.class, MongoTemplateName.FRONT)
    .fields(pair("status", "ACTIVE"))
    .end()
    .findAll()
    .explain();
```

verbosity 지정:

```java
import com.mongodb.ExplainVerbosity;

.explain(ExplainVerbosity.QUERY_PLANNER)
```

지원되는 경로에는 `QUERY_PLANNER`, `EXECUTION_STATS`, `ALL_PLANS_EXECUTIONS` 등 Driver의 `ExplainVerbosity` 값을 그대로 전달할 수 있습니다.

`preview()`와 달리 `explain()`은 실제 DB 연결이 필요합니다.

---

## 결과 타입

### `PageResult<T>`

기존 page 형태:

```text
List<T> data
Long totalCount
```

데이터를 최종적으로 메모리에 collect한 결과가 필요할 때 사용합니다.

---

### `PageStream<T>`

reactive-friendly page:

```text
Flux<T> data
Mono<Long> totalCount
```

데이터 스트림을 유지한 채 처리해야 하는 batch/streaming 작업에 적합합니다.

필요하면:

```java
page.collectToPageResult()
```

로 `PageResult<T>`로 변환할 수 있습니다.

---

### `ResultTuple<L, R>`

lookup/group 등에서 좌/우 결과 또는 이름과 값을 함께 담는 컨테이너입니다.

---

## 라이프사이클 / 매핑 확장

기본 `DriverMongoExecutionContext`는 MongoDB Driver POJO codec을 사용합니다.

프레임워크나 프로젝트 고유 매핑이 필요하면 `MongoExecutionContext`를 구현하거나 확장해서 다음을 바꿀 수 있습니다.

```java
Document write(Object source)
<T> T read(Class<T> targetType, Document source)
Object getId(Object entity)
void setId(Object entity, Object id)
<T> Mono<T> beforePersist(T entity, String collectionName)
<T> Mono<T> afterPersist(T entity, Document document, String collectionName)
```

Spring Data MongoDB를 사용하는 경우에는 앞의 **Spring Data MongoDB 프로젝트에서 사용하기** 예제처럼 `ReactiveMongoTemplate`의 converter / collection naming / reactive lifecycle을 adapter에 연결할 수 있습니다.

코어 라이브러리 자체는 특정 프레임워크에 의존하지 않습니다.

---

## Driver 우선 설계

이 DSL은 MongoDB Java Driver를 대체하는 추상화가 아니라 애플리케이션 레벨 convenience layer입니다.

기본 원칙은 다음과 같습니다.

1. Driver에 typed builder가 있으면 가능한 한 Driver builder를 사용합니다.
2. DSL은 반복적인 조합과 Reactor 연결을 단순화합니다.
3. Driver가 새 기능을 먼저 제공하면 raw/driver escape hatch로 바로 사용할 수 있게 둡니다.

대표 escape hatch:

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

## 주의사항

### 일반 Query

- String field name은 Java property가 아니라 MongoDB physical field name 기준입니다.
- 단 `id` path segment만 `_id` alias로 처리합니다.
- `_id`를 직접 쓸 때 String → `ObjectId` 자동 변환은 하지 않습니다.
- `like`는 case-insensitive regex이며 입력 문자열을 literal contains 검색으로 escape하지 않습니다.
- `near` / `nearSphere`는 `Double[]{longitude, latitude, maxDistance[, minDistance]}` 형태를 사용합니다. `nearSphere`의 거리 값은 meter 기준으로 받아 내부에서 지구 반지름 기준 단위로 변환하며, MongoDB geo index와 저장 좌표 형식도 맞아야 합니다.
- `saveAllBulk()`는 upsert가 아니라 `insertMany`입니다.
- Bulk/history/remove 경로는 save lifecycle hook을 호출하지 않습니다.
- `atomicUpdate()`도 save lifecycle/auditing hook을 호출하지 않습니다.
- `getTxJob(...)`은 transaction retry policy를 대신하지 않습니다.

### `$lookup`

- lookup sub-pipeline의 일반 조건은 right builder criteria와 `LookupSpec` pipeline으로 구성됩니다.
- `bindConditionFieldsLeftToObjectId(...)`는 left 값을 ObjectId로 변환합니다.
- lookup `$expr` helper에서 `near`, `nearSphere`, `elemMatch`는 지원하지 않으며 `rawStage(Bson)`을 사용해야 합니다.

### Cursor paging / state store / Change Stream

- Page-number cursor의 상대 skip이 `maxRelativeSkip`을 넘으면 `skipExceededAction` 정책을 따릅니다. 기본값 `FAIL`은 business collection query 전에 차단하며, page-number cursor builder의 `skipPolicy()` 또는 global `CursorCacheOptions`로 `RETURN_EMPTY` / `EXECUTE_ANYWAY`를 선택할 수 있습니다. 페이지 번호 없는 opaque cursor는 `skip` 자체를 사용하지 않습니다.
- Cursor sort는 deterministic한 numeric ascending/descending field로 구성해야 하며 `_id`가 없으면 tie-breaker로 `_id: -1`이 추가됩니다.
- 기본 state store는 process-local입니다. multi-instance에서 cursor/checkpoint/embedded lease를 노드 간 공유하려면 distributed store와 stable `distributedStateScopeKey`를 사용해야 합니다.
- MongoDB-backed state store의 `changeStreamConsumerId`를 여러 동시 consumer가 같은 값으로 공유하면 안 됩니다. 재시작 continuity가 필요한 consumer만 자신의 stable/unique id를 사용합니다.
- `reservationChangeStream()`은 query filter를 Change Stream `$match`로 변환하지 않습니다. dependency collection 변경을 invalidation으로 보고 finite query를 다시 실행합니다.
- Shared Change Stream 내부 state side effect는 batch 처리될 수 있지만 public watch event는 원래 event 단위로 전달됩니다.

### Embedded snapshot sync

- `from`이 canonical source이고 `into`가 snapshot target입니다. 방향을 반대로 등록하면 의미도 반대가 됩니다.
- 신규 insert가 어느 target에 연결될지 알아야 하는 관계는 `linkBy()`를 명시해야 합니다.
- relation graph의 directed cycle과 동일 target field의 multiple source owner는 등록 단계에서 거부됩니다.
- registration은 startup full reconciliation이 아닙니다. 기존 데이터 backfill이 필요하면 별도 application job으로 명시적으로 수행해야 합니다.
- multi-instance coordination은 lease store에 의존하며 기본 in-memory lease는 process-local입니다.

### Atlas Search

- 실제 Atlas Search index가 먼저 구성되어 있어야 합니다.
- `$search` / `$searchMeta`는 pipeline 첫 stage 제약을 가집니다.
- `autocomplete`는 현재 single path입니다.
- `text.fuzzy`와 `text.synonyms`는 동시에 사용할 수 없습니다.
- `search().fields(...)`는 `$search` 내부 filter가 아니라 뒤쪽 일반 `$match`입니다.
- `count().execute()`와 `count().executeSearchMeta()`는 서로 다른 count입니다.
- sequence token pagination을 안정적으로 사용하려면 deterministic sort를 구성하는 것이 좋습니다.

### Vector Search

- `vectorSearch(index)`는 명시적인 vector index 이름이 필요합니다.
- `$vectorSearch`는 pipeline 첫 stage여야 합니다.
- ANN에서는 `numCandidates(...)` / `approximate(...)`가 필요합니다.
- ENN에서는 `exact()`을 사용합니다.
- `filterFields(...)` / `filter(...)`는 `$vectorSearch.filter` pre-filter입니다.
- `fields(...)`는 `$vectorSearch` 뒤의 일반 `$match` post-filter입니다.
- `count()`는 vector `limit` 이후 pipeline 결과 수입니다.
- 현재 Vector Search에는 Atlas Search와 같은 `executePage()` / sequence token / metadata count terminal이 없습니다.

---

## 어떤 API를 써야 하나

기준을 단순하게 잡으면 다음과 같습니다.

- 일반 Mongo 조건 조회: `fields(...).end()`
- Driver filter를 그대로 사용: `driverFilter(...)`
- 다건 조회: `findAll()`
- 단건 조회: `find()`
- count: `count()`
- 존재 여부: `exists()`
- distinct stream: `distinct(field, resultClass)`
- 조건 기반 다건 삭제: `end().delete()`
- 엔티티 저장: `save()` / `saveAll()`
- 대량 insert: `saveAllBulk()`
- id 기준 bulk upsert: `saveAllBulkUpsert()`
- 업무키 기준 bulk upsert: `saveAllBulkUpsertByKey()`
- 원자 update: `atomicUpdate()`
- join: `executeLookup(...)`
- 일반 page-number 페이징: 기존 `findAll().paging(pageNumber, pageSize)` 또는 `paging().pageNumber(...).pageSize(...).and()`
- page-number cursor 페이징: `findAll().paging().pageNumberCursor(pageNumber, pageSize).execute()`
- page number 없는 opaque cursor: `findAll().paging().cursor(pageSize).after(token).execute()`
- lookup page-number cursor: `left.paging().pageNumberCursor(...).executeLookup(...)` / `executeLookupAndCount(...)`
- lookup opaque cursor: `left.paging().cursor(...).after(token).executeLookup(...)`
- 변경 시 finite query snapshot 재조회: `findAll().reservationChangeStream()`
- 직접 Change Stream 구독: `changeStreams().watch(...)`
- embedded snapshot 자동 동기화: `EmbeddedSyncConfig`
- cursor/checkpoint/lease 공통 상태 backend: `ReactiveMongoDslStateStore`
- 그룹 집계: `group(...)`
- 실행 전 query/pipeline 확인: `preview()`
- 실제 실행 계획 확인: `explain()`
- Atlas Search: `search(...)`
- Vector Search: `vectorSearch(...)`
- transaction: `getTxJob(...)`

일반 Mongo 조회 기능 위에 Search/Vector를 별도 검색 레이어로 추가하는 구조이며, Search/Vector 때문에 기존 조회/저장/수정 DSL 흐름을 대체하지 않습니다.

---

## 확장 기능: Atlas Search / Vector Search

Atlas Search와 Vector Search는 기존 `fields(...).end()` 기반 DSL을 대체하지 않습니다. 일반 조회/저장/수정/lookup 흐름은 그대로 유지하고, 검색 전용 aggregation stage가 필요한 경우에만 별도 진입점으로 확장해서 사용합니다.

## Atlas Search

Atlas Search는 `$search` / `$searchMeta`가 aggregation pipeline의 첫 stage여야 하므로 일반 `fields(...).end()` terminal과 분리되어 있습니다.

### 기본 진입점

기본 index:

```java
.search()
```

명시적 index:

```java
.search("articles_default")
```

예:

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

### Search root operator

`SearchBuilder`에서 다음 operator를 바로 구성할 수 있습니다.

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

MongoDB Driver가 새 operator를 먼저 지원한 경우 Driver-native operator를 그대로 넣을 수도 있습니다.

```java
SearchOperator driverSearchOperator = ...;

.search("articles_default")
.operator(driverSearchOperator)
.findAll()
.execute();
```

`AtlasSearchOperator` 형태로 이름까지 붙여 재사용하려면:

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

Search clause path는 다음 입력을 지원합니다.

- `String`
- `Enum<?>`
- Driver-native `SearchPath` / `FieldSearchPath`
- custom wrapper (`toString()` fallback)

Search 전용 helper:

```java
SearchPaths.field("title")
SearchPaths.wildcard("content.*")
```

일반 DSL과 마찬가지로 String/Enum path는 `id` segment를 `_id`로 정규화합니다.

---

### `SearchOperators`

재사용 가능한 clause는 `SearchOperators`에서 만들 수 있습니다.

```java
TextClause titleClause = SearchOperators.text()
    .path("title")
    .query("atlas search");
```

제공 factory:

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

지원 옵션:

- `path(...)`
- `paths(...)`
- `query(...)`
- `queries(...)`
- `fuzzy(maxEdits, prefixLength, maxExpansions)`
- `matchCriteria(SearchMatchCriteria.ANY/ALL)`
- `synonyms(mappingName)`
- `score(SearchScoreSpec)`
- `score(SearchScore)`

예:

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

`fuzzy(...)`의 `maxEdits`는 현재 1 또는 2만 허용합니다.

`fuzzy(...)`와 `synonyms(...)`는 동시에 사용할 수 없습니다.

`matchCriteria(...)`는 Driver 5.9.x에서 전용 typed method가 아직 없는 부분만 DSL이 좁게 BSON option을 보완합니다.

---

### `phrase`

지원 옵션:

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

현재 autocomplete는 single path 방식입니다.

지원 옵션:

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

`equals`는 단일 path + 단일 value 비교용입니다.

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

현재 clause는 String, Boolean, 정수/실수, `Instant`, `ObjectId`, UUID 등 Driver가 지원하는 typed value overload와 `valueNull()`을 제공합니다.

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

일반 Mongo `end().exists()`와 이름은 비슷하지만 의미가 다릅니다.

- `search().exists(...)`: Atlas Search operator
- `search()....existsQuery()`: 검색 결과가 한 건 이상 존재하는지 확인하는 terminal

---

### `in`

`InClause`는 value type을 명시적으로 구분하는 API를 제공합니다.

예:

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

주요 value helper:

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

지원 boundary:

- `gt(...)`
- `gte(...)`
- `lt(...)`
- `lte(...)`

숫자/날짜/ObjectId 등 Driver가 지원하는 typed range value를 사용할 수 있습니다.

---

### `compound`

복합 검색은 `compound(...)`로 구성합니다.

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

일반 operator 자체를 전달할 수도 있습니다.

```java
.compound(compound -> compound
    .must(SearchOperators.text().path("title").query("mongo"))
    .should(SearchOperators.phrase().path("title").query("reactive mongo"))
    .filter(SearchOperators.equals().path("status").value("PUBLISHED"))
)
```

지원 group:

- `must(...)`
- `mustNot(...)`
- `should(...)`
- `filter(...)`
- `minimumShouldMatch(...)`
- `score(...)`

편의 helper:

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

### Search clause와 post-search `fields(...)`

이 구분은 중요합니다.

#### Search clause

`text`, `phrase`, `autocomplete`, `compound.filter` 등은 `$search` stage 내부에 들어갑니다.

Search index와 scoring에 직접 참여합니다.

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

위 `fields(...)`는 `$search` 내부 filter가 아닙니다.

실제 순서는 개념적으로 다음과 같습니다.

```text
$search
-> $match (fields)
-> metadata addFields / score match
-> paging
-> projection
```

검색 index 단계에서 걸러야 하는 조건은 `compound.filter(...)`를 사용하고, Search 후보를 만든 뒤 일반 Mongo 조건을 적용하려는 경우에만 `search().fields(...)`를 사용합니다.

---

### Search score

#### score 조정

`SearchScoreSpec` helper:

```java
SearchScoreSpec.boost(3.0)
SearchScoreSpec.boostByPath("popularity")
SearchScoreSpec.constant(1.0)
SearchScoreSpec.function(...)
```

Driver-native `SearchScore`도 clause에 바로 전달할 수 있습니다.

---

#### 결과에 score 추가

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsScore()
.findAll()
.execute();
```

기본 alias는 `score`입니다.

```java
.addFieldsScore("searchScore")
```

---

#### score range 후처리

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.matchScoreGte(1.5)
.findAll()
.execute();
```

지원:

```java
matchScoreGte(min)
matchScoreLte(max)
matchScoreBetween(min, max)
```

이 조건은 `$search` 내부 조건이 아니라 search score를 `$addFields`로 꺼낸 뒤 뒤쪽 `$match`로 적용됩니다.

따라서 `executePage()`와 `count().execute()`도 같은 score threshold를 기준으로 동작합니다.

---

### Search 정렬

일반 필드 정렬:

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

score 정렬은 `scoreDesc()` / `scoreAsc()`를 지원하며, 호출한 위치의 우선순위대로 추가됩니다.

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.scoreDesc()
.sorts(sort -> sort.desc("publishedAt"))
.findAll()
.execute();
```

또는:

```java
.sorts(sort -> sort.desc("publishedAt"))
.scoreDesc()
```

순서가 달라지면 sort priority도 달라집니다.

동점 결과의 안정적인 pagination이 필요하면 score 외에 unique/stable field 정렬을 같이 두는 것이 좋습니다.

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

`highlight(...)`는 `$search` stage option이고, `addFieldsHighlights(...)`는 highlight metadata를 결과 document field로 꺼냅니다.

기본 alias:

```text
highlights
```

Driver-native `SearchHighlight`도 직접 전달할 수 있습니다.

---

### Search score details

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsScoreDetails()
.findAll()
.execute();
```

`addFieldsScoreDetails()`는 내부적으로 `scoreDetails(true)`도 활성화합니다.

기본 alias는 `scoreDetails`입니다.

---

### Search sequence token pagination

sequence token을 결과에 추가:

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.addFieldsSequenceToken()
.findAll()
.execute();
```

기본 alias:

```text
searchSequenceToken
```

다음 페이지:

```java
.search("articles_default")
.text(text -> text.path("title").query("mongodb"))
.searchAfter(token)
.findAll()
.execute();
```

이전 방향:

```java
.searchBefore(token)
```

`searchAfter(...)`를 지정하면 기존 `searchBefore(...)` 값은 제거되고, 반대도 동일합니다.

---

### Search paging / page result

offset paging:

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

Atlas Search에는 서로 다른 두 count 경로가 있습니다.

#### 1) 최종 pipeline count: `count().execute()`

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

이 값은 `$search` 뒤의 post-search `fields(...)`, score match까지 반영한 **최종 pipeline 결과 수**입니다.

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

`executeSearchMeta()`는 `$searchMeta`를 사용합니다.

`SearchCountType`:

- `TOTAL`
- `LOWER_BOUND`

`countType(...)`를 지정하지 않고 `executeSearchMeta()`를 호출하면 기본은 `TOTAL`입니다.

`$searchMeta`는 Search 자체의 metadata count이므로 post-search `fields(...)`의 일반 `$match`를 실행하지 않습니다.

둘은 목적이 다르므로 구분해서 사용해야 합니다.

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

이 terminal은 Search pipeline count가 0보다 큰지 확인합니다.

---

### Search Driver-native 확장

stage option을 직접 보완할 수 있습니다.

```java
.search("articles_default")
.driverOptions(options -> options.option("someNewOption", value))
```

MongoDB Driver가 새 Search 기능을 먼저 제공하고 DSL convenience API가 아직 없는 경우에도 `operator(...)`, `driverOptions(...)`, `SortSpec.driver(...)`, `customizeAggregation(...)` 같은 escape hatch를 사용할 수 있습니다.

`$search` 뒤에 Driver-native aggregation stage가 필요하면 `stage(Bson)` / `stages(...)`를 사용할 수 있습니다. 추가 stage는 `$search` 직후, post-search `fields(...)`, metadata 추가, score filter, paging, projection보다 먼저 실행됩니다.

추가 stage는 `$search` 기반의 일반 결과/count pipeline에 적용됩니다. `executeSearchMeta()`는 `$searchMeta` 전용 metadata count 경로이므로 `stage(...)`를 적용하지 않습니다.

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

Vector Search는 MongoDB `$vectorSearch` stage를 구성합니다.

반드시 index 이름을 지정합니다.

```java
.vectorSearch("articles_vector_index")
```

기본 형태:

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

`path(...)`는 다음을 지원합니다.

- String
- Enum
- Driver-native `FieldSearchPath`
- custom wrapper

Manual vector index에서는 embedding vector field를 지정하고, MongoDB Automated Embedding index에서는 index된 text field를 지정합니다.

---

### Query vector

직접 vector를 넘기는 overload:

```java
.queryVector(float[] values)
.queryVector(double[] values)
.queryVector(Collection<Double> values)
.queryVector(BinaryVector values)
```

예:

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

현재 DSL 코어가 외부 embedding provider를 호출해 vector를 만드는 기능은 포함하지 않습니다. 애플리케이션에서 embedding을 만든 뒤 `queryVector(...)`에 전달하면 됩니다.

---

### MongoDB Automated Embedding text query

Automated Embedding vector index를 사용하는 경우 query text 자체를 전달할 수 있습니다.

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

필요하면 model override:

```java
.query("reactive mongodb search")
.model("model-name")
```

`model(...)`은 text `query(String)`과 함께 사용하는 옵션입니다.

---

### Driver-native vector query

MongoDB Driver의 `VectorSearchQuery`를 직접 전달할 수 있습니다.

```java
.vectorSearch("articles_vector_index")
.path("embedding")
.query(driverVectorSearchQuery)
.limit(20)
.findAll()
.execute();
```

새 Driver query type이 DSL convenience overload보다 먼저 추가된 경우 사용할 수 있는 escape hatch입니다.

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

`approximate(n)`은 `numCandidates(n)`의 convenience alias입니다.

```java
.numCandidates(200)
```

ANN 모드에서는 `numCandidates`가 필요합니다.

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

또는:

```java
.exact(true)
```

`exact(true)`를 설정하면 기존 `numCandidates` 값은 제거됩니다.

---

### Vector pre-filter / post-filter

이 둘도 구분해야 합니다.

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

중첩 조건:

```java
.filter(filter -> filter
    .and(f -> f.fields(
        pair("status", "PUBLISHED"),
        pair("deleted", false)
    ))
)
```

이 조건은 `$vectorSearch`의 `filter` 내부에 들어갑니다.

따라서 Vector Search 후보 선택 자체에 영향을 줍니다.

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

이 조건은 `$vectorSearch` 뒤에 일반 aggregation `$match`로 붙습니다.

개념적으로:

```text
$vectorSearch
-> $match (fields)
-> addFields vector score
-> projection
```

검색 후보 자체를 제한하려면 pre-filter를, vector 결과를 얻은 뒤 일반 조건을 적용하려면 post-filter를 사용합니다.

---

### Vector score

Vector Search score를 결과 필드로 꺼낼 수 있습니다.

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

기본 alias:

```text
vectorSearchScore
```

직접 alias:

```java
.addFieldsVectorSearchScore("similarity")
```

---

### Vector projection

최종 결과에서 큰 embedding field를 제외하는 식으로 사용할 수 있습니다.

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

Vector count는 `$vectorSearch`가 이미 `limit`을 가진 결과 pipeline 뒤에 `$count`를 붙입니다.

따라서 corpus 전체 count가 아니라 **현재 Vector Search pipeline이 반환하는 제한된 결과 수**입니다.

Vector Search에는 Atlas Search의 `$searchMeta`와 같은 metadata count terminal이 없습니다.

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

`$vectorSearch` 뒤에 Driver-native aggregation stage가 필요하면 `stage(Bson)` / `stages(...)`를 사용할 수 있습니다. 추가 stage는 `$vectorSearch` 직후, post-filter, metadata 추가, projection보다 먼저 실행됩니다.

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

### Nested / array embedding 옵션 (Driver 5.10+)

MongoDB Driver 5.10.0에서 추가된 nested Vector Search의 핵심 옵션은 기존 Vector DSL과 바로 연결할 수 있습니다.

- `filter(...)` / `filterFields(...)`: nested embedding의 leaf document에 적용되는 `filter`
- `parentFilter(...)` / `parentFilterFields(...)`: root document에 적용되는 `parentFilter`
- `nestedScoreMode(...)`: 한 document 안에서 여러 embedding이 매칭될 때 score 결합 방식 (`AVG` / `MAX`)

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

이 convenience API는 MongoDB Driver 기능을 다시 구현하는 것이 아니라 기존 `FieldsPair` / `FieldBuilder` 조건 DSL과 Driver 5.10의 `VectorSearchOptions`를 연결하는 역할만 합니다. nested Vector Search는 MongoDB 8.3+ 서버 기능입니다.

### Vector Search Driver options

```java
.vectorSearch("articles_vector_index")
.driverOptions(options -> ...)
```

`driverOptions(...)`는 convenience API에 아직 반영되지 않은 현재/미래 Driver 옵션을 직접 사용할 수 있는 escape hatch로 유지됩니다. 예를 들어 동일한 nested 옵션도 Driver API를 직접 사용할 수 있습니다.

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

## Search / Vector의 `preview()`와 `explain()`

Atlas Search와 Vector Search terminal도 동일하게 진단 API를 제공합니다.

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

