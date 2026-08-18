# MongoDB v1 migration safety tests

These tests are the release gate for the Spring-free v1 implementation that executes directly on the MongoDB Reactive Streams Driver.

## Contract under test

- Query, sort, update, lookup, and aggregation field strings are **physical MongoDB field paths**, with one convenience rule: a path segment named `id` is normalized to MongoDB `_id`.
- The DSL does not inspect Spring annotations or Java property metadata to rewrite any other path. Raw driver `Bson` is never rewritten, so callers using `Filters`/`Sorts`/`Aggregates` directly must use `_id` themselves.
- `MongoExecutionContext` owns only runtime/database/session access, entity BSON conversion, collection-name resolution, identifier access, and optional native-object exposure. `write/read` have a default MongoDB Driver POJO-codec implementation and can be overridden for framework-specific mapping such as Spring Data MongoDB.
- Criteria/update/aggregation execution is built directly from MongoDB driver `Bson` / `Filters` / `Updates` / `Aggregates` / `Sorts` / `Projections`.
- Entity serialization may still use an application-specific mapper through `MongoExecutionContext.write/read`; that does not alter query field strings.

For example, if an entity property `accountName` is serialized to MongoDB as `account_name`, callers query it with `pair("account_name", ...)`. The DSL does not rewrite `accountName` to `account_name`.

## Test layers

- `MongoExecutionContextMappingTest`: no external MongoDB required. Verifies the physical-field contract, direct driver BSON rendering, lookup raw `Bson` stages, entity codec responsibility, and legacy `FieldsPair` condition semantics.
- `ReactiveMongoDslMigrationSafetyIntegrationTest`: uses a real MongoDB cluster and reproduces production-style entity serialization and DSL calls for save/find/count/exists, nested physical paths, bulk operations, backup/history, atomic updates, aggregation paging/count, lookup, lookup+count, transaction commit/rollback, native unwrap, and the default driver execution context.

The integration suite creates a unique database named `rmdsl_v1_<cluster>_<random>` and drops that database after the suite.

## Environment

```bash
export TEST_CLUSTER_NAME='cluster0'
export TEST_USERNAME='...'
export TEST_PASSWORD='...'
export TEST_URL='@cluster0.1omf7sr.mongodb.net/?appName=Cluster0'
```

`TEST_URL` may also be a complete `mongodb://` or `mongodb+srv://` URI. When it is only the host suffix, the test builds the URI from `TEST_USERNAME`, `TEST_PASSWORD`, and `TEST_URL`.

## Commands

Run the normal test suite:

```bash
./gradlew clean test
```

Run the mandatory migration gate against MongoDB:

```bash
./gradlew mongoMigrationTest
```

`mongoMigrationTest` must execute the integration test source set; `NO-SOURCE` is not a successful migration verification.
