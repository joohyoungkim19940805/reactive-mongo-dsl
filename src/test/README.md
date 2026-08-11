# MongoDB v1 migration safety tests

These tests are the release gate for migrating the production Spring-backed DSL to the Spring-free v1 driver implementation.

## Test layers

- `MongoExecutionContextMappingTest`: no external MongoDB required. Verifies BSON field/id mapping, criteria rendering, update/aggregation mapping, and that Atlas Search/Vector Search stages remain native documents.
- `ReactiveMongoDslMigrationSafetyIntegrationTest`: uses a real MongoDB cluster and reproduces production-style entity mapping and DSL calls for save/find/count/exists, nested paths, bulk operations, backup/history, atomic updates, aggregation paging/count, lookup, lookup+count, transaction commit/rollback, native unwrap, and the default driver execution context.

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

`mongoMigrationTest` fails immediately when any required `TEST_*` variable is missing. This prevents a migration verification run from being reported as successful after silently skipping the real MongoDB tests.
